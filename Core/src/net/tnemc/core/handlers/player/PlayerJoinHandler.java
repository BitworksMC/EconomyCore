package net.tnemc.core.handlers.player;

/*
 * The New Economy
 * Copyright (C) 2022 - 2024 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.api.response.AccountAPIResponse;
import net.tnemc.core.config.DataConfig;
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.currency.item.ItemCurrency;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.history.AwayHistory;
import net.tnemc.core.utils.MISCUtils;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;
import net.tnemc.plugincore.core.id.UUIDPair;
import net.tnemc.plugincore.core.io.message.MessageData;
import net.tnemc.plugincore.core.utils.HandlerResponse;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents an event where a player is joining.
 *
 * @author creatorfromhell
 * @since 0.1.2
 */
public class PlayerJoinHandler {

  /**
   * Used to handle a PlayerJoinEvent using the specified {@link PlayerProvider} class.
   *
   * @param provider The {@link PlayerProvider} associated with the platform event.
   *
   * @return True if the event should be cancelled, otherwise false.
   */
  public HandlerResponse handle(final PlayerProvider provider, final String serverIP, final int serverPort) {

    final HandlerResponse response = new HandlerResponse("", false);
    PluginCore.log().debug("Player Join ID: " + provider.identifier());

    if(provider.identifier().toString().equalsIgnoreCase("657912a8-aa0e-3f17-aff5-a41f440e710c")) {
      return response;
    }

    validatePlayerName(provider);
    final Optional<Account> account = TNECore.eco().account().findAccount(provider.identifier());
    PluginCore.log().debug("Join Account Check: " + account.isPresent());
    final JoinAccount joinAccount = resolveAccount(provider, account);
    if(joinAccount == null) {
      response.setResponse(response.getResponse());
      response.setCancelled(true);
      return response;
    }

    PluginCore.log().debug("First Join: " + joinAccount.firstJoin());
    if(joinAccount.account().isPresent()) {
      initializeAccount(provider, joinAccount.account().get(), joinAccount.firstJoin());
    }
    return response;
  }

  private void validatePlayerName(final PlayerProvider provider) {

    PluginCore.log().debug("Validating Player Name: " + provider.getName() + ".");
    final Optional<UUIDPair> idPair = TNECore.eco().account().uuidProvider().retrieve(provider.getName());
    if(idPair.isEmpty()
       || provider.identifier().toString().equalsIgnoreCase(idPair.get().getIdentifier().toString())) {
      return;
    }

    final Optional<Account> oldAccount = TNECore.eco().account().findAccount(provider.identifier());
    if(oldAccount.isPresent()) {
      PluginCore.log().debug("Renaming player as someone took their old username already!");
      final String newName = oldAccount.get().getName() + "_old";
      oldAccount.get().setName(newName);
      TNECore.eco().account().uuidProvider().store(new UUIDPair(provider.identifier(), newName));
    }
  }

  private JoinAccount resolveAccount(final PlayerProvider provider, final Optional<Account> account) {

    if(account.isPresent()) {
      return new JoinAccount(account, false);
    }

    final AccountAPIResponse apiResponse = TNECore.eco().account().createAccount(
            provider.identifier().toString(), provider.getName());
    PluginCore.log().debug("API Join Check. Account Exists: " + apiResponse.getAccount().isPresent());
    if(apiResponse.getAccount().isEmpty()) {
      return null;
    }
    return new JoinAccount(apiResponse.getAccount(), apiResponse.getResponse().success());
  }

  private void initializeAccount(final PlayerProvider provider, final Account account, final boolean firstJoin) {

    updatePlayerName(provider, account);
    final UUID identifier = provider.identifier();
    if(firstJoin || account.getCreationDate() == ((PlayerAccount)account).getLastOnline()) {
      initializeBalances(provider, account, identifier, firstJoin);
    } else {
      loadBalances(provider, account, identifier);
    }
    TNECore.eco().account().getLoading().remove(identifier);
    scheduleAwayNotification(provider, account);
    notifyUpdate(provider);
    reloadSharedDataIfNeeded();
  }

  private void updatePlayerName(final PlayerProvider provider, final Account account) {

    if(!account.getName().equalsIgnoreCase(provider.getName())) {
      account.setName(provider.getName());
      TNECore.eco().account().uuidProvider().store(new UUIDPair(provider.identifier(), provider.getName()));
    }
  }

  private void initializeBalances(final PlayerProvider provider, final Account account, final UUID identifier,
                                  final boolean firstJoin) {

    final String region = TNECore.eco().region().getMode().region(provider);
    for(final Currency currency : TNECore.eco().currency().currencies()) {
      initializeCurrency(account, identifier, region, currency, firstJoin);
    }
  }

  private void initializeCurrency(final Account account, final UUID identifier, final String region,
                                  final Currency currency, final boolean firstJoin) {

    if(currency.type().supportsItems() && MainConfig.yaml().getBoolean("Core.Server.ImportItems", true)) {
      importHoldings(account, identifier, region, currency);
      return;
    }

    PluginCore.log().debug("Setting Balance to Starting Holdings Currency: " + currency.getIdentifier());
    if(firstJoin) {
      account.setHoldings(new HoldingsEntry(region, currency.getUid(), currency.getStartingHoldings(),
                                            EconomyManager.NORMAL));
    }
  }

  private void loadBalances(final PlayerProvider provider, final Account account, final UUID identifier) {

    TNECore.eco().account().getLoading().add(identifier);
    final String region = TNECore.eco().region().getMode().region(provider);
    for(final Currency currency : TNECore.eco().currency().getCurrencies(region)) {
      loadCurrency(account, identifier, region, currency);
    }
  }

  private void loadCurrency(final Account account, final UUID identifier, final String region,
                            final Currency currency) {

    if(!(currency instanceof final ItemCurrency itemCurrency)) {
      return;
    }
    if(account.getWallet().contains(region, currency.getUid())) {
      restoreHoldings(account, region, currency);
      return;
    }
    if(itemCurrency.isImportItem()) {
      importHoldings(account, identifier, region, currency);
    } else {
      account.setHoldings(new HoldingsEntry(region, currency.getUid(), currency.getStartingHoldings(),
                                            EconomyManager.NORMAL));
    }
  }

  private void importHoldings(final Account account, final UUID identifier, final String region,
                              final Currency currency) {

    TNECore.eco().account().getImporting().add(identifier);
    restoreHoldings(account, region, currency);
    TNECore.eco().account().getImporting().remove(identifier);
  }

  private void restoreHoldings(final Account account, final String region, final Currency currency) {

    for(final HoldingsEntry entry : account.getHoldings(region, currency.getUid())) {
      account.setHoldings(entry, entry.getHandler());
    }
  }

  private void scheduleAwayNotification(final PlayerProvider provider, final Account account) {

    PluginCore.server().scheduler().createDelayedTask(()->{
      final Optional<AwayHistory> away = account.away(((PlayerAccount)account).getUUID());
      if(away.isPresent()) {
        provider.message(new MessageData("Messages.Transaction.AwayJoin"));
      }
    }, new ChoreTime(0), ChoreExecution.SECONDARY);
  }

  private void notifyUpdate(final PlayerProvider provider) {

    if(!provider.hasPermission("tne.admin.update") || !MainConfig.yaml().getBoolean("Core.Update.Notify")
       || TNECore.instance().update() == null) {
      return;
    }
    if(TNECore.instance().update().needsUpdate()) {
      provider.message(new MessageData("<red>[TNE] Update Available! Latest: <white>"
                                       + TNECore.instance().update().getBuild()));
    }
    if(TNECore.instance().update().isEarlyBuild()) {
      provider.message(new MessageData("<gold>[TNE] Thank You for testing this pre-release version!"));
    }
  }

  private void reloadSharedDataIfNeeded() {

    if(PluginCore.server().onlinePlayers() != 1
       || !DataConfig.yaml().getBoolean("Data.Sync.Reload.Enabled", false)) {
      return;
    }
    if(!MISCUtils.isTimeDifferenceGreaterOrEqual(new Date(TNECore.eco().getReloadTime()),
                                                 DataConfig.yaml().getInt("Data.Sync.Reload.Time", 120))) {
      return;
    }

    TNECore.eco().account().getAccounts().clear();
    TransactionManager.receipts().getReceipts().clear();
    TNECore.instance().storage().loadAll(Account.class, "");
    TNECore.instance().storage().loadAll(Receipt.class, "");
    TNECore.eco().setReloadTime(new Date().getTime());
  }

  private record JoinAccount(Optional<Account> account, boolean firstJoin) { }
}
