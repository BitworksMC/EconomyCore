import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/** Scheduling for disposable Paper/Folia integration probes. */
public final class ProbeScheduler {
  private ProbeScheduler() { }

  public static boolean folia() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }

  /** Runs on the thread owning spawn, including tests which drop and remove items there. */
  public static void run(JavaPlugin plugin, Runnable task) {
    if (!folia()) {
      Bukkit.getScheduler().runTaskLater(plugin, task, 40L);
      return;
    }
    Location location = Bukkit.getWorlds().getFirst().getSpawnLocation();
    location.getWorld().getChunkAtAsync(location).thenRun(() ->
        Bukkit.getRegionScheduler().execute(plugin, location, task));
  }

  /** For thread-independent deadlines and shutdown only. */
  public static void later(JavaPlugin plugin, Runnable task, long ticks) {
    if (folia()) {
      Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> task.run(), Math.max(1, ticks * 50), TimeUnit.MILLISECONDS);
    } else {
      Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }
  }

  /** Starts a distinct server tick, without adding the preceding probe's workload to its deadline. */
  public static void next(JavaPlugin plugin, Runnable task) {
    if (folia()) {
      Location location = Bukkit.getWorlds().getFirst().getSpawnLocation();
      Bukkit.getRegionScheduler().runDelayed(plugin, location, ignored -> task.run(), 1L);
    } else {
      Bukkit.getScheduler().runTaskLater(plugin, task, 1L);
    }
  }
}
