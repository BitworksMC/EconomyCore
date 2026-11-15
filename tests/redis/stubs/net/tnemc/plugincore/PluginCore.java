package net.tnemc.plugincore;

import java.io.File;
import java.util.UUID;

/** Minimal host fixture; RedisSync itself and Jedis are the production implementations. */
public final class PluginCore {
  private static final PluginCore INSTANCE = new PluginCore();
  private static final UUID ID = UUID.randomUUID();
  public static File dataDirectory;
  public static PluginCore instance() { return INSTANCE; }
  public static File directory() { return dataDirectory; }
  public UUID getServerID() { return ID; }
  public static PluginCore log() { return INSTANCE; }
  public void inform(final String message) { System.out.println(message); }
  public void error(final String message, final Object level) { System.out.println(message); }
}
