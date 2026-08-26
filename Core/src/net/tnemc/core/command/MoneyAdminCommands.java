package net.tnemc.core.command;

/*
 * The New Economy
 * Copyright (C) 2022 - 2025 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.actions.source.PlayerSource;
import net.tnemc.core.channel.MessageHandler;
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.currency.format.CurrencyFormatter;
import net.tnemc.core.currency.parser.ParseMoney;
import net.tnemc.core.manager.TopManager;
import net.tnemc.core.manager.top.TopPage;
import net.tnemc.core.transaction.Transaction;
import net.tnemc.core.utils.MISCUtils;
import net.tnemc.plugincore.core.compatibility.CmdSource;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.tnemc.core.EconomyManager.TOP_PER_PAGE;

final class MoneyAdminCommands {

  private MoneyAdminCommands() {
  }

  static void giveAll(final CmdSource<?> sender, final ParseMoney parseMoney,
                      final Currency currencyParam, final String regionParam) {

    parseMoney.normalizeParameters(currencyParam, regionParam);
    final String region = TNECore.eco().region().resolve(parseMoney.region());
    final Currency currency = parseMoney.currency();
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.give." + currency.getIdentifier(),
                                          "give funds", currency)) {
      return;
    }
    final HoldingsModifier modifier = new HoldingsModifier(region, currency.getUid(), parseMoney.amount());
    final List<String> accounts = new ArrayList<>();
    final UUID sourceID = MoneyCommandSupport.sourceID(sender);
    for(final Account account : TNECore.eco().account().getAccounts().values()) {
      giveToAccount(sender, parseMoney, currency, modifier, accounts, sourceID, account);
    }
    final MessageData data = new MessageData("Messages.Money.Gave");
    data.addReplacement("$player", String.join(", ", accounts));
    data.addReplacement("$currency", currency.getIdentifier());
    data.addReplacement("$amount", CurrencyFormatter.format(null, modifier.asEntry()));
    sender.message(data);
  }

  private static void giveToAccount(final CmdSource<?> sender, final ParseMoney parseMoney,
                                    final Currency currency, final HoldingsModifier modifier,
                                    final List<String> accounts, final UUID sourceID,
                                    final Account account) {

    final Transaction transaction = new Transaction("give")
            .to(account, modifier)
            .source(new PlayerSource(sourceID));
    if(MoneyCommand.processTransaction(sender, transaction, account.getName(), parseMoney.amount()).isEmpty()) {
      return;
    }
    accounts.add(account.getName());
    final MessageData message = new MessageData("Messages.Money.Given");
    message.addReplacement("$currency", currency.getIdentifier());
    message.addReplacement("$player", sender.name() == null
                                      ? MainConfig.yaml().getString("Core.Server.Account.Name") : sender.name());
    message.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
    MessageHandler.send(account.getIdentifier(), message.grab(account.getIdentifier()));
    if(account.isPlayer() && ((PlayerAccount)account).isOnline()) {
      ((PlayerAccount)account).getPlayer().ifPresent(player->player.message(message));
    }
  }

  static void other(final CmdSource<?> sender, final Account account,
                    final Currency currencyParam, final String regionParam) {

    final Optional<PlayerProvider> player = sender.player();
    final boolean other = sender.identifier().isPresent()
                          && !sender.identifier().get().equals(account.getIdentifier());
    final Currency currency = currencyParam == null
                              ? TNECore.eco().currency().defaultCurrency() : currencyParam;
    if(other && MoneyCommandSupport.currencyDenied(sender,
                                                   "tne.money.other." + currency.getIdentifier(),
                                                   "balance check other", currency)) {
      return;
    }
    final String region = TNECore.eco().region().resolve(
            MoneyCommandSupport.playerRegion(player, regionParam));
    if(TNECore.eco().region().getDisabledRegions().contains(region)) {
      sender.message(new MessageData("Messages.General.Disabled"));
      return;
    }
    final String header = other ? "Messages.Money.HoldingsMultiOther" : "Messages.Money.HoldingsMulti";
    final MessageData message = new MessageData(header);
    message.addReplacement("$world", MISCUtils.worldFormatted(region));
    message.addReplacement("$player", account.getName());
    sender.message(message);
    TNECore.eco().currency().currencies().forEach(currencyEntry->{
      if(currencyEntry.isBalanceShow()) {
        MoneyCommand.printBalance(sender, account, currencyEntry, region);
      }
    });
  }

  static void take(final CmdSource<?> sender, final Account account,
                   final ParseMoney parseMoney, final Currency currencyParam,
                   final String regionParam) {

    parseMoney.normalizeParameters(currencyParam, regionParam);
    final Currency currency = parseMoney.currency();
    final Optional<PlayerProvider> player = sender.player();
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.take." + currency.getIdentifier(),
                                          "take funds", currency)) {
      return;
    }
    final String region = TNECore.eco().region().resolve(
            MoneyCommandSupport.playerRegion(player, parseMoney.region()));
    final HoldingsModifier modifier = new HoldingsModifier(region, currency.getUid(), parseMoney.amount());
    final Transaction transaction = new Transaction("take")
            .to(account, modifier.counter())
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(MoneyCommandSupport.sourceID(sender)));
    if(MoneyCommand.processTransaction(sender, transaction, account.getName(), parseMoney.amount()).isEmpty()) {
      return;
    }
    sendTakenMessages(sender, account, currency, modifier);
  }

  private static void sendTakenMessages(final CmdSource<?> sender, final Account account,
                                        final Currency currency, final HoldingsModifier modifier) {

    final MessageData data = new MessageData("Messages.Money.Took");
    data.addReplacement("$player", account.getName());
    data.addReplacement("$currency", currency.getIdentifier());
    data.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
    sender.message(data);
    final MessageData taken = new MessageData("Messages.Money.Taken");
    taken.addReplacement("$player", sender.name() == null
                                   ? MainConfig.yaml().getString("Core.Server.Account.Name") : sender.name());
    taken.addReplacement("$currency", currency.getIdentifier());
    taken.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
    MessageHandler.send(account.getIdentifier(), taken.grab(account.getIdentifier()));
    if(account.isPlayer() && ((PlayerAccount)account).isOnline()) {
      ((PlayerAccount)account).getPlayer().ifPresent(player->player.message(taken));
    }
  }

  static void top(final CmdSource<?> sender, Integer page, final Currency currencyParam,
                  final Boolean refresh) {

    final Optional<PlayerProvider> player = sender.player();
    final String region = TNECore.eco().region().resolve(
            player.map(PlayerProvider::world).orElse(TNECore.DEFAULT_WORLD));
    final Currency currency = currencyParam == null
                              ? TNECore.eco().currency().defaultCurrency(region) : currencyParam;
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.top." + currency.getIdentifier(),
                                          "balance top", currency)) {
      return;
    }
    final Optional<Account> senderAccount = BaseCommand.account(sender, "top");
    if(player.isPresent() && TNECore.eco().region().getDisabledRegions().contains(player.get().world())) {
      sender.message(new MessageData("Messages.General.Disabled"));
      return;
    }
    if(refresh && (player.isEmpty() || player.get().hasPermission("tne.money.top.refresh"))) {
      TopManager.instance().load();
    }
    final int max = Math.max(1, TNECore.eco().getTopManager().page(currency.getUid()));
    page = Math.max(1, Math.min(page, max));
    final TopPage<String> pageEntry = TNECore.eco().getTopManager().page(page, currency.getUid());
    if(pageEntry != null) {
      sendTopPage(sender, senderAccount.orElse(null), page, max, currency, pageEntry);
    }
  }

  private static void sendTopPage(final CmdSource<?> sender, final Account senderAccount,
                                  final int page, final int max, final Currency currency,
                                  final TopPage<String> pageEntry) {

    final MessageData data = new MessageData("Messages.Money.Top");
    data.addReplacement("$page", String.valueOf(page));
    data.addReplacement("$page_top", String.valueOf(max));
    sender.message(data);
    final int adjusted = (page - 1) * TOP_PER_PAGE;
    int index = 1;
    for(final Map.Entry<String, BigDecimal> entry : pageEntry.getValues().entrySet()) {
      final MessageData topEntry = new MessageData("Messages.Money.TopEntry");
      topEntry.addReplacement("$pos", adjusted + index);
      topEntry.addReplacement("$player", entry.getKey());
      topEntry.addReplacement("$amount", CurrencyFormatter.format(senderAccount,
                              new HoldingsEntry(TNECore.eco().region().defaultRegion(),
                                                currency.getUid(), entry.getValue(), EconomyManager.NORMAL)));
      sender.message(topEntry);
      index++;
    }
  }
}
