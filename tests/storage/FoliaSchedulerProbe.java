import net.tnemc.core.TNECore;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.scheduler.Chore;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated Folia scheduler smoke test. Stops its server; never install on a live server. */
public final class FoliaSchedulerProbe extends JavaPlugin {

  private final AtomicInteger failures = new AtomicInteger();

  @Override
  public void onEnable() {

    final CompletableFuture<Void> main = new CompletableFuture<>();
    final CompletableFuture<Void> async = new CompletableFuture<>();
    final CompletableFuture<Void> repeating = new CompletableFuture<>();
    PluginCore.server().scheduler().createDelayedTask(()->{
      check("main task owns global region", Bukkit.isGlobalTickThread());
      check("resource provider", PluginCore.server().getResource("config.yml") != null);
      check("world provider", PluginCore.server().findWorld("world").isPresent());
      main.complete(null);
    }, new ChoreTime(0), ChoreExecution.MAIN_THREAD);
    PluginCore.server().scheduler().createDelayedTask(()->{
      check("secondary task is async", !Bukkit.isPrimaryThread());
      async.complete(null);
    }, new ChoreTime(0), ChoreExecution.SECONDARY);

    final AtomicInteger runs = new AtomicInteger();
    final AtomicReference<Chore<?>> task = new AtomicReference<>();
    task.set(PluginCore.server().scheduler().createRepeatingTask(()->{
      check("repeating task owns global region", Bukkit.isGlobalTickThread());
      if(runs.incrementAndGet() == 2) {
        task.get().cancel();
        repeating.complete(null);
      }
    }, new ChoreTime(2), new ChoreTime(2), ChoreExecution.MAIN_THREAD));

    CompletableFuture.allOf(main, async, repeating, TNECore.instance().snapshotOnlinePlayers(identifier->
      check("snapshot owns player", TNECore.instance().canAccessInventory(identifier))))
      .orTimeout(20, TimeUnit.SECONDS).whenComplete((ignored, failure)->{
        check("tasks completed", failure == null);
        getLogger().info("FOLIA_SCHEDULER_RESULT failures=" + failures.get());
        Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
      });
  }

  private void check(final String name, final boolean success) {

    getLogger().info(name + ": " + success);
    if(!success) {
      failures.incrementAndGet();
    }
  }
}
