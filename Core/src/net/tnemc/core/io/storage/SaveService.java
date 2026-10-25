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
import net.tnemc.plugincore.core.io.storage.StorageManager;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    snapshotOnlinePlayers();
    run(()->{
      try {
        PluginCore.log().inform("Running AutoSaver.");
        saveDirty();
      } finally {
        autosaving.set(false);
      }
    });
  }

  /**
   * Queues a save of one account, regardless of whether it is marked as modified.
   */
  public void save(final Account account) {

    run(()->datable(Account.class).ifPresent(datable->datable.store(connector(), account,
                                                                        account.getIdentifier().toString())));
  }

  /**
   * Queues a save of every modified account and receipt.
   */
  public void saveAll() {

    snapshotOnlinePlayers();
    run(this::saveDirty);
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
   * Saves all unsaved changes, then replaces in-memory data with database data. No balance change
   * can happen in between, and nothing is replaced unless every account was saved.
   *
   * @param reload Replaces in-memory data, e.g. by reloading accounts from the database.
   *
   * @return false if saving failed and the reload was cancelled.
   */
  public boolean reload(final Runnable reload) {

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

    snapshotOnlinePlayers();
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
   * Saves everything, stops the storage thread and releases plugin core's storage worker.
   */
  public void shutdown() {

    if(!flush(FLUSH_TIMEOUT_SECONDS)) {
      PluginCore.log().error("TNE could not save every account before shutdown; unsaved balance changes are lost.",
                             DebugLevel.OFF);
    }
    executor.shutdown();
    try {
      executor.awaitTermination(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    stopPluginCoreWorker();
  }

  private void saveDirty() {

    datable(Account.class).ifPresent(datable->datable.storeAll(connector(), null));
    datable(Receipt.class).ifPresent(datable->datable.storeAll(connector(), null));
  }

  /**
   * Item balances live in player inventories, so picking items up or dropping them changes no
   * wallet. Copies them into the wallets of online players and marks those for saving. Does
   * nothing off the server thread, where inventories cannot be read.
   */
  private void snapshotOnlinePlayers() {

    if(!TNECore.instance().isMainThread()
       || TNECore.eco().currency().currencies().stream().noneMatch(currency->currency.type().supportsItems())) {
      return;
    }

    for(final String name : onlinePlayers()) {
      TNECore.eco().account().findAccount(name).ifPresent(account->{
        if(account instanceof final PlayerAccount player) {
          player.getPlayer().ifPresent(provider->PlayerLeaveHandler.syncItemHoldings(provider, player));
        }
        account.markDirty();
      });
    }
  }

  private Collection<String> onlinePlayers() {

    try {
      return PluginCore.server().onlinePlayersList();
    } catch(final RuntimeException e) {
      return Collections.emptyList();
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

  /**
   * Plugin core's storage worker blocks on its queue and ignores executor shutdown, which stalled
   * every server stop for 10 seconds. A final queued task stops it once earlier queued work ran.
   */
  private static void stopPluginCoreWorker() {

    final StorageManager storage = StorageManager.instance();
    if(storage == null) {
      return;
    }
    storage.getEngine().datables().put(WorkerStop.class, new WorkerStopDatable());
    storage.store(new WorkerStop(Thread.currentThread()), "");
  }

  private record WorkerStop(Thread caller) { }

  private static final class WorkerStopDatable implements Datable<WorkerStop> {

    @Override
    public Class<? extends WorkerStop> clazz() {

      return WorkerStop.class;
    }

    @Override
    public void purge(final StorageConnector<?> connector) {
    }

    @Override
    public void store(final StorageConnector<?> connector, final WorkerStop stop, final String identifier) {

      if(Thread.currentThread() != stop.caller()) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void storeAll(final StorageConnector<?> connector, final String identifier) {
    }

    @Override
    public void delete(final StorageConnector<?> connector, final String identifier) {
    }

    @Override
    public Optional<WorkerStop> load(final StorageConnector<?> connector, final String identifier) {

      return Optional.empty();
    }

    @Override
    public Collection<WorkerStop> loadAll(final StorageConnector<?> connector, final String identifier) {

      return Collections.emptyList();
    }
  }
}
