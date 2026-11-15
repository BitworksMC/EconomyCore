import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.actions.source.PluginSource;
import net.tnemc.core.currency.item.ItemCurrency;
import net.tnemc.core.currency.item.ItemDenomination;
import net.tnemc.core.transaction.Transaction;
import net.tnemc.core.transaction.TransactionResult;
import net.tnemc.core.utils.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable real-player inventory regression. Stops the server with the player still online. */
public final class OnlineItemProbe extends JavaPlugin implements Listener {

  private static final String PLAYER = "OnlineItemBot";
  private static final UUID PLAYER_ID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + PLAYER).getBytes(StandardCharsets.UTF_8));
  private static final UUID CURRENCY_ID = UUID.fromString("17e10000-0000-4000-8000-00000000beef");
  private final List<String> failures = new ArrayList<>();
  private final AtomicBoolean finished = new AtomicBoolean();
  private int phase;
  private File marker;

  @Override
  public void onEnable() {

    marker = new File(getDataFolder(), "restart-check");
    phase = marker.exists()? 2 : 1;
    final ItemCurrency currency = new ItemCurrency("online-item-probe");
    currency.setUid(CURRENCY_ID);
    currency.setType("item");
    currency.setEnderChest(true);
    currency.setSync(false);
    currency.setDecimalPlaces(0);
    currency.getDenominations().put(BigDecimal.ONE, new ItemDenomination(BigDecimal.ONE, "GOLD_INGOT"));
    TNECore.eco().currency().addCurrency(currency);
    Bukkit.getPluginManager().registerEvents(this, this);
    if(phase == 2) {
      check("wallet after restart before player joins", BigDecimal.valueOf(30), wallet());
    }
    getLogger().info("ONLINE_ITEM_READY phase=" + phase + " player=" + PLAYER + " id=" + PLAYER_ID);
    ProbeScheduler.later(this, ()->{
      if(!finished.get()) {
        failures.add("Timed out waiting for the offline test client");
        finish();
      }
    }, 1200L);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(final PlayerJoinEvent event) {

    if(event.getPlayer().getName().equals(PLAYER)) {
      playerTask(event.getPlayer(), ()->run(event.getPlayer()), 40L);
    }
  }

  private void run(final Player player) {

    try {
      if(phase == 2) {
        check("restored physical inventory", BigDecimal.valueOf(30), physical(player));
        check("restored inventory portion", BigDecimal.valueOf(23), count(player.getInventory().getStorageContents()));
        check("restored ender portion", BigDecimal.valueOf(7), count(player.getEnderChest().getStorageContents()));
        check("restored live balance", BigDecimal.valueOf(30), account().getHoldingsTotal(region(), CURRENCY_ID));
        check("restored wallet", BigDecimal.valueOf(30), wallet());
        if(!marker.delete()) {
          throw new IllegalStateException("Cannot remove restart marker");
        }
        finish();
        return;
      }

      setPhysical(player, 17, 5);
      check("initial physical balance", BigDecimal.valueOf(22), account().getHoldingsTotal(region(), CURRENCY_ID));
      require("owner item transaction", give(1).isSuccessful());
      check("owner transaction physical result", BigDecimal.valueOf(23), physical(player));
      setPhysical(player, 17, 5);
      TNECore.instance().saver().saveAll();
      TNECore.instance().saver().run(()->playerTask(player, ()->checkAsyncTransactions(player), 1L));
    } catch(final Exception e) {
      failures.add("exception: " + e);
      getLogger().severe("Online item probe failed: " + e);
      finish();
    }
  }

  private void checkAsyncTransactions(final Player player) {

    check("initial inventory wallet snapshot", BigDecimal.valueOf(17), wallet(EconomyManager.INVENTORY_ONLY));
    check("initial ender wallet snapshot", BigDecimal.valueOf(5), wallet(EconomyManager.E_CHEST));
    final UUID digitalCurrency = TNECore.eco().currency().defaultCurrency(region()).getUid();
    final Account digital = TNECore.eco().account().createAccount("17e10000-0000-4000-8000-00000000cafe", "OnlineItemDigital", true)
            .getAccount().orElseThrow();
    digital.setHoldings(new HoldingsEntry(region(), digitalCurrency, BigDecimal.valueOf(100), EconomyManager.VIRTUAL));
    CompletableFuture.runAsync(()->{
      try {
        require("async item transaction rejected", !give(1).isSuccessful());
        final TransactionResult mixed = new Transaction("pay")
                .from(digital, new HoldingsModifier(region(), digitalCurrency, BigDecimal.ONE.negate(), EconomyManager.VIRTUAL))
                .to(account(), new HoldingsModifier(region(), CURRENCY_ID, BigDecimal.ONE))
                .processor(EconomyManager.baseProcessor()).source(new PluginSource("OnlineItemProbe")).process();
        require("async mixed transaction rejected", !mixed.isSuccessful());
        check("rejected mixed transaction leaves digital balance", BigDecimal.valueOf(100), digital.getHoldingsTotal(region(), digitalCurrency));
        check("rejected transactions leave item wallet", BigDecimal.valueOf(22), wallet());
      } catch(final Exception e) {
        throw new IllegalStateException(e);
      }
    }).thenCompose(ignored->TNECore.instance().runPlayerTask(PLAYER_ID, ()->{
      try {
        check("rejected transactions leave physical items", BigDecimal.valueOf(22), physical(player));
        require("async dispatch to owner transaction", give(1).isSuccessful());
        check("dispatched owner physical result", BigDecimal.valueOf(23), physical(player));
        setPhysical(player, 17, 5);
        TNECore.instance().saver().saveAll();
        TNECore.instance().saver().run(()->playerTask(player, ()->afterSave(player), 1L));
      } catch(final Exception e) {
        throw new IllegalStateException(e);
      }
    })).exceptionally(failure->{
      failures.add("async checks: " + failure);
      finish();
      return null;
    });
  }

  private TransactionResult give(final int amount) throws Exception {

    return new Transaction("give").to(account(), new HoldingsModifier(region(), CURRENCY_ID, BigDecimal.valueOf(amount)))
            .processor(EconomyManager.baseProcessor()).source(new PluginSource("OnlineItemProbe")).process();
  }

  private void afterSave(final Player player) {

    try {
      check("wallet copied by saveAll", BigDecimal.valueOf(22), wallet());
      setPhysical(player, 23, 7);
      check("wallet before shutdown remains snapshot", BigDecimal.valueOf(22), wallet());
      check("physical inventory at shutdown", BigDecimal.valueOf(30), physical(player));
      getDataFolder().mkdirs();
      if(!marker.createNewFile()) {
        throw new IllegalStateException("Cannot create restart marker");
      }
      getLogger().info("ONLINE_ITEM_SHUTDOWN playerOnline=" + player.isOnline() + " physical=" + physical(player) + " wallet=" + wallet());
    } catch(final Exception e) {
      failures.add("exception: " + e);
    }
    finish();
  }

  private void require(final String name, final boolean successful) {

    getLogger().info(name + ": " + successful);
    if(!successful) {
      failures.add(name);
    }
  }

  @Override
  public void onDisable() {

    final Player player = Bukkit.getPlayer(PLAYER_ID);
    if(player != null) {
      getLogger().info("ONLINE_ITEM_DISABLE playerOnline=" + player.isOnline() + " wallet=" + wallet());
    }
  }

  private void finish() {

    if(finished.compareAndSet(false, true)) {
      getLogger().info("ONLINE_ITEM_PROBE_RESULT phase=" + phase + " failures=" + failures.size() + " " + failures);
      Bukkit.shutdown();
    }
  }

  private void playerTask(final Player player, final Runnable task, final long delay) {

    if(ProbeScheduler.folia()) {
      player.getScheduler().runDelayed(this, ignored->task.run(), ()->{
        failures.add("Test player retired before check");
        finish();
      }, delay);
    } else {
      Bukkit.getScheduler().runTaskLater(this, task, delay);
    }
  }

  private void check(final String name, final BigDecimal expected, final BigDecimal actual) {

    getLogger().info(name + ": expected=" + expected + " actual=" + actual);
    if(expected.compareTo(actual) != 0) {
      failures.add(name + " expected " + expected + " got " + actual);
    }
  }

  private Account account() {

    return TNECore.eco().account().findAccount(PLAYER_ID).orElseThrow();
  }

  private BigDecimal wallet() {

    return wallet(EconomyManager.INVENTORY_ONLY).add(wallet(EconomyManager.E_CHEST));
  }

  private BigDecimal wallet(final Identifier handler) {

    return TNECore.eco().account().findAccount(PLAYER_ID)
            .flatMap(account->account.getWallet().getHoldings(region(), CURRENCY_ID, handler))
            .map(HoldingsEntry::getAmount).orElse(BigDecimal.ZERO);
  }

  private BigDecimal physical(final Player player) {

    return count(player.getInventory().getStorageContents()).add(count(player.getEnderChest().getStorageContents()));
  }

  private BigDecimal count(final ItemStack[] contents) {

    int amount = 0;
    for(final ItemStack stack : contents) {
      if(stack != null && stack.getType() == Material.GOLD_INGOT) {
        amount += stack.getAmount();
      }
    }
    return BigDecimal.valueOf(amount);
  }

  private void setPhysical(final Player player, final int inventory, final int ender) {

    player.getInventory().clear();
    player.getEnderChest().clear();
    player.getInventory().setItem(0, new ItemStack(Material.GOLD_INGOT, inventory));
    player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_INGOT, ender));
  }

  private static String region() {

    return TNECore.eco().region().defaultRegion();
  }
}
