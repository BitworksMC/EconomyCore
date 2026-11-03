import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.actions.source.PluginSource;
import net.tnemc.core.command.AdminCommand;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.Transaction;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Storage regression probe. Stops the server it runs on; never install it on a live server.
 *
 * <p>Run 1: 16 threads deposit concurrently (lost updates), then {@code reloaddb} with unsaved
 * changes, then one last unsaved deposit and a payment right before shutdown. Run 2 (next start)
 * checks that both survived the restart and that the payment receipt was reloaded with both
 * participants. Each run logs {@code SAVE_PROBE_RESULT ... failures=N}.</p>
 */
public final class SaveProbe extends JavaPlugin {

  private static final String ID = "5a7e0000-0000-4000-8000-00000000beef";
  private static final String OTHER = "5a7e0000-0000-4000-8000-00000000cafe";
  private static final int THREADS = 16;
  private static final int DEPOSITS = 500;
  private static final BigDecimal CONCURRENT = BigDecimal.valueOf(THREADS * DEPOSITS);
  private static final BigDecimal FINAL = CONCURRENT.add(BigDecimal.valueOf(5 - 2));

  private final List<String> failures = new ArrayList<>();

  @Override
  public void onEnable() {

    getServer().getScheduler().runTaskLater(this, this::run, 40L);
  }

  private void run() {

    final File marker = new File(getDataFolder(), "restart-check");
    try {
      if(marker.exists()) {
        check("restart", FINAL, balance());
        checkPayReceipt();
        marker.delete();
      } else {
        firstRun(marker);
      }
    } catch(final Exception e) {
      failures.add("exception: " + e);
      getLogger().severe("Probe failed: " + e);
    }
    getLogger().info("SAVE_PROBE_RESULT phase=" + (marker.exists()? "1" : "2") + " failures=" + failures.size()
                     + " " + failures);
    getServer().shutdown();
  }

  private void firstRun(final File marker) throws Exception {

    final Account account = TNECore.eco().account().findAccount(ID)
            .orElseGet(()->TNECore.eco().account().createAccount(ID, "SaveProbe").getAccount().orElseThrow());
    account.setHoldings(new HoldingsEntry(region(), currency(), BigDecimal.ZERO, EconomyManager.NORMAL));

    final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    for(int t = 0; t < THREADS; t++) {
      pool.execute(()->{
        for(int i = 0; i < DEPOSITS; i++) {
          give(1);
        }
      });
    }
    pool.shutdown();
    pool.awaitTermination(2, TimeUnit.MINUTES);
    check("concurrent", CONCURRENT, balance());

    AdminCommand.onReloadDB(null);
    check("reloaddb", CONCURRENT, balance());

    give(5);
    pay(2);
    getDataFolder().mkdirs();
    marker.createNewFile();
  }

  private void give(final int amount) {

    try {
      new Transaction("give")
              .to(TNECore.eco().account().findAccount(ID).orElseThrow(),
                  new HoldingsModifier(region(), currency(), BigDecimal.valueOf(amount)))
              .processor(EconomyManager.baseProcessor())
              .source(new PluginSource("SaveProbe"))
              .process();
    } catch(final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void pay(final int amount) {

    final Account other = TNECore.eco().account().findAccount(OTHER)
            .orElseGet(()->TNECore.eco().account().createAccount(OTHER, "SaveProbeOther").getAccount().orElseThrow());
    final HoldingsModifier modifier = new HoldingsModifier(region(), currency(), BigDecimal.valueOf(amount));
    try {
      new Transaction("pay")
              .to(other, modifier)
              .from(TNECore.eco().account().findAccount(ID).orElseThrow(), modifier.counter())
              .processor(EconomyManager.baseProcessor())
              .source(new PluginSource("SaveProbe"))
              .process();
    } catch(final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void checkPayReceipt() {

    final boolean complete = TNECore.eco().account().findAccount(OTHER).isPresent()
                             && TransactionManager.receipts().getReceiptsByParticipant(UUID.fromString(OTHER)).stream()
                                     .anyMatch(receipt->receipt.getType().equals("pay")
                                                        && receipt.getFrom() != null && receipt.getTo() != null);
    getLogger().info("pay receipt reloaded with both participants: " + complete);
    if(!complete) {
      failures.add("pay receipt missing a participant after restart");
    }
  }

  private BigDecimal balance() {

    return TNECore.eco().account().findAccount(ID).orElseThrow().getHoldingsTotal(region(), currency());
  }

  private void check(final String name, final BigDecimal expected, final BigDecimal actual) {

    getLogger().info(name + ": expected=" + expected.toPlainString() + " actual=" + actual.toPlainString());
    if(expected.compareTo(actual) != 0) {
      failures.add(name + " expected " + expected.toPlainString() + " got " + actual.toPlainString());
    }
  }

  private static String region() {

    return TNECore.eco().region().defaultRegion();
  }

  private static UUID currency() {

    return TNECore.eco().currency().defaultCurrency(region()).getUid();
  }
}
