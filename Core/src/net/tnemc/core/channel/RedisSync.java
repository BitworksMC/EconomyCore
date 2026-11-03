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
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  private static volatile RedisSync instance;

  private final JedisPool pool;
  private final UUID runId = UUID.randomUUID();
  private final ExecutorService publisher = Executors.newSingleThreadExecutor(runnable->daemon(runnable, "TNE Redis Publisher"));
  private final Thread readerThread;

  private volatile boolean running = true;
  private boolean warnedDuplicateId = false;

  private RedisSync(final JedisPool pool) {

    this.pool = pool;
    this.readerThread = daemon(this::readLoop, "TNE Redis Reader");
    this.readerThread.start();
  }

  public static void start(final JedisPool pool) {

    instance = new RedisSync(pool);
  }

  public static void stop() {

    final RedisSync sync = instance;
    instance = null;
    if(sync != null) {
      sync.shutdown();
    }
  }

  /**
   * Queues a message for every other server. Does nothing when sync is not running.
   */
  public static void publish(final String channel, final Consumer<ByteArrayDataOutput> payload) {

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
    sync.publisher.execute(()->sync.send(entry));
  }

  private void send(final Map<String, String> entry) {

    long backoff = 1000;
    boolean warned = false;
    while(running) {
      try(final Jedis jedis = pool.getResource()) {
        jedis.xadd(STREAM, XAddParams.xAddParams().maxLen(STREAM_LENGTH).approximateTrimming(), entry);
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
        backoff = pause(backoff);
      }
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
      publisher.awaitTermination(5, TimeUnit.SECONDS);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    pool.close();
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
