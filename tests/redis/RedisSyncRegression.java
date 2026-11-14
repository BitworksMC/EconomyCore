import net.tnemc.core.channel.BalanceHandler;
import net.tnemc.core.channel.ChannelSecurity;
import net.tnemc.core.channel.RedisSync;
import net.tnemc.plugincore.PluginCore;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the exact RedisSync.java against a dedicated real Redis, with controlled failures. */
public final class RedisSyncRegression {
  private static final String HOST = "127.0.0.1";
  private static int port;
  private static Path root;
  private static int checks;

  public static void main(final String[] args) throws Exception {
    port = Integer.parseInt(args[0]);
    root = Path.of(args[1]);
    Files.createDirectories(root);
    try(final Jedis admin = new Jedis(HOST, port)) {
      final long before = admin.xlen("tne:sync");
      healthyShutdown(admin, before);
      outageRestart(admin, before + 100);
      lostAcknowledgement(admin, before + 107);
      unwritableOutbox(admin, before + 108);
      check(BalanceHandler.received.get() == 0, "a stopped reader consumed a later run's messages");
    } finally {
      RedisSync.stop();
    }
    System.out.println("REDIS_SYNC_RESULT checks=" + checks + " failures=0");
  }

  private static void healthyShutdown(final Jedis admin, final long before) throws Exception {
    useDirectory("healthy");
    final ControlledPool pool = new ControlledPool(false, false, true);
    RedisSync.start(pool);
    publish(100);
    check(pool.entered.await(3, TimeUnit.SECONDS), "first send did not start");
    final Thread stop = new Thread(RedisSync::stop);
    stop.start();
    Thread.sleep(100);
    pool.release.countDown();
    stop.join(7000);
    check(!stop.isAlive(), "healthy shutdown exceeded deadline");
    check(admin.xlen("tne:sync") == before + 100, "healthy shutdown discarded updates");
    check(pending() == 0, "healthy shutdown left pending files");
    System.out.println("HEALTHY_SHUTDOWN accepted=100 delivered=100");
  }

  private static void outageRestart(final Jedis admin, final long before) throws Exception {
    useDirectory("outage");
    RedisSync.start(new ControlledPool(true, false, false));
    publish(7);
    final long start = System.nanoTime();
    RedisSync.stop();
    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6500, "outage shutdown was not bounded");
    check(pending() == 7, "outage updates were not persisted");
    check(admin.xlen("tne:sync") == before, "outage unexpectedly sent updates");
    ChannelSecurity.token = "rotated-fixture-token";
    RedisSync.start(new JedisPool(HOST, port));
    waitForOutbox();
    check(admin.xlen("tne:sync") == before + 7, "restart did not deliver all persisted updates");
    final var instanceField = RedisSync.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    final var runField = RedisSync.class.getDeclaredField("runId");
    runField.setAccessible(true);
    final UUID run = (UUID)runField.get(instanceField.get(null));
    for(final var entry : admin.xrevrange("tne:sync", "+", "-", 7)) {
      try(final var input = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(entry.getFields().get("d"))))) {
        check(UUID.fromString(input.readUTF()).equals(PluginCore.instance().getServerID()), "replay server ID changed");
        check(UUID.fromString(input.readUTF()).equals(run), "replay retained previous run ID");
        check(input.readUTF().equals(ChannelSecurity.token), "replay retained old shared token");
      }
    }
    check(BalanceHandler.received.get() == 0, "server consumed its own replay");
    RedisSync.stop();
    System.out.println("OUTAGE_RESTART accepted=7 persisted=7 delivered=7");
  }

  private static void lostAcknowledgement(final Jedis admin, final long before) throws Exception {
    useDirectory("lost-acknowledgement");
    final ControlledPool pool = new ControlledPool(false, true, false);
    RedisSync.start(pool);
    publish(1);
    check(pool.entered.await(3, TimeUnit.SECONDS), "Redis did not accept interrupted acknowledgement");
    RedisSync.stop();
    check(admin.xlen("tne:sync") == before + 1, "retries duplicated an accepted update");
    check(pending() == 1, "lost acknowledgement was not retained");
    RedisSync.start(new JedisPool(HOST, port));
    waitForOutbox();
    check(admin.xlen("tne:sync") == before + 1, "restart duplicated previously accepted update");
    RedisSync.stop();
    System.out.println("LOST_ACKNOWLEDGEMENT accepted=1 deliveries=1 after-restart=1");
  }

  private static void publish(final int count) {
    for(int i = 0; i < count; i++) {
      final int index = i;
      RedisSync.publish(RedisSync.BALANCE, output->output.writeUTF("fixture-change-" + index));
    }
  }

  private static void unwritableOutbox(final Jedis admin, final long before) throws Exception {
    useDirectory("unwritable");
    RedisSync.start(new JedisPool(HOST, port));
    final Path outbox = PluginCore.directory().toPath().resolve("redis-outbox");
    final Path saved = outbox.resolveSibling("saved-outbox");
    Files.move(outbox, saved);
    Files.writeString(outbox, "controlled filesystem obstruction");
    try {
      publish(1);
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while(admin.xlen("tne:sync") == before && System.nanoTime() < deadline) Thread.sleep(25);
      RedisSync.stop();
      check(admin.xlen("tne:sync") == before + 1, "filesystem failure interrupted transaction publishing");
    } finally {
      RedisSync.stop();
      Files.delete(outbox);
      Files.move(saved, outbox);
    }
    System.out.println("UNWRITABLE_OUTBOX caller-exception=false delivered=1");
  }

  private static void useDirectory(final String name) throws Exception {
    final Path directory = root.resolve(name + "-" + UUID.randomUUID());
    Files.createDirectories(directory);
    PluginCore.dataDirectory = directory.toFile();
  }

  private static long pending() throws Exception {
    try(final var files = Files.list(PluginCore.directory().toPath().resolve("redis-outbox"))) {
      return files.filter(path->path.toString().endsWith(".pending")).count();
    }
  }

  private static void waitForOutbox() throws Exception {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while(pending() != 0 && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    check(pending() == 0, "persisted outbox did not drain");
  }

  private static void check(final boolean success, final String message) {
    checks++;
    if(!success) throw new AssertionError(message);
  }

  private static final class ControlledPool extends JedisPool {
    private final boolean offline;
    private final boolean loseReply;
    private final boolean block;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private ControlledPool(final boolean offline, final boolean loseReply, final boolean block) {
      super(HOST, port);
      this.offline = offline;
      this.loseReply = loseReply;
      this.block = block;
    }

    @Override
    public Jedis getResource() {
      if(offline) throw new JedisConnectionException("controlled offline connection");
      return new Jedis(HOST, port) {
        @Override
        public Object eval(final String script, final List<String> keys, final List<String> args) {
          if(block) {
            entered.countDown();
            try { release.await(); } catch(final InterruptedException ex) { throw new JedisConnectionException(ex); }
          }
          final Object result = super.eval(script, keys, args);
          entered.countDown();
          if(loseReply) throw new JedisConnectionException("controlled reply lost after Redis accepted update");
          return result;
        }
      };
    }
  }
}
