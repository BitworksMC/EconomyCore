import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.actions.source.PluginSource;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.Transaction;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;

/** Console-controlled virtual balance integration probe, for isolated servers only. */
public final class PluginSyncProbe extends JavaPlugin {
  private static final String ACCOUNT = "ecc00000-0000-4000-8000-00000000beef";

  @Override
  public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
    try {
      switch(args[0]) {
        case "init" -> {
          final Account account = TNECore.eco().account().createAccount(ACCOUNT, "RedisPluginProbe", true).getAccount().orElseThrow();
          account.setHoldings(new HoldingsEntry(region(), currency(), BigDecimal.ZERO, EconomyManager.NORMAL));
          report("INIT");
        }
        case "report" -> report("REPORT");
        case "deposit" -> {
          final int count = Integer.parseInt(args[1]);
          final Thread worker = new Thread(()->deposit(count), "PluginSyncProbe-Deposits");
          worker.setDaemon(true);
          worker.start();
        }
        default -> throw new IllegalArgumentException("Unknown probe action");
      }
    } catch(final Exception failure) {
      getLogger().severe("SYNC_PLUGIN_FAILURE " + failure);
      failure.printStackTrace();
    }
    return true;
  }

  private void deposit(final int count) {
    try {
      for(int i = 0; i < count; i++) {
        final var result = new Transaction("give")
                .to(account(), new HoldingsModifier(region(), currency(), BigDecimal.ONE))
                .processor(EconomyManager.baseProcessor())
                .source(new PluginSource("PluginSyncProbe"))
                .process();
        if(!result.isSuccessful()) throw new IllegalStateException("Deposit rejected: " + i);
      }
      report("DEPOSITS_DONE count=" + count);
    } catch(final Exception failure) {
      getLogger().severe("SYNC_PLUGIN_FAILURE " + failure);
      failure.printStackTrace();
    }
  }

  private void report(final String phase) throws Exception {
    final var outbox = getDataFolder().toPath().resolveSibling("TheNewEconomy").resolve("redis-outbox");
    long pending;
    try(final var files = Files.list(outbox)) {
      pending = files.filter(path->path.toString().endsWith(".pending")).count();
    }
    getLogger().info("SYNC_PLUGIN_" + phase + " balance=" + account().getHoldingsTotal(region(), currency())
                     + " pending=" + pending + " currency=" + currency()
                     + " receipts=" + TransactionManager.receipts().getReceiptsByParticipant(UUID.fromString(ACCOUNT)).size());
  }

  private static Account account() { return TNECore.eco().account().findAccount(ACCOUNT).orElseThrow(); }
  private static String region() { return TNECore.eco().region().defaultRegion(); }
  private static UUID currency() { return TNECore.eco().currency().defaultCurrency(region()).getUid(); }
}
