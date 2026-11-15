import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.command.AdminCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs only on an isolated test server and stops it. The first run queues create/delete/recreate
 * while the storage writer is blocked; the second verifies the resulting accounts after restart.
 */
public final class LifecycleProbe extends JavaPlugin {

  private static final UUID DELETED = UUID.fromString("dea70000-0000-4000-8000-00000000beef");
  private static final UUID RECREATED = UUID.fromString("dea70000-0000-4000-8000-00000000cafe");
  private static final String NAME = "LifecycleRenamed";
  private static final BigDecimal BALANCE = BigDecimal.valueOf(37);
  private final List<String> failures = new ArrayList<>();

  @Override
  public void onEnable() {

    ProbeScheduler.run(this, this::run);
  }

  private void run() {

    final File marker = new File(getDataFolder(), "restart-check");
    final int phase = marker.exists()? 2 : 1;
    try {
      if(phase == 1) {
        queueLifecycleChanges();
        AdminCommand.onReloadDB(null);
        verifyAccounts("reload");
        getDataFolder().mkdirs();
        if(!marker.createNewFile()) {
          throw new IllegalStateException("Cannot create restart marker");
        }
      } else {
        verifyAccounts("restart");
        if(!marker.delete()) {
          throw new IllegalStateException("Cannot remove restart marker");
        }
      }
    } catch(final Exception e) {
      failures.add("exception: " + e);
      getLogger().severe("Lifecycle probe failed: " + e);
    }
    getLogger().info("LIFECYCLE_PROBE_RESULT phase=" + phase + " failures=" + failures.size() + " " + failures);
    getServer().shutdown();
  }

  private void queueLifecycleChanges() throws Exception {

    TNECore.eco().account().deleteAccount(DELETED);
    TNECore.eco().account().deleteAccount(RECREATED);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    TNECore.instance().saver().run(()->{
      entered.countDown();
      try {
        release.await(15, TimeUnit.SECONDS);
      } catch(final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      if(!entered.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Storage blocker did not start");
      }
      final Account deleted = create(DELETED, "LifecycleDeleted");
      TNECore.instance().saver().save(deleted);
      TNECore.eco().account().deleteAccount(DELETED);

      final Account original = create(RECREATED, "LifecycleOriginal");
      TNECore.eco().account().deleteAccount(RECREATED);
      final Account replacement = create(RECREATED, "LifecycleReplacement");
      replacement.setName(NAME);
      replacement.setHoldings(new HoldingsEntry(region(), currency(), BALANCE, EconomyManager.NORMAL));
      TNECore.instance().saver().save(replacement);
      //A stale logout callback arriving after recreation must not overwrite the replacement.
      TNECore.instance().saver().save(original);
      TNECore.instance().saver().save(deleted);
    } finally {
      release.countDown();
    }
    final CountDownLatch stored = new CountDownLatch(1);
    TNECore.instance().saver().run(stored::countDown);
    if(!stored.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Lifecycle writes did not finish");
    }
  }

  private Account create(final UUID id, final String name) {

    return TNECore.eco().account().createAccount(id.toString(), name).getAccount().orElseThrow();
  }

  private void verifyAccounts(final String phase) {

    if(TNECore.eco().account().findAccount(DELETED).isPresent()) {
      failures.add(phase + ": deleted account was resurrected");
    }
    final Account replacement = TNECore.eco().account().findAccount(RECREATED).orElseThrow();
    if(!replacement.getName().equals(NAME)) {
      failures.add(phase + ": stale name " + replacement.getName());
    }
    final BigDecimal actual = replacement.getHoldingsTotal(region(), currency());
    if(actual.compareTo(BALANCE) != 0) {
      failures.add(phase + ": balance expected " + BALANCE + " got " + actual);
    }
    getLogger().info(phase + ": deletedPresent=" + TNECore.eco().account().findAccount(DELETED).isPresent()
                    + " replacement=" + replacement.getName() + " balance=" + actual);
  }

  private static String region() {

    return TNECore.eco().region().defaultRegion();
  }

  private static UUID currency() {

    return TNECore.eco().currency().defaultCurrency(region()).getUid();
  }
}
