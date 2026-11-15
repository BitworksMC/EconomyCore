package net.tnemc.core.channel;
import java.io.DataInput;
import java.util.concurrent.atomic.AtomicInteger;
public final class BalanceHandler {
  public static final AtomicInteger received = new AtomicInteger();
  public static void handle(final DataInput input) { received.incrementAndGet(); }
  public static final class SyncApplyException extends RuntimeException {}
}
