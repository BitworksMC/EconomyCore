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

import net.kyori.adventure.text.Component;
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.currency.format.CurrencyFormatter;
import net.tnemc.core.currency.type.VirtualType;
import net.tnemc.core.menu.icons.shared.PreviousPageIcon;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.Icon;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.icon.action.IconAction;
import net.tnemc.menu.core.icon.action.impl.DataAction;
import net.tnemc.menu.core.icon.action.impl.SwitchPageAction;
import net.tnemc.menu.core.viewer.MenuViewer;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;
import net.tnemc.plugincore.core.io.message.MessageHandler;

import java.util.Collections;
import java.util.LinkedList;
import java.util.Optional;
import java.util.UUID;

final class MyBalMenuView {

  private MyBalMenuView() {
  }

  static void actionsPage(final PageOpenCallback callback, final String menuName) {

    final Optional<MenuViewer> viewer = callback.getPlayer().viewer();
    if(viewer.isEmpty()) {
      return;
    }
    final UUID id = viewer.get().uuid();
    callback.getPage().addIcon(new PreviousPageIcon(id, 0, menuName, 1, ActionType.ANY));
    final Optional<Account> account = TNECore.eco().account().findAccount(callback.getPlayer().identifier());
    if(account.isEmpty()) {
      return;
    }
    final Optional<Object> currencyID = viewer.get().findData(MyBalMenu.ACTION_CURRENCY);
    if(currencyID.isEmpty()) {
      return;
    }
    final Optional<Currency> currency = TNECore.eco().currency().find((UUID)currencyID.get());
    if(currency.isEmpty()) {
      return;
    }
    final Optional<PlayerProvider> provider = playerProvider(account.get());
    addConvertAction(callback, menuName, id, (UUID)currencyID.get(), provider);
    if(currency.get().type().supportsExchange()) {
      addExchangeActions(callback, menuName, id, (UUID)currencyID.get(), provider);
    }
  }

  private static void addConvertAction(final PageOpenCallback callback, final String menuName,
                                       final UUID viewerID, final UUID currencyID,
                                       final Optional<PlayerProvider> provider) {

    if(allowed(provider, "tne.money.from." + currencyID)) {
      callback.getPage().addIcon(actionIcon(menuName, viewerID, 10,
                                            "Convert", MyBalMenu.BALANCE_ACTION_CONVERT_CURRENCY_PAGE));
    }
  }

  private static void addExchangeActions(final PageOpenCallback callback, final String menuName,
                                         final UUID viewerID, final UUID currencyID,
                                         final Optional<PlayerProvider> provider) {

    if(allowed(provider, "tne.money.deposit." + currencyID)) {
      callback.getPage().addIcon(actionIcon(menuName, viewerID, 12,
                                            "Deposit", MyBalMenu.BALANCE_ACTION_DEPOSIT_AMOUNT_PAGE));
    }
    if(allowed(provider, "tne.money.withdraw." + currencyID)) {
      callback.getPage().addIcon(actionIcon(menuName, viewerID, 14,
                                            "Withdraw", MyBalMenu.BALANCE_ACTION_WITHDRAW_AMOUNT_PAGE));
    }
  }

  private static Icon actionIcon(final String menuName, final UUID viewerID, final int slot,
                                 final String messageName, final int destination) {

    final String root = "Messages.Menu.MyBal.Actions." + messageName;
    return new IconBuilder(PluginCore.server().stackBuilder().of("PAPER", 1)
                                   .customName(MessageHandler.grab(new MessageData(root + "Display"), viewerID))
                                   .lore(Collections.singletonList(
                                           MessageHandler.grab(new MessageData(root), viewerID))))
            .withSlot(slot)
            .withActions(new SwitchPageAction(menuName, destination))
            .build();
  }

  static Icon buildBalanceIcon(final String menuName, final int slot,
                               final Currency currency, final Account account) {

    final UUID id = account.getIdentifier();
    final Optional<PlayerProvider> provider = playerProvider(account);
    final LinkedList<Component> lore = new LinkedList<>();
    final LinkedList<IconAction> actions = new LinkedList<>();
    actions.add(new DataAction(MyBalMenu.ACTION_CURRENCY, currency.getUid()));
    actions.add(new DataAction(MyBalMenu.ACTION_MAX_HOLDINGS,
                               account.getHoldingsTotal(TNECore.eco().region().defaultRegion(), currency.getUid())));
    addBalanceLore(lore, id, account, currency);
    if(allowed(provider, "tne.money.pay." + currency.getIdentifier())) {
      lore.add(MessageHandler.grab(new MessageData("Messages.Menu.MyBal.Main.Pay"), id));
      actions.add(new SwitchPageAction(menuName, MyBalMenu.BALANCE_PAY_PAGE, ActionType.LEFT_CLICK));
    }
    lore.add(MessageHandler.grab(new MessageData("Messages.Menu.MyBal.Main.Other"), id));
    actions.add(new SwitchPageAction(menuName, MyBalMenu.BALANCE_ACTIONS_PAGE, ActionType.DROP));
    addOptionalActions(menuName, currency, id, provider, lore, actions);
    return new IconBuilder(PluginCore.server().stackBuilder().of(currency.getIconMaterial(), 1)
                                   .customName(Component.text(currency.getIdentifier())).lore(lore))
            .withSlot(slot)
            .withActions(actions.toArray(new IconAction[actions.size()])).build();
  }

  private static void addBalanceLore(final LinkedList<Component> lore, final UUID id,
                                     final Account account, final Currency currency) {

    final MessageData balance = new MessageData("Messages.Menu.MyBal.Main.Balance");
    final HoldingsEntry entry = new HoldingsEntry(PluginCore.server().defaultWorld(), currency.getUid(),
                                                   account.getHoldingsTotal(TNECore.eco().region().defaultRegion(),
                                                                            currency.getUid()),
                                                   EconomyManager.NORMAL);
    balance.addReplacement("$balance", CurrencyFormatter.format(account, entry));
    lore.add(MessageHandler.grab(balance, id));
  }

  private static void addOptionalActions(final String menuName, final Currency currency,
                                         final UUID id, final Optional<PlayerProvider> provider,
                                         final LinkedList<Component> lore,
                                         final LinkedList<IconAction> actions) {

    if(currency.type() instanceof VirtualType && currency.isNotable()
       && allowed(provider, "tne.money.pay." + currency.getIdentifier())) {
      lore.add(MessageHandler.grab(new MessageData("Messages.Menu.MyBal.Main.Note"), id));
      actions.add(new SwitchPageAction(menuName, MyBalMenu.BALANCE_NOTE_AMOUNT_PAGE, ActionType.RIGHT_CLICK));
    }
    if(currency.type().supportsItems()) {
      lore.add(MessageHandler.grab(new MessageData("Messages.Menu.MyBal.Main.Breakdown"), id));
      actions.add(new SwitchPageAction(menuName, MyBalMenu.BALANCE_BREAKDOWN_PAGE, ActionType.RIGHT_CLICK));
    }
  }

  private static Optional<PlayerProvider> playerProvider(final Account account) {

    if(account.isPlayer()) {
      return ((PlayerAccount)account).getPlayer();
    }
    return Optional.empty();
  }

  private static boolean allowed(final Optional<PlayerProvider> provider, final String permission) {

    return !EconomyManager.limitCurrency()
           || provider.isPresent() && provider.get().hasPermission(permission);
  }
}
