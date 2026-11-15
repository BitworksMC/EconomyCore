package net.tnemc.core;

import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Default single-server-thread snapshot dispatch; Folia overrides the platform hook. */
final class OnlinePlayerSnapshots {

  private OnlinePlayerSnapshots() { }

  static CompletableFuture<Void> snapshot(final TNECore core, final Consumer<UUID> snapshot) {

    return run(core, ()->{
      for(final String name : PluginCore.server().onlinePlayersList()) {
        TNECore.eco().account().findAccount(name).ifPresent(account->snapshot.accept(account.getIdentifier()));
      }
    });
  }

  static CompletableFuture<Void> run(final TNECore core, final Runnable action) {

    final CompletableFuture<Void> result = new CompletableFuture<>();
    final Runnable task = ()->{
      try {
        action.run();
        result.complete(null);
      } catch(final Throwable failure) {
        result.completeExceptionally(failure);
      }
    };
    if(core.isMainThread()) {
      task.run();
    } else {
      try {
        PluginCore.server().scheduler().createDelayedTask(task, new ChoreTime(0), ChoreExecution.MAIN_THREAD);
      } catch(final RuntimeException failure) {
        result.completeExceptionally(failure);
      }
    }
    return result;
  }
}
