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
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.actions.source.PlayerSource;
import net.tnemc.core.channel.MessageHandler;
import net.tnemc.core.command.parameters.PercentBigDecimal;
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.currency.Note;
import net.tnemc.core.currency.format.CurrencyFormatter;
import net.tnemc.core.currency.parser.ParseMoney;
import net.tnemc.core.currency.type.MixedType;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.Transaction;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.CmdSource;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

final class MoneyTransferCommands {

  private MoneyTransferCommands() {
  }

  static void convert(final CmdSource<?> sender, final PercentBigDecimal amount,
                      final Currency currency, final Currency fromCurrency) {

    final Currency resolvedFrom = fromCurrency == null
                                  ? TNECore.eco().currency().defaultCurrency(BaseCommand.region(sender))
                                  : fromCurrency;
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.convert.to." + currency.getIdentifier(),
                                          "convert to", currency)) {
      return;
    }
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.convert.from." + resolvedFrom.getIdentifier(),
                                          "convert from", resolvedFrom)) {
      return;
    }
    if(amount.value().compareTo(BigDecimal.ZERO) < 0) {
      sender.message(new MessageData("Messages.Money.Negative"));
      return;
    }
    if(currency.getUid().equals(resolvedFrom.getUid())) {
      sender.message(new MessageData("Messages.Money.ConvertSame"));
      return;
    }
    final Optional<Account> account = BaseCommand.account(sender, "convert");
    if(account.isEmpty()) {
      MoneyCommandSupport.noPlayer(sender);
      return;
    }
    final Optional<BigDecimal> converted = resolvedFrom.convertValue(currency.getIdentifier(), amount.value());
    if(converted.isEmpty()) {
      final MessageData data = new MessageData("Messages.Money.NoConversion");
      data.addReplacement("$converted", currency.getIdentifier());
      sender.message(data);
      return;
    }
    processConversion(sender, amount, currency, resolvedFrom, account.get(), converted.get());
  }

  private static void processConversion(final CmdSource<?> sender, final PercentBigDecimal amount,
                                        final Currency currency, final Currency resolvedFrom,
                                        final Account account, final BigDecimal converted) {

    final HoldingsModifier modifier = new HoldingsModifier(BaseCommand.region(sender), currency.getUid(),
                                                            converted.setScale(currency.getDecimalPlaces(), RoundingMode.DOWN));
    final HoldingsModifier modifierFrom = new HoldingsModifier(BaseCommand.region(sender), resolvedFrom.getUid(),
                                                                amount.value().setScale(currency.getDecimalPlaces(), RoundingMode.DOWN).negate());
    final Transaction transaction = new Transaction("convert")
            .from(account, modifierFrom)
            .to(account, modifier)
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(MoneyCommandSupport.sourceID(sender)));
    if(MoneyCommand.processTransaction(sender, transaction, account.getName(), amount.value()).isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Converted");
      data.addReplacement("$from_amount", amount.value().toPlainString());
      data.addReplacement("$amount", CurrencyFormatter.format(account, modifierFrom.asEntry()));
      sender.message(data);
    }
  }

  static void deposit(final CmdSource<?> sender, final ParseMoney parseMoney,
                      final Currency currencyParam, final String regionParam) {

    parseMoney.normalizeParameters(currencyParam, regionParam);
    final Currency currency = parseMoney.currency();
    final Optional<PlayerProvider> player = sender.player();
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.deposit." + currency.getIdentifier(),
                                          "deposit", currency)) {
      return;
    }
    final String requestedRegion = MoneyCommandSupport.playerRegion(player, parseMoney.region());
    if(parseMoney.amount().compareTo(BigDecimal.ZERO) < 0) {
      sender.message(new MessageData("Messages.Money.Negative"));
      return;
    }
    final Optional<Account> account = BaseCommand.account(sender, "deposit");
    if(account.isEmpty()) {
      MoneyCommandSupport.noPlayer(sender);
      return;
    }
    final String region = TNECore.eco().region().resolve(requestedRegion);
    if(!(currency.type() instanceof MixedType)) {
      sender.message(new MessageData("Messages.Money.NotMixed"));
      return;
    }
    processDeposit(sender, parseMoney, currency, region, account.get());
  }

  private static void processDeposit(final CmdSource<?> sender, final ParseMoney parseMoney,
                                     final Currency currency, final String region, final Account account) {

    final HoldingsModifier modifier = new HoldingsModifier(region, currency.getUid(),
                                                            parseMoney.amount(), EconomyManager.VIRTUAL);
    final Transaction transaction = new Transaction("deposit")
            .to(account, modifier)
            .from(account, modifier.counter(EconomyManager.ITEM_ONLY))
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(MoneyCommandSupport.sourceID(sender)));
    if(MoneyCommand.processTransaction(sender, transaction, account.getName(), parseMoney.amount()).isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Deposit");
      data.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
      sender.message(data);
    }
  }

  static void note(final CmdSource<?> sender, final ParseMoney parseMoney, final Currency currency) {

    parseMoney.normalizeParameters(currency, TNECore.DEFAULT_WORLD);
    final Optional<NoteContext> context = noteContext(sender, parseMoney);
    if(context.isEmpty()) {
      return;
    }
    final NoteContext resolved = context.get();
    if(notePermissionDenied(sender, resolved.provider(), parseMoney.currency())) {
      return;
    }
    if(parseMoney.amount().compareTo(resolved.note().getMinimum()) < 0) {
      final MessageData minimum = new MessageData("Messages.Note.Minimum");
      minimum.addReplacement("$amount", resolved.note().getMinimum().toPlainString());
      sender.message(minimum);
      return;
    }
    createNote(sender, parseMoney, resolved);
  }

  private static Optional<NoteContext> noteContext(final CmdSource<?> sender, final ParseMoney parseMoney) {

    final Optional<Account> account = BaseCommand.account(sender, "note");
    final Optional<Note> note = parseMoney.currency().getNote();
    if(account.isEmpty() || note.isEmpty() || !(account.get() instanceof final PlayerAccount player)) {
      return Optional.empty();
    }
    final Optional<PlayerProvider> provider = player.getPlayer();
    return provider.map(playerProvider->new NoteContext(player, playerProvider, note.get()));
  }

  private static boolean notePermissionDenied(final CmdSource<?> sender,
                                              final PlayerProvider provider,
                                              final Currency currency) {

    if(!EconomyManager.limitCurrency()
       || provider.hasPermission("tne.money.note." + currency.getIdentifier())) {
      return false;
    }
    final MessageData data = new MessageData("Messages.Account.BlockedAction");
    data.addReplacement("$action", "note");
    data.addReplacement("$currency", currency.getDisplay());
    sender.message(data);
    return true;
  }

  private static void createNote(final CmdSource<?> sender, final ParseMoney parseMoney,
                                 final NoteContext context) {

    final BigDecimal rounded = parseMoney.amount().setScale(parseMoney.currency().getDecimalPlaces(), RoundingMode.DOWN);
    final BigDecimal amount = rounded.add(context.note().getFee().calculateTax(rounded))
            .setScale(parseMoney.currency().getDecimalPlaces(), RoundingMode.DOWN);
    final HoldingsModifier modifier = new HoldingsModifier(BaseCommand.region(sender),
                                                            parseMoney.currency().getUid(), amount);
    final Transaction transaction = new Transaction("note")
            .from(context.player(), modifier.counter())
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(MoneyCommandSupport.sourceID(sender)));
    final Optional<Receipt> receipt = MoneyCommand.processTransaction(sender, transaction,
                                                                       context.player().getName(),
                                                                       parseMoney.amount());
    if(receipt.isEmpty()) {
      return;
    }
    final Collection<AbstractItemStack<Object>> left = PluginCore.server().calculations().giveItems(
            Collections.singletonList(context.note().stack(parseMoney.currency().getIdentifier(),
                                                            BaseCommand.region(sender), rounded)),
            context.provider().inventory().getInventory(false));
    final MessageData entry = new MessageData("Messages.Note.Given");
    entry.addReplacement("$currency", parseMoney.currency().getIdentifier());
    entry.addReplacement("$amount", CurrencyFormatter.format(context.player(), modifier.asEntry()));
    sender.message(entry);
    if(!left.isEmpty()) {
      PluginCore.server().calculations().drop(left, context.player().getUUID(), true);
      sender.message(new MessageData("Messages.Note.Dropped"));
    }
  }

  static void pay(final CmdSource<?> sender, final Account suppliedAccount,
                  final ParseMoney parseMoney, final Currency currency) {

    parseMoney.normalizeParameters(currency, TNECore.DEFAULT_WORLD);
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.pay." + parseMoney.currency().getIdentifier(),
                                          "pay", parseMoney.currency())) {
      return;
    }
    if(parseMoney.amount().compareTo(BigDecimal.ZERO) < 0) {
      sender.message(new MessageData("Messages.Money.Negative"));
      return;
    }
    final Optional<PayContext> context = payContext(sender, suppliedAccount);
    if(context.isEmpty() || distanceBlocked(sender, context.get())) {
      return;
    }
    processPayment(sender, parseMoney, context.get());
  }

  private static Optional<PayContext> payContext(final CmdSource<?> sender, final Account suppliedAccount) {

    final Optional<Account> senderAccount = BaseCommand.account(sender, "pay");
    final Account account = BaseCommand.account(suppliedAccount.getIdentifier(), "payreceive")
            .orElse(suppliedAccount);
    if(senderAccount.isEmpty()) {
      MoneyCommandSupport.noPlayer(sender);
      return Optional.empty();
    }
    if(senderAccount.get().getIdentifier().equals(account.getIdentifier())) {
      final MessageData data = new MessageData("Messages.Money.SelfPay");
      data.addReplacement("$player", sender.name());
      sender.message(data);
      return Optional.empty();
    }
    if(payeeOffline(account)) {
      sender.message(new MessageData("Messages.Money.PayFailedOnline"));
      return Optional.empty();
    }
    return Optional.of(new PayContext(senderAccount.get(), account));
  }

  private static boolean payeeOffline(final Account account) {

    if(MainConfig.yaml().getBoolean("Core.Commands.Pay.Offline", true)) {
      return false;
    }
    return !(account instanceof PlayerAccount) || !((PlayerAccount)account).isOnline();
  }

  private static boolean distanceBlocked(final CmdSource<?> sender, final PayContext context) {

    final int radius = MainConfig.yaml().getInt("Core.Commands.Pay.Radius", 0);
    if(radius <= 0) {
      return false;
    }
    final MessageData data = new MessageData("Messages.Money.PayFailedDistance");
    data.addReplacement("$distance", String.valueOf(radius));
    final Optional<PlayerAccount> sendingPlayer = onlinePlayer(context.sender());
    final Optional<PlayerAccount> receivingPlayer = onlinePlayer(context.receiver());
    if(sendingPlayer.isEmpty() || receivingPlayer.isEmpty()) {
      sender.message(data);
      return true;
    }
    final Optional<PlayerProvider> senderProvider = sendingPlayer.get().getPlayer();
    final Optional<PlayerProvider> receiverProvider = receivingPlayer.get().getPlayer();
    if(senderProvider.isEmpty() || receiverProvider.isEmpty()) {
      sender.message(data);
      return true;
    }
    if(senderProvider.get().getLocation().isEmpty() || receiverProvider.get().getLocation().isEmpty()) {
      sender.message(data);
      return true;
    }
    if(senderProvider.get().getLocation().get().distance(receiverProvider.get().getLocation().get()) > radius) {
      sender.message(data);
      return true;
    }
    return false;
  }

  private static Optional<PlayerAccount> onlinePlayer(final Account account) {

    if(account instanceof final PlayerAccount player && player.isOnline()) {
      return Optional.of(player);
    }
    return Optional.empty();
  }

  private static void processPayment(final CmdSource<?> sender, final ParseMoney parseMoney,
                                     final PayContext context) {

    final HoldingsModifier modifier = new HoldingsModifier(BaseCommand.region(sender),
                                                            parseMoney.currency().getUid(),
                                                            parseMoney.amount());
    final Transaction transaction = new Transaction("pay")
            .to(context.receiver(), modifier)
            .from(context.sender(), modifier.counter())
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(MoneyCommandSupport.sourceID(sender)));
    if(MoneyCommand.processTransaction(sender, transaction, context.receiver().getName(),
                                       parseMoney.amount()).isEmpty()) {
      return;
    }
    sendPaymentMessages(sender, parseMoney, context.receiver(), modifier);
  }

  private static void sendPaymentMessages(final CmdSource<?> sender, final ParseMoney parseMoney,
                                          final Account account, final HoldingsModifier modifier) {

    final MessageData data = new MessageData("Messages.Money.Paid");
    data.addReplacement("$player", account.getName());
    data.addReplacement("$currency", parseMoney.currency().getIdentifier());
    data.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
    sender.message(data);
    final MessageData received = new MessageData("Messages.Money.Received");
    received.addReplacement("$player", sender.name() == null
                                       ? MainConfig.yaml().getString("Core.Server.Account.Name") : sender.name());
    received.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
    MessageHandler.send(account.getIdentifier(), received.grab(account.getIdentifier()));
    if(account.isPlayer() && ((PlayerAccount)account).isOnline()) {
      PluginCore.server().findPlayer(((PlayerAccount)account).getUUID())
              .ifPresent(playerProvider->playerProvider.message(received));
    }
  }

  static void withdraw(final CmdSource<?> sender, final ParseMoney parseMoney,
                       final Currency currencyParam, final String regionParam) {

    parseMoney.normalizeParameters(currencyParam, regionParam);
    final Currency currency = parseMoney.currency();
    final Optional<PlayerProvider> player = sender.player();
    if(MoneyCommandSupport.currencyDenied(sender,
                                          "tne.money.withdraw." + currency.getIdentifier(),
                                          "withdraw funds", currency)) {
      return;
    }
    final String requestedRegion = MoneyCommandSupport.playerRegion(player, parseMoney.region());
    if(parseMoney.amount().compareTo(BigDecimal.ZERO) < 0) {
      sender.message(new MessageData("Messages.Money.Negative"));
      return;
    }
    final Optional<Account> account = BaseCommand.account(sender, "withdraw");
    if(account.isEmpty()) {
      MoneyCommandSupport.noPlayer(sender);
      return;
    }
    final String region = TNECore.eco().region().resolve(requestedRegion);
    if(!(currency.type() instanceof MixedType)) {
      sender.message(new MessageData("Messages.Money.NotMixed"));
      return;
    }
    processWithdrawal(sender, parseMoney, currency, region, account.get());
  }

  private static void processWithdrawal(final CmdSource<?> sender, final ParseMoney parseMoney,
                                        final Currency currency, final String region,
                                        final Account account) {

    final HoldingsModifier modifier = new HoldingsModifier(BaseCommand.region(sender), currency.getUid(),
                                                            parseMoney.amount(), EconomyManager.ITEM_ONLY);
    final Transaction transaction = new Transaction("withdraw")
            .to(account, modifier)
            .from(account, modifier.counter(EconomyManager.VIRTUAL))
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(MoneyCommandSupport.sourceID(sender)));
    if(MoneyCommand.processTransaction(sender, transaction, account.getName(), parseMoney.amount()).isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Withdrawn");
      data.addReplacement("$currency", currency.getIdentifier());
      data.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
      sender.message(data);
    }
  }

  private record NoteContext(PlayerAccount player, PlayerProvider provider, Note note) {
  }

  private record PayContext(Account sender, Account receiver) {
  }
}
