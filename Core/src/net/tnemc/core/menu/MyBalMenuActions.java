package net.tnemc.core.menu;

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
import net.tnemc.core.currency.Currency;
import net.tnemc.core.currency.Note;
import net.tnemc.core.currency.format.CurrencyFormatter;
import net.tnemc.core.currency.type.MixedType;
import net.tnemc.core.menu.handlers.AmountSelectionHandler;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.Transaction;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.menu.core.viewer.MenuViewer;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

final class MyBalMenuActions {

  private MyBalMenuActions() {
  }

  static void convert(final AmountSelectionHandler handler) {

    final Optional<ActionContext> context = context(handler, true);
    if(context.isEmpty()) {
      return;
    }
    final Optional<Object> convertID = context.get().viewer().findData(MyBalMenu.ACTION_CONVERT_CURRENCY);
    if(convertID.isEmpty()) {
      return;
    }
    final Optional<Currency> fromCurrency = TNECore.eco().currency().find((UUID)convertID.get());
    if(fromCurrency.isEmpty() || conversionBlocked(handler, context.get(), fromCurrency.get())) {
      return;
    }
    final Optional<Account> account = TNECore.eco().account().findAccount(context.get().playerID());
    if(account.isEmpty()) {
      noPlayer(context.get().player());
      return;
    }
    final Optional<BigDecimal> converted = fromCurrency.get().convertValue(
            context.get().currency().getIdentifier(), handler.getAmount());
    if(converted.isEmpty()) {
      final MessageData data = new MessageData("Messages.Money.NoConversion");
      data.addReplacement("$converted", context.get().currency().getIdentifier());
      context.get().player().message(data);
      return;
    }
    processConversion(handler, context.get(), fromCurrency.get(), account.get(), converted.get());
  }

  private static boolean conversionBlocked(final AmountSelectionHandler handler,
                                           final ActionContext context,
                                           final Currency fromCurrency) {

    if(denied(context.player(), "tne.money.convert.to." + context.currency().getIdentifier(),
              "convert to", context.currency())) {
      return true;
    }
    if(denied(context.player(), "tne.money.convert.from." + fromCurrency.getIdentifier(),
              "convert from", fromCurrency)) {
      return true;
    }
    if(handler.getAmount().compareTo(BigDecimal.ZERO) < 0) {
      context.player().message(new MessageData("Messages.Money.Negative"));
      return true;
    }
    if(context.currency().getUid().equals(fromCurrency.getUid())) {
      context.player().message(new MessageData("Messages.Money.ConvertSame"));
      return true;
    }
    return false;
  }

  private static void processConversion(final AmountSelectionHandler handler,
                                        final ActionContext context, final Currency fromCurrency,
                                        final Account account, final BigDecimal converted) {

    final HoldingsModifier modifier = new HoldingsModifier("", context.currency().getUid(),
                                                            converted.setScale(context.currency().getDecimalPlaces(),
                                                                               RoundingMode.DOWN));
    final HoldingsModifier modifierFrom = new HoldingsModifier("", fromCurrency.getUid(),
                                                                handler.getAmount().setScale(
                                                                        context.currency().getDecimalPlaces(),
                                                                        RoundingMode.DOWN).negate());
    final Transaction transaction = new Transaction("convert")
            .from(account, modifierFrom)
            .to(account, modifier)
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(context.playerID()));
    if(MyBalMenu.processTransaction(context.player(), transaction, account.getName(),
                                    handler.getAmount()).isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Converted");
      data.addReplacement("$from_amount", handler.getAmount().toPlainString());
      data.addReplacement("$amount", CurrencyFormatter.format(account, modifierFrom.asEntry()));
      context.player().message(data);
    }
  }

  static void deposit(final AmountSelectionHandler handler) {

    final Optional<ActionContext> context = context(handler, true);
    if(context.isEmpty()) {
      return;
    }
    if(denied(context.get().player(), "tne.money.deposit." + context.get().currency().getIdentifier(),
              "deposit", context.get().currency())) {
      return;
    }
    if(handler.getAmount().compareTo(BigDecimal.ZERO) < 0) {
      context.get().player().message(new MessageData("Messages.Money.Negative"));
      return;
    }
    final Optional<Account> account = TNECore.eco().account().findAccount(context.get().playerID());
    if(account.isEmpty()) {
      noPlayer(context.get().player());
      return;
    }
    if(!(context.get().currency().type() instanceof MixedType)) {
      context.get().player().message(new MessageData("Messages.Money.NotMixed"));
      return;
    }
    processDeposit(handler, context.get(), account.get());
  }

  private static void processDeposit(final AmountSelectionHandler handler,
                                     final ActionContext context, final Account account) {

    final HoldingsModifier modifier = new HoldingsModifier(
            TNECore.eco().region().getMode().region(context.player()),
            context.currency().getUid(), handler.getAmount(), EconomyManager.VIRTUAL);
    final Transaction transaction = new Transaction("deposit")
            .to(account, modifier)
            .from(account, modifier.counter(EconomyManager.ITEM_ONLY))
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(context.playerID()));
    if(MyBalMenu.processTransaction(context.player(), transaction, account.getName(),
                                    handler.getAmount()).isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Deposit");
      data.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
      context.player().message(data);
    }
  }

  static void note(final AmountSelectionHandler handler) {

    final Optional<ActionContext> context = context(handler, true);
    if(context.isEmpty()) {
      return;
    }
    final Optional<Account> account = TNECore.eco().account().findAccount(context.get().playerID());
    final Optional<Note> note = context.get().currency().getNote();
    if(account.isEmpty() || note.isEmpty() || !(account.get() instanceof final PlayerAccount playerAccount)) {
      return;
    }
    if(denied(context.get().player(), "tne.money.note." + context.get().currency().getIdentifier(),
              "note", context.get().currency())) {
      return;
    }
    if(handler.getAmount().compareTo(note.get().getMinimum()) < 0) {
      final MessageData minimum = new MessageData("Messages.Note.Minimum");
      minimum.addReplacement("$amount", note.get().getMinimum().toPlainString());
      context.get().player().message(minimum);
      return;
    }
    createNote(handler, context.get(), playerAccount, note.get());
  }

  private static void createNote(final AmountSelectionHandler handler,
                                 final ActionContext context, final PlayerAccount account,
                                 final Note note) {

    final String region = TNECore.eco().region().getMode().region(context.player());
    final BigDecimal rounded = handler.getAmount().setScale(context.currency().getDecimalPlaces(), RoundingMode.DOWN);
    final BigDecimal amount = rounded.add(note.getFee().calculateTax(rounded))
            .setScale(context.currency().getDecimalPlaces(), RoundingMode.DOWN);
    final HoldingsModifier modifier = new HoldingsModifier(region, context.currency().getUid(), amount);
    final Transaction transaction = new Transaction("note")
            .from(account, modifier.counter())
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(context.playerID()));
    final Optional<Receipt> receipt = MyBalMenu.processTransaction(context.player(), transaction,
                                                                   account.getName(), handler.getAmount());
    if(receipt.isEmpty()) {
      return;
    }
    final Collection<AbstractItemStack<Object>> left = PluginCore.server().calculations().giveItems(
            Collections.singletonList(note.stack(context.currency().getIdentifier(), region, rounded)),
            context.player().inventory().getInventory(false));
    if(!left.isEmpty()) {
      PluginCore.server().calculations().drop(left, account.getUUID(), true);
    }
    final MessageData entry = new MessageData("Messages.Note.Given");
    entry.addReplacement("$currency", context.currency().getIdentifier());
    entry.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
    context.player().message(entry);
  }

  static void withdraw(final AmountSelectionHandler handler) {

    final Optional<ActionContext> context = context(handler, false);
    if(context.isEmpty()) {
      return;
    }
    if(denied(context.get().player(), "tne.money.withdraw." + context.get().currency().getIdentifier(),
              "withdraw funds", context.get().currency())) {
      return;
    }
    if(handler.getAmount().compareTo(BigDecimal.ZERO) < 0) {
      context.get().player().message(new MessageData("Messages.Money.Negative"));
      return;
    }
    final Optional<Account> account = TNECore.eco().account().findAccount(context.get().playerID());
    if(account.isEmpty()) {
      noPlayer(context.get().player());
      return;
    }
    if(!(context.get().currency().type() instanceof MixedType)) {
      context.get().player().message(new MessageData("Messages.Money.NotMixed"));
      return;
    }
    processWithdrawal(handler, context.get(), account.get());
  }

  private static void processWithdrawal(final AmountSelectionHandler handler,
                                        final ActionContext context, final Account account) {

    final HoldingsModifier modifier = new HoldingsModifier(
            TNECore.eco().region().getMode().region(context.player()),
            context.currency().getUid(), handler.getAmount(), EconomyManager.ITEM_ONLY);
    final Transaction transaction = new Transaction("withdraw")
            .to(account, modifier)
            .from(account, modifier.counter(EconomyManager.VIRTUAL))
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(context.playerID()));
    if(MyBalMenu.processTransaction(context.player(), transaction, account.getName(),
                                    handler.getAmount()).isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Withdrawn");
      data.addReplacement("$currency", context.currency().getIdentifier());
      data.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));
      context.player().message(data);
    }
  }

  private static Optional<ActionContext> context(final AmountSelectionHandler handler,
                                                 final boolean requireReceiver) {

    final Optional<MenuViewer> viewer = handler.getClick().player().viewer();
    if(viewer.isEmpty()) {
      return Optional.empty();
    }
    final Optional<Object> currencyID = viewer.get().findData(MyBalMenu.ACTION_CURRENCY);
    if(currencyID.isEmpty()) {
      return Optional.empty();
    }
    if(requireReceiver && viewer.get().findData(MyBalMenu.ACTION_ACCOUNT_ID + "_ID").isEmpty()) {
      return Optional.empty();
    }
    final Optional<Currency> currency = TNECore.eco().currency().find((UUID)currencyID.get());
    final Optional<PlayerProvider> player = PluginCore.server().findPlayer(
            handler.getClick().player().identifier());
    if(currency.isEmpty() || player.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new ActionContext(viewer.get(), viewer.get().uuid(), currency.get(), player.get()));
  }

  private static boolean denied(final PlayerProvider player, final String permission,
                                final String action, final Currency currency) {

    if(!EconomyManager.limitCurrency() || player.hasPermission(permission)) {
      return false;
    }
    final MessageData data = new MessageData("Messages.Account.BlockedAction");
    data.addReplacement("$action", action);
    data.addReplacement("$currency", currency.getDisplay());
    player.message(data);
    return true;
  }

  private static void noPlayer(final PlayerProvider player) {

    final MessageData data = new MessageData("Messages.General.NoPlayer");
    data.addReplacement("$player", player.getName());
    player.message(data);
  }

  private record ActionContext(MenuViewer viewer, UUID playerID, Currency currency,
                               PlayerProvider player) {
  }
}
