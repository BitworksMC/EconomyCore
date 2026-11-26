package net.tnemc.core.io.storage;

/*
 * The New Economy
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.core.handlers.player.PlayerLeaveHandler;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.Transaction;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;
import net.tnemc.plugincore.core.io.storage.Datable;
import net.tnemc.plugincore.core.io.storage.StorageConnector;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Runs every TNE write of this server on one dedicated thread.
 *
 * <p>Within this server, saves never overlap, never race each other for the same account, and never
 * pile up as new Bukkit scheduler threads. {@link #reload(Runnable) Reloads} and shutdown save through
 * the same thread first, so no queued or unsaved change is overwritten or dropped.</p>
 *
 * <p>This does not coordinate servers. Each server keeps its own copy of every balance and writes it
 * back, so two servers sharing a database overwrite each other's changes unless they use different
 * {@code Core.Server.Name} values or Redis sync.</p>
 */
public final class SaveService {

  private static final long FLUSH_TIMEOUT_SECONDS = 120;
  //Below Paper's 60 second watchdog: reloads wait on the server thread.
  private static final long RELOAD_TIMEOUT_SECONDS = 20;

  private volatile Thread storageThread;
  private final AtomicBoolean autosaving = new AtomicBoolean(false);

  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable->{
    final Thread thread = new Thread(runnable, "TNE-Storage");
    thread.setDaemon(true);
    storageThread = thread;
    return thread;
  });

  /**
   * Starts saving modified data periodically. A run is skipped while the previous one is still
   * queued or running.
   *
   * @param intervalSeconds Seconds between autosaves.
   */
  public void start(final long intervalSeconds) {

    if(intervalSeconds > 0) {
      final ChoreTime interval = new ChoreTime((int)intervalSeconds, TimeUnit.SECONDS);
      //Starts on the server thread, the only one that may read item balances from inventories.
      PluginCore.server().scheduler().createRepeatingTask(this::autosave, interval, interval, ChoreExecution.MAIN_THREAD);
    }
  }

  private void autosave() {

    if(!autosaving.compareAndSet(false, true)) {
      return;
    }
    snapshotOnlinePlayers().whenComplete((ignored, failure)->{
      reportSnapshotFailure(failure);
      run(()->{
        try {
          PluginCore.log().inform("Running AutoSaver.");
          saveDirty();
        } finally {
          autosaving.set(false);
        }
      });
    });
  }

  /**
   * Queues a save of one account, regardless of whether it is marked as modified.
   */
  public void save(final Account account) {

    run(()->{
      //A queued logout/creation save must not resurrect a deleted or replaced account.
      if(TNECore.eco().account().findAccount(account.getIdentifier()).orElse(null) == account) {
        datable(Account.class).ifPresent(datable->datable.store(connector(), account,
                                                               account.getIdentifier().toString()));
      }
    });
  }

  /**
   * Deletes an account after every earlier queued write and before later creation saves.
   */
  public void delete(final String identifier) {

    run(()->datable(Account.class).ifPresent(datable->datable.delete(connector(), identifier)));
  }

  /**
   * Queues a save of every modified account and receipt.
   */
  public void saveAll() {

    snapshotOnlinePlayers().whenComplete((ignored, failure)->{
      reportSnapshotFailure(failure);
      run(this::saveDirty);
    });
  }

  /**
   * Queues storage work on the storage thread.
   */
  public void run(final Runnable task) {

    try {
      executor.execute(guard(task));
    } catch(final RejectedExecutionException shutDown) {
      guard(task).run();
    }
  }

  /**
   * Keeps account identity and persistence changes together without letting a storage task
   * acquire the balance lock. Reload holds that lock while waiting for this same worker.
   * The operation must use only storage and account state, without invoking plugin callbacks.
   */
  boolean rewriteAccount(final BooleanSupplier rewrite) {

    if(Thread.currentThread() == storageThread) {
      return false;
    }
    synchronized(Transaction.BALANCE_LOCK) {
      final CountDownLatch started = new CountDownLatch(1);
      final AtomicBoolean claimed = new AtomicBoolean(false);
      final CompletableFuture<Boolean> result = new CompletableFuture<>();
      try {
        executor.execute(()->startRewrite(rewrite, started, claimed, result));
      } catch(final RejectedExecutionException shutDown) {
        return false;
      }
      if(!awaitRewriteStart(started, claimed)) {
        return false;
      }
      // Once started, learn the outcome before allowing a conflicting cache change.
      try {
        return result.join();
      } catch(final CompletionException failure) {
        PluginCore.log().error("Account storage rewrite failed.", new Exception(failure.getCause()), DebugLevel.OFF);
        return false;
      }
    }
  }

  private static void startRewrite(final BooleanSupplier rewrite, final CountDownLatch started,
                                   final AtomicBoolean claimed, final CompletableFuture<Boolean> result) {

    if(!claimed.compareAndSet(false, true)) {
      return;
    }
    started.countDown();
    try {
      result.complete(rewrite.getAsBoolean());
    } catch(final Throwable failure) {
      result.completeExceptionally(failure);
    }
  }

  private static boolean awaitRewriteStart(final CountDownLatch started, final AtomicBoolean claimed) {

    try {
      return started.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS) || !claimed.compareAndSet(false, true);
    } catch(final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return !claimed.compareAndSet(false, true);
    }
  }

  /**
   * Saves all unsaved changes, then replaces in-memory data with database data. No balance change
   * can happen in between, and nothing is replaced unless every account was saved.
   *
   * @param reload Replaces in-memory data, e.g. by reloading accounts from the database.
   *
   * @return false if saving failed and the reload was cancelled.
   */
  public boolean reload(final Runnable reload) {

    //Entity scheduler tasks may themselves be waiting for the balance lock.
    if(!awaitSnapshot(RELOAD_TIMEOUT_SECONDS)) {
      return false;
    }
    synchronized(Transaction.BALANCE_LOCK) {
      if(!flush(RELOAD_TIMEOUT_SECONDS)) {
        PluginCore.log().error("Reload cancelled: unsaved balance changes could not be saved.", DebugLevel.OFF);
        return false;
      }
      reload.run();
      return true;
    }
  }

  /**
   * Waits until all queued work has finished and every modified account and receipt was saved.
   *
   * @return true if no account is left with unsaved changes.
   */
  private boolean flush(final long timeoutSeconds) {

    if(Thread.currentThread() == storageThread) {
      saveDirty();
      return accountsSaved();
    }

    try {
      executor.submit(this::saveDirty).get(timeoutSeconds, TimeUnit.SECONDS);
    } catch(final RejectedExecutionException shutDown) {
      guard(this::saveDirty).run();
    } catch(final TimeoutException e) {
      PluginCore.log().error("TNE storage did not finish saving within " + timeoutSeconds + " seconds.", DebugLevel.OFF);
      return false;
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch(final ExecutionException e) {
      PluginCore.log().error("TNE storage flush failed.", e, DebugLevel.OFF);
      return false;
    }
    return accountsSaved();
  }

  /**
   * Failed saves keep their account dirty. Receipts are left out on purpose: they only hold
   * history, and one receipt the database rejects must not block every reload.
   */
  private static boolean accountsSaved() {

    return TNECore.eco().account().getAccounts().values().stream().noneMatch(Account::isDirty);
  }

  /**
   * Saves everything and stops the storage thread without scheduling work after plugin disable.
   */
  public void shutdown() {

    final boolean snapshot = awaitSnapshot(FLUSH_TIMEOUT_SECONDS);
    final boolean saved = flush(FLUSH_TIMEOUT_SECONDS);
    if(!snapshot || !saved) {
      PluginCore.log().error("TNE could not save every account before shutdown; unsaved balance changes are lost.",
                             DebugLevel.OFF);
    }
    executor.shutdown();
    try {
      executor.awaitTermination(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void saveDirty() {

    datable(Account.class).ifPresent(datable->datable.storeAll(connector(), null));
    datable(Receipt.class).ifPresent(datable->datable.storeAll(connector(), null));
  }

  /**
   * Item balances live in player inventories, so picking items up or dropping them changes no
   * wallet. Copies them into the wallets of online players on each player's owning thread, then
   * allows storage work to start.
   */
  private CompletableFuture<Void> snapshotOnlinePlayers() {

    if(TNECore.eco().currency().currencies().stream().noneMatch(currency->currency.type().supportsItems())) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      return TNECore.instance().snapshotOnlinePlayers(identifier->
        TNECore.eco().account().findAccount(identifier).ifPresent(account->{
          if(account instanceof final PlayerAccount player) {
            player.getPlayer().ifPresent(provider->PlayerLeaveHandler.syncItemHoldings(provider, player));
          }
          account.markDirty();
        }));
    } catch(final RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private boolean awaitSnapshot(final long timeoutSeconds) {

    try {
      snapshotOnlinePlayers().get(timeoutSeconds, TimeUnit.SECONDS);
      return true;
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch(final ExecutionException | TimeoutException | RuntimeException e) {
      reportSnapshotFailure(e);
      return false;
    }
  }

  private void reportSnapshotFailure(final Throwable failure) {

    if(failure != null) {
      PluginCore.log().error("Failed to copy online item balances before saving.",
                             failure instanceof final Exception exception? exception : new Exception(failure), DebugLevel.OFF);
    }
  }

  private Runnable guard(final Runnable task) {

    return ()->{
      try {
        task.run();
      } catch(final Throwable e) {
        //An escaped exception would cancel the periodic autosave for good.
        PluginCore.log().error("TNE storage task failed.", e instanceof final Exception ex? ex : new Exception(e), DebugLevel.OFF);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> Optional<Datable<T>> datable(final Class<T> type) {

    return Optional.ofNullable((Datable<T>)TNECore.instance().storage().getEngine().datables().get(type));
  }

  private static StorageConnector<?> connector() {

    return TNECore.instance().storage().getConnector();
  }

}
