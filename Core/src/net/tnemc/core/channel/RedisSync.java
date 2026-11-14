package net.tnemc.core.channel;

/*
 * The New Economy
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Redis transport for cross-server sync.
 *
 * <p>Messages go through one Redis stream instead of pub/sub, because pub/sub drops whatever is
 * sent while a server is disconnected. One thread reads the stream and, after Redis or the network
 * drops, continues from the last message it read. One thread publishes in order and retries until
 * Redis is reachable again. Together no change is missed during an outage, as long as Redis keeps
 * the stream (it is trimmed to the newest {@value #STREAM_LENGTH} messages).</p>
 *
 * <p>Accepted outgoing messages are written to {@code redis-outbox} in the plugin directory before
 * being queued. Shutdown drains healthy connections and retains undelivered files for the next
 * start. An atomic Redis publisher watermark prevents duplicate stream entries after lost replies.</p>
 *
 * <p>Every message starts with the server ID, a random ID for this run of the server and the
 * shared token. The run ID tells this server's own messages apart from those of a server that was
 * copied together with its {@code Core.ServerID}.</p>
 */
public final class RedisSync {

  public static final String BALANCE = "tne:balance";
  public static final String MESSAGE = "tne:message";

  private static final String STREAM = "tne:sync";
  private static final int STREAM_LENGTH = 50_000;
  private static final int READ_BLOCK_MILLIS = 5000;
  private static final long MAX_BACKOFF_MILLIS = 30_000;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
  //The publisher is ordered. Remembering its last accepted sequence makes a retried XADD
  //idempotent, including a restart after Redis accepted it but before its local file was removed.
  private static final String PUBLISH_SCRIPT = """
          local last = redis.call('GET', KEYS[2])
          if last and tonumber(last) >= tonumber(ARGV[1]) then return 0 end
          redis.call('XADD', KEYS[1], 'MAXLEN', '~', ARGV[2], '*', 'c', ARGV[3], 'd', ARGV[4])
          redis.call('SET', KEYS[2], ARGV[1])
          return 1
          """;

  private static volatile RedisSync instance;

  private final JedisPool pool;
  private final UUID runId = UUID.randomUUID();
  private final ExecutorService publisher = Executors.newSingleThreadExecutor(runnable->daemon(runnable, "TNE Redis Publisher"));
  private final Thread readerThread;
  private final Path outbox;
  private UUID publisherId;
  private long nextMessage;
  private long lastPublished;
  private final Set<Path> memoryOnly = ConcurrentHashMap.newKeySet();

  private volatile boolean running = true;
  private volatile boolean publishing = true;
  private boolean warnedDuplicateId = false;

  private RedisSync(final JedisPool pool) {

    this.pool = pool;
    this.outbox = PluginCore.directory().toPath().resolve("redis-outbox");
    restoreOutbox();
    this.readerThread = daemon(this::readLoop, "TNE Redis Reader");
    this.readerThread.start();
  }

  public static synchronized void start(final JedisPool pool) {

    if(instance != null) {
      throw new IllegalStateException("Redis sync is already running");
    }
    instance = new RedisSync(pool);
  }

  public static synchronized void stop() {

    final RedisSync sync = instance;
    instance = null;
    if(sync != null) {
      sync.shutdown();
    }
  }

  /**
   * Queues a message for every other server. Does nothing when sync is not running.
   */
  public static synchronized void publish(final String channel, final Consumer<ByteArrayDataOutput> payload) {

    final RedisSync sync = instance;
    if(sync == null) {
      return;
    }

    final ByteArrayDataOutput out = ByteStreams.newDataOutput();
    out.writeUTF(PluginCore.instance().getServerID().toString());
    out.writeUTF(sync.runId.toString());
    ChannelSecurity.writeToken(out);
    payload.accept(out);
    final Map<String, String> entry = Map.of("c", channel, "d", Base64.getEncoder().encodeToString(out.toByteArray()));
    final Path pending = sync.persist(entry);
    sync.publisher.execute(()->sync.send(pending, entry));
  }

  private void send(final Path pending, final Map<String, String> entry) {

    long backoff = 1000;
    boolean warned = false;
    while(publishing) {
      try(final Jedis jedis = pool.getResource()) {
        jedis.eval(PUBLISH_SCRIPT, List.of(STREAM, "tne:sync:publisher:" + publisherId),
                   List.of(Long.toString(sequence(pending)), Integer.toString(STREAM_LENGTH), entry.get("c"), entry.get("d")));
        removePublished(pending);
        if(warned) {
          PluginCore.log().inform("Redis is reachable again; queued sync messages are being sent.");
        }
        return;
      } catch(final RuntimeException e) {
        if(!warned) {
          PluginCore.log().error("Unable to publish to Redis (" + e.getMessage() + "); sync messages are queued until it is back.",
                                 DebugLevel.OFF);
          warned = true;
        }
        backoff = publisherPause(backoff);
      }
    }
  }

  /**
   * A successful enqueue must survive a Redis outage followed by a normal server stop. Persist it
   * before putting it on the executor; queued Runnables alone disappear when that process exits.
   */
  private Path persist(final Map<String, String> entry) {

    nextMessage = Math.max(nextMessage + 1, System.currentTimeMillis());
    final Path pending = outbox.resolve(nextMessage + "-" + UUID.randomUUID() + ".pending");
    final Path temporary = pending.resolveSibling(pending.getFileName() + ".tmp");
    try {
      try(final FileOutputStream file = new FileOutputStream(temporary.toFile());
          final DataOutputStream data = new DataOutputStream(file)) {
        data.writeUTF(entry.get("c"));
        final byte[] bytes = entry.get("d").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
        data.flush();
        file.getFD().sync();
      }
      try {
        Files.move(temporary, pending, StandardCopyOption.ATOMIC_MOVE);
      } catch(final AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, pending);
      }
      return pending;
    } catch(final IOException e) {
      //Account mutation has already happened when this is called. An exception here would abort
      //a transfer between its debit and credit. Keep retrying in memory and make the durability
      //failure explicit rather than turning a filesystem problem into a partially applied payment.
      memoryOnly.add(pending);
      PluginCore.log().error("Cannot persist Redis sync update in " + outbox + ": " + e.getMessage()
                             + ". Retrying in memory only; restore writable storage before stopping this server.", DebugLevel.OFF);
      return pending;
    }
  }

  private void restoreOutbox() {

    try {
      Files.createDirectories(outbox);
      final Path identity = outbox.resolve("publisher.id");
      if(Files.exists(identity)) {
        publisherId = UUID.fromString(Files.readString(identity).trim());
      } else {
        publisherId = UUID.randomUUID();
        writeDurable(identity, publisherId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      final Path previous = outbox.resolve("publisher.sequence");
      if(Files.exists(previous)) {
        nextMessage = Long.parseLong(Files.readString(previous).trim());
        lastPublished = nextMessage;
      }
      final List<Path> pending;
      try(final var files = Files.list(outbox)) {
        pending = files.filter(path->path.getFileName().toString().endsWith(".pending"))
                .sorted(Comparator.comparing(path->path.getFileName().toString())).toList();
      }
      for(final Path path : pending) {
        final Map<String, String> entry = readPending(path);
        nextMessage = Math.max(nextMessage, sequence(path));
        publisher.execute(()->send(path, entry));
      }
      if(!pending.isEmpty()) {
        PluginCore.log().inform("Restored " + pending.size() + " unsent Redis sync updates from " + outbox + ".");
      }
    } catch(final IOException | RuntimeException e) {
      publishing = false;
      publisher.shutdownNow();
      throw new IllegalStateException("Cannot restore pending Redis sync updates from " + outbox, e);
    }
  }

  private Map<String, String> readPending(final Path path) throws IOException {

    try(final DataInputStream file = new DataInputStream(Files.newInputStream(path))) {
      final String channel = file.readUTF();
      final int length = file.readInt();
      if(length < 0 || length > 16 * 1024 * 1024) {
        throw new IOException("Invalid Redis outbox payload size: " + path);
      }
      final byte[] stored = file.readNBytes(length);
      if(stored.length != length) {
        throw new IOException("Incomplete Redis outbox entry: " + path);
      }
      final byte[] encoded = Base64.getDecoder().decode(stored);
      //Use this process's run ID when replaying. Otherwise its reader would apply its own previous
      //run's balance change again, even though that balance was already saved locally.
      try(final DataInputStream message = new DataInputStream(new ByteArrayInputStream(encoded))) {
        message.readUTF();
        message.readUTF();
        message.readUTF();
        final ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF(PluginCore.instance().getServerID().toString());
        output.writeUTF(runId.toString());
        ChannelSecurity.writeToken(output);
        output.write(message.readAllBytes());
        return Map.of("c", channel, "d", Base64.getEncoder().encodeToString(output.toByteArray()));
      }
    }
  }

  private void removePublished(final Path pending) {

    memoryOnly.remove(pending);
    lastPublished = Math.max(lastPublished, sequence(pending));
    try {
      writeDurable(outbox.resolve("publisher.sequence"),
                   Long.toString(lastPublished).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Files.deleteIfExists(pending);
    } catch(final IOException e) {
      PluginCore.log().error("Redis accepted an update, but its outbox file could not be removed: " + pending
                             + ". Check filesystem permissions before restarting.", DebugLevel.OFF);
    }
  }

  private static long sequence(final Path pending) {

    return Long.parseLong(pending.getFileName().toString().split("-", 2)[0]);
  }

  private static void writeDurable(final Path destination, final byte[] content) throws IOException {

    final Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
    try(final FileOutputStream file = new FileOutputStream(temporary.toFile())) {
      file.write(content);
      file.getFD().sync();
    }
    try {
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch(final AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void readLoop() {

    //ponytail: starts after the newest message, so changes sent while this server was starting up
    //can be applied on top of data that already contains them; a per-account version would close that.
    StreamEntryID last = null;
    long backoff = 1000;
    boolean lost = false;
    while(running) {
      try(final Jedis jedis = pool.getResource()) {
        if(last == null) {
          final List<StreamEntry> newest = jedis.xrevrange(STREAM, "+", "-", 1);
          last = newest.isEmpty()? new StreamEntryID(0, 0) : newest.get(0).getID();
        }
        if(lost) {
          PluginCore.log().inform("Redis sync reconnected; catching up on changes made while it was down.");
          lost = false;
        }
        backoff = 1000;
        last = readBatch(jedis, last);
      } catch(final RuntimeException e) {
        if(!running) {
          return;
        }
        if(!lost) {
          PluginCore.log().error("Redis sync connection lost (" + e.getMessage() + "); reconnecting.", DebugLevel.OFF);
          lost = true;
        }
        backoff = pause(backoff);
      }
    }
  }

  /**
   * Reads the messages after {@code last}, waiting a few seconds for new ones.
   *
   * @return the ID of the last message read.
   */
  private StreamEntryID readBatch(final Jedis jedis, final StreamEntryID last) {

    final List<Map.Entry<String, List<StreamEntry>>> read =
            jedis.xread(XReadParams.xReadParams().block(READ_BLOCK_MILLIS).count(500), Map.of(STREAM, last));
    StreamEntryID newest = last;
    for(final Map.Entry<String, List<StreamEntry>> stream : read == null? List.<Map.Entry<String, List<StreamEntry>>>of() : read) {
      for(final StreamEntry entry : stream.getValue()) {
        if(!running) {
          return newest;
        }
        newest = entry.getID();
        receive(entry.getFields().get("c"), entry.getFields().get("d"));
      }
    }
    return newest;
  }

  private void receive(final String channel, final String data) {

    try(final DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {

      final UUID server = UUID.fromString(in.readUTF());
      final UUID run = UUID.fromString(in.readUTF());
      if(run.equals(runId)) {
        return;
      }
      if(server.equals(PluginCore.instance().getServerID())) {
        warnDuplicateId(server);
      }
      if(!ChannelSecurity.validate(in, channel)) {
        return;
      }

      switch(channel) {
        case BALANCE -> BalanceHandler.handle(in);
        case MESSAGE -> MessageHandler.handle(in);
        default -> { }
      }
    } catch(final BalanceHandler.SyncApplyException failure) {
      throw failure;
    } catch(final IOException | RuntimeException e) {
      PluginCore.log().error("Ignored a malformed " + channel + " sync message. Every server must run the same TNE version.",
                             DebugLevel.STANDARD);
    }
  }

  private void warnDuplicateId(final UUID server) {

    if(warnedDuplicateId) {
      return;
    }
    warnedDuplicateId = true;
    PluginCore.log().error("Another server uses this server's Core.ServerID (" + server + "), probably a copied config.yml. "
                           + "Remove Core.ServerID from one server's config.yml and restart it so each server has its own ID.",
                           DebugLevel.OFF);
  }

  private void shutdown() {

    running = false;
    readerThread.interrupt();
    publisher.shutdown();
    try {
      //Keep publishing while draining accepted updates. The reader's stop flag must not cancel
      //the publisher queue; a disconnected publisher is stopped only after this bounded wait.
      if(!publisher.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        publishing = false;
        publisher.shutdownNow();
        PluginCore.log().error("Redis sync did not finish before shutdown; unsent updates remain in " + outbox
                               + " and will be retried on the next start.", DebugLevel.OFF);
      }
    } catch(final InterruptedException e) {
      publishing = false;
      publisher.shutdownNow();
      Thread.currentThread().interrupt();
      PluginCore.log().error("Redis shutdown interrupted; unsent updates remain in " + outbox + ".", DebugLevel.OFF);
    }
    if(!memoryOnly.isEmpty()) {
      PluginCore.log().error(memoryOnly.size() + " Redis sync updates could not be persisted or delivered because storage was unwritable. "
                             + "Those updates cannot survive this shutdown; reconcile balances before restarting traffic.", DebugLevel.OFF);
    }
    pool.close();
  }

  private long publisherPause(final long backoff) {

    try {
      Thread.sleep(backoff);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
      publishing = false;
    }
    return Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
  }

  private long pause(final long backoff) {

    try {
      Thread.sleep(backoff);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
    return Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
  }

  private static Thread daemon(final Runnable runnable, final String name) {

    final Thread thread = new Thread(runnable, name);
    thread.setDaemon(true);
    return thread;
  }
}
