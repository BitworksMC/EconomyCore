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
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.currency.format.CurrencyFormatter;
import net.tnemc.core.menu.handlers.AmountSelectionHandler;
import net.tnemc.core.transaction.Transaction;
import net.tnemc.menu.core.viewer.MenuViewer;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

final class MyBalMenuPayment {

  private MyBalMenuPayment() {
  }

  static void pay(final AmountSelectionHandler handler) {

    final Optional<PaymentContext> context = context(handler);
    if(context.isEmpty()) {
      return;
    }
    final PaymentContext payment = context.get();
    if(denied(payment)) {
      return;
    }
    if(handler.getAmount().compareTo(BigDecimal.ZERO) < 0) {
      payment.player().message(new MessageData("Messages.Money.Negative"));
      return;
    }
    final Optional<Account> senderAccount = TNECore.eco().account().findAccount(payment.playerID());
    if(senderAccount.isEmpty()) {
      final MessageData data = new MessageData("Messages.General.NoPlayer");
      data.addReplacement("$player", payment.player().getName());
      payment.player().message(data);
      return;
    }
    if(senderAccount.get().getIdentifier().equals(payment.receiver().getIdentifier())) {
      final MessageData data = new MessageData("Messages.Money.SelfPay");
      data.addReplacement("$player", payment.player().getName());
      payment.player().message(data);
      return;
    }
    if(payeeOffline(payment.receiver())) {
      payment.player().message(new MessageData("Messages.Money.PayFailedOnline"));
      return;
    }
    if(distanceBlocked(payment.player(), senderAccount.get(), payment.receiver())) {
      return;
    }
    processPayment(handler, payment, senderAccount.get());
  }

  private static Optional<PaymentContext> context(final AmountSelectionHandler handler) {

    final Optional<MenuViewer> viewer = handler.getClick().player().viewer();
    if(viewer.isEmpty()) {
      return Optional.empty();
    }
    final Optional<Object> currencyID = viewer.get().findData(MyBalMenu.ACTION_CURRENCY);
    final Optional<Object> receiverID = viewer.get().findData(MyBalMenu.ACTION_ACCOUNT_ID + "_ID");
    if(currencyID.isEmpty() || receiverID.isEmpty()) {
      return Optional.empty();
    }
    final Optional<Currency> currency = TNECore.eco().currency().find((UUID)currencyID.get());
    final Optional<PlayerProvider> player = PluginCore.server().findPlayer(
            handler.getClick().player().identifier());
    if(currency.isEmpty() || player.isEmpty()) {
      return Optional.empty();
    }
    final Optional<Account> receiver = TNECore.eco().account().findAccount(
            UUID.fromString((String)receiverID.get()));
    return receiver.map(account->new PaymentContext(viewer.get().uuid(), currency.get(),
                                                    player.get(), account));
  }

  private static boolean denied(final PaymentContext context) {

    if(!EconomyManager.limitCurrency()
       || context.player().hasPermission("tne.money.pay." + context.currency().getIdentifier())) {
      return false;
    }
    final MessageData data = new MessageData("Messages.Account.BlockedAction");
    data.addReplacement("$action", "pay");
    data.addReplacement("$currency", context.currency().getDisplay());
    context.player().message(data);
    return true;
  }

  private static boolean payeeOffline(final Account account) {

    if(MainConfig.yaml().getBoolean("Core.Commands.Pay.Offline", true)) {
      return false;
    }
    return !(account instanceof PlayerAccount) || !((PlayerAccount)account).isOnline();
  }

  private static boolean distanceBlocked(final PlayerProvider player, final Account sender,
                                         final Account receiver) {

    final int radius = MainConfig.yaml().getInt("Core.Commands.Pay.Radius", 0);
    if(radius <= 0) {
      return false;
    }
    final MessageData data = new MessageData("Messages.Money.PayFailedDistance");
    data.addReplacement("$distance", String.valueOf(radius));
    final Optional<PlayerAccount> senderAccount = onlinePlayer(sender);
    final Optional<PlayerAccount> receiverAccount = onlinePlayer(receiver);
    if(senderAccount.isEmpty() || receiverAccount.isEmpty()) {
      player.message(data);
      return true;
    }
    final Optional<PlayerProvider> senderPlayer = senderAccount.get().getPlayer();
    final Optional<PlayerProvider> receiverPlayer = receiverAccount.get().getPlayer();
    if(senderPlayer.isEmpty() || receiverPlayer.isEmpty()) {
      player.message(data);
      return true;
    }
    if(senderPlayer.get().getLocation().isEmpty() || receiverPlayer.get().getLocation().isEmpty()) {
      player.message(data);
      return true;
    }
    if(senderPlayer.get().getLocation().get().distance(receiverPlayer.get().getLocation().get()) > radius) {
      player.message(data);
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

  private static void processPayment(final AmountSelectionHandler handler,
                                     final PaymentContext context, final Account senderAccount) {

    final HoldingsModifier modifier = new HoldingsModifier(
            TNECore.eco().region().getMode().region(context.player()),
            context.currency().getUid(), handler.getAmount());
    final Transaction transaction = new Transaction("pay")
            .to(context.receiver(), modifier)
            .from(senderAccount, modifier.counter())
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(context.playerID()));
    if(MyBalMenu.processTransaction(context.player(), transaction, context.receiver().getName(),
                                    handler.getAmount()).isEmpty()) {
      return;
    }
    final MessageData data = new MessageData("Messages.Money.Paid");
    data.addReplacement("$player", context.receiver().getName());
    data.addReplacement("$currency", context.currency().getIdentifier());
    data.addReplacement("$amount", CurrencyFormatter.format(context.receiver(), modifier.asEntry()));
    context.player().message(data);
    notifyReceiver(context, modifier);
  }

  private static void notifyReceiver(final PaymentContext context,
                                     final HoldingsModifier modifier) {

    if(!context.receiver().isPlayer() || !((PlayerAccount)context.receiver()).isOnline()) {
      return;
    }
    final Optional<PlayerProvider> provider = PluginCore.server().findPlayer(
            ((PlayerAccount)context.receiver()).getUUID());
    if(provider.isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Received");
      data.addReplacement("$player", context.player().getName() == null
                                     ? MainConfig.yaml().getString("Core.Server.Account.Name")
                                     : context.player().getName());
      data.addReplacement("$amount", CurrencyFormatter.format(context.receiver(), modifier.asEntry()));
      provider.get().message(data);
    }
  }

  private record PaymentContext(UUID playerID, Currency currency, PlayerProvider player,
                                Account receiver) {
  }
}
