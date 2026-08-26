package net.tnemc.core.command;

/*
 * The New Economy
 * Copyright (C) 2022 - 2025 Daniel "creatorfromhell" Vidmar
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
import net.tnemc.core.account.SharedAccount;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.account.holdings.modify.HoldingsOperation;
import net.tnemc.core.account.shared.MemberPermissions;
import net.tnemc.core.actions.source.PlayerSource;
import net.tnemc.core.channel.MessageHandler;
import net.tnemc.core.command.parameters.PercentBigDecimal;
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.currency.Note;
import net.tnemc.core.currency.format.CurrencyFormatter;
import net.tnemc.core.currency.parser.ParseMoney;
import net.tnemc.core.currency.type.MixedType;
import net.tnemc.core.manager.TopManager;
import net.tnemc.core.manager.top.TopPage;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.Transaction;
import net.tnemc.core.transaction.TransactionResult;
import net.tnemc.core.utils.MISCUtils;
import net.tnemc.core.utils.exceptions.InvalidTransactionException;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.CmdSource;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.tnemc.core.EconomyManager.TOP_PER_PAGE;
import static net.tnemc.core.TNECore.DEFAULT_WORLD;

/**
 * MoneyCommands
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class MoneyCommand extends BaseCommand {

  //ArgumentsParser: [currency]
  public static void onMyBal(final CmdSource<?> sender) {

    if(sender.player().isPresent()) {
      sender.player().get().inventory().openMenu(sender.player().get(), "my_bal");
    }
  }

  //ArgumentsParser: [currency] [world]
  public static void onBalance(final CmdSource<?> sender, final Currency currencyParam, final String region) {

    final Currency currency = (currencyParam == null)? TNECore.eco().currency().defaultCurrency() : currencyParam;

    final Optional<PlayerProvider> player = sender.player();
    if(EconomyManager.limitCurrency() && player.isPresent()) {
      if(!player.get().hasPermission("tne.money.balance." + currency.getIdentifier())) {
        final MessageData data = new MessageData("Messages.Account.BlockedAction");
        data.addReplacement("$action", "balance check");
        data.addReplacement("$currency", currency.getDisplay());
        sender.message(data);
        return;
      }
    }

    if(sender.player().isPresent() && MainConfig.yaml().getBoolean("Core.Commands.GUIAlternatives", true)) {
      sender.player().get().inventory().openMenu(sender.player().get(), "my_bal");
      return;
    }

    final Optional<Account> account = BaseCommand.account(sender, "balance");
    if(account.isEmpty()) {
      final MessageData data = new MessageData("Messages.General.NoPlayer");
      data.addReplacement("$player", sender.name());
      sender.message(data);
      return;
    }
    onOther(sender, account.get(), currency, region);
  }

  //ArgumentsParser: <amount> <to currency> [from currency]
  public static void onConvert(final CmdSource<?> sender, final PercentBigDecimal amount, final Currency currency, final Currency fromCurrency) {

    MoneyTransferCommands.convert(sender, amount, currency, fromCurrency);
  }

  //ArgumentsParser: <amount> [currency]
  public static void onDeposit(final CmdSource<?> sender, final ParseMoney parseMoney, final Currency currencyParam, final String regionParam) {

    MoneyTransferCommands.deposit(sender, parseMoney, currencyParam, regionParam);
  }

  //ArgumentsParser: <player> <amount> [world] [currency]
  public static void onGive(final CmdSource<?> sender, final Account account, final ParseMoney parseMoney, final Currency currency, final String region) {

    parseMoney.normalizeParameters(currency, region);

    final Optional<PlayerProvider> player = sender.player();
    if(EconomyManager.limitCurrency() && player.isPresent() && !player.get().hasPermission("tne.money.give." + parseMoney.currency().getIdentifier())) {

      final MessageData data = new MessageData("Messages.Account.BlockedAction");
      data.addReplacement("$action", "give funds");
      data.addReplacement("$currency", parseMoney.currency().getDisplay());
      sender.message(data);
      return;
    }


    final HoldingsModifier modifier = new HoldingsModifier(parseMoney.region(),
                                                           parseMoney.currency().getUid(),
                                                           parseMoney.amount());

    final UUID sourceID = (sender.identifier().isPresent())? sender.identifier().get() : TNECore.instance().getServerAccount();
    final Transaction transaction = new Transaction("give")
            .to(account, modifier)
            .source(new PlayerSource(sourceID));

    final Optional<Receipt> receipt = processTransaction(sender, transaction, account.getName(), parseMoney.amount());
    if(receipt.isPresent()) {
      final MessageData data = new MessageData("Messages.Money.Gave");
      data.addReplacement("$player", account.getName());
      data.addReplacement("$currency", parseMoney.currency().getIdentifier());
      data.addReplacement("$amount", CurrencyFormatter.format(account,
                                                              modifier.asEntry()));
      sender.message(data);

      final MessageData msgData = new MessageData("Messages.Money.Given");
      msgData.addReplacement("$currency", parseMoney.currency().getIdentifier());
      msgData.addReplacement("$player", (sender.name() == null)? MainConfig.yaml().getString("Core.Server.Account.Name") : sender.name());
      msgData.addReplacement("$amount", CurrencyFormatter.format(account, modifier.asEntry()));

      MessageHandler.send(account.getIdentifier(), msgData.grab(account.getIdentifier()));
      if(account.isPlayer() && ((PlayerAccount)account).isOnline()) {

        final Optional<PlayerProvider> provider = ((PlayerAccount)account).getPlayer();

        provider.ifPresent(playerProvider->playerProvider.message(msgData));
      }
    }
  }

  //ArgumentsParser: <amount> [world] [currency]
  public static void onGiveAll(final CmdSource<?> sender, final ParseMoney parseMoney, final Currency currencyParam, final String regionParam) {

    MoneyAdminCommands.giveAll(sender, parseMoney, currencyParam, regionParam);
  }

  public static void onGiveNote(final CmdSource<?> sender, final Account acc, final ParseMoney parseMoney, final Currency currency) {

    parseMoney.normalizeParameters(currency, DEFAULT_WORLD);

    final Optional<Note> note = parseMoney.currency().getNote();

    final Optional<Account> accountOpt = BaseCommand.account(acc.getIdentifier(), "note");
    final Account account = accountOpt.orElse(acc);
    if(note.isPresent() && account instanceof final PlayerAccount player) {

      final Optional<PlayerProvider> provider = player.getPlayer();

      if(provider.isEmpty() || !player.isOnline()) {
        sender.message(new MessageData("Messages.Note.CreateOffline"));
        return;
      }

      if(parseMoney.amount().compareTo(note.get().getMinimum()) < 0) {
        final MessageData min = new MessageData("Messages.Note.Minimum");
        min.addReplacement("$amount", note.get().getMinimum().toPlainString());
        sender.message(min);
        return;
      }

      final BigDecimal rounded = parseMoney.amount().setScale(parseMoney.currency().getDecimalPlaces(), RoundingMode.DOWN);

      final Collection<AbstractItemStack<Object>> left = PluginCore.server().calculations().giveItems(Collections.singletonList(note.get().stack(parseMoney.currency().getIdentifier(), BaseCommand.region(sender), rounded)), provider.get().inventory().getInventory(false));

      final MessageData entryMSG = new MessageData("Messages.Note.Given");
      entryMSG.addReplacement("$currency", parseMoney.currency().getIdentifier());
      entryMSG.addReplacement("$amount", CurrencyFormatter.format(account, rounded));
      provider.get().message(entryMSG);

      final MessageData senderMSG = new MessageData("Messages.Note.Created");
      senderMSG.addReplacement("$player", provider.get().getName());
      senderMSG.addReplacement("$currency", parseMoney.currency().getIdentifier());
      senderMSG.addReplacement("$amount", CurrencyFormatter.format(account, rounded));
      sender.message(senderMSG);

      if(!left.isEmpty()) {
        PluginCore.server().calculations().drop(left, player.getUUID(), true);
        provider.get().message(new MessageData("Messages.Note.Dropped"));
      }
      return;
    }
    sender.message(new MessageData("Messages.Note.CreateOffline"));
  }

  //ArgumentsParser: <amount> [currency]
  public static void onNote(final CmdSource<?> sender, final ParseMoney parseMoney, final Currency currency) {

    MoneyTransferCommands.note(sender, parseMoney, currency);
  }

  //ArgumentsParser: <player> [world] [currency]
  public static void onOther(final CmdSource<?> sender, final Account account, final Currency currencyParam, String region) {

    MoneyAdminCommands.other(sender, account, currencyParam, region);
  }

  public static void printBalance(final CmdSource<?> sender, final Account account, final Currency currency, final String region) {

    final MessageData entryMSG = new MessageData("Messages.Money.HoldingsMultiSingle");
    entryMSG.addReplacement("$currency", currency.getIdentifier());

    BigDecimal amount = BigDecimal.ZERO;
    for(final HoldingsEntry entry : currency.type().getHoldings(account, region, currency, EconomyManager.NORMAL)) {
      amount = amount.add(entry.getAmount());

      if(entry.getHandler().asID().equalsIgnoreCase(EconomyManager.INVENTORY_ONLY.asID())) {
        if(currency.type().supportsItems()) {
          entryMSG.addReplacement("$inventory", CurrencyFormatter.format(account, entry));
        } else {
          entryMSG.addReplacement("$inventory", "0");
        }
      }

      if(entry.getHandler().asID().equalsIgnoreCase(EconomyManager.E_CHEST.asID())) {
        if(currency.type().supportsItems()) {
          entryMSG.addReplacement("$ender", CurrencyFormatter.format(account, entry));
        } else {
          entryMSG.addReplacement("$ender", "0");
        }
      }

      if(entry.getHandler().asID().equalsIgnoreCase(EconomyManager.VIRTUAL.asID())) {
        entryMSG.addReplacement("$virtual", CurrencyFormatter.format(account, entry));
      }
    }
    entryMSG.addReplacement("$amount", CurrencyFormatter.format(account, new HoldingsEntry(region, currency.getUid(), amount, EconomyManager.NORMAL)));
    sender.message(entryMSG);
  }

  //ArgumentsParser: <player> <amount> [currency] [from:account]
  public static void onPay(final CmdSource<?> sender, final Account acc, final ParseMoney parseMoney, final Currency currency) {

    MoneyTransferCommands.pay(sender, acc, parseMoney, currency);
  }

  //ArgumentsParser: <player> <amount> [currency]
  public static void onRequest(final CmdSource<?> sender, final Account account, final ParseMoney parseMoney, final Currency currency) {

    parseMoney.normalizeParameters(currency, DEFAULT_WORLD);

    final Optional<PlayerProvider> player = sender.player();
    if(EconomyManager.limitCurrency() && player.isPresent()) {
      if(!player.get().hasPermission("tne.money.request." + parseMoney.currency().getIdentifier())) {
        final MessageData data = new MessageData("Messages.Account.BlockedAction");
        data.addReplacement("$action", "request funds");
        data.addReplacement("$currency", parseMoney.currency().getDisplay());
        sender.message(data);
        return;
      }
    }

    if(parseMoney.amount().compareTo(BigDecimal.ZERO) < 0) {
      sender.message(new MessageData("Messages.Money.Negative"));
      return;
    }

    if(!(account instanceof PlayerAccount playerAccount)) {
      final MessageData data = new MessageData("Messages.General.NoPlayer");
      data.addReplacement("$player", account.getName());
      sender.message(data);
      return;
    }

    final Optional<PlayerProvider> provider = PluginCore.server().findPlayer(playerAccount.getUUID());
    if(provider.isEmpty()) {
      final MessageData data = new MessageData("Messages.General.NoPlayer");
      data.addReplacement("$player", account.getName());
      sender.message(data);
      return;
    }

    final MessageData msg = new MessageData("Messages.Money.RequestSender");
    msg.addReplacement("$player", account.getName());
    msg.addReplacement("$amount", parseMoney.amount().toPlainString());
    sender.message(msg);

    final MessageData request = new MessageData("Messages.Money.Request");
    request.addReplacement("$player", sender.name());
    request.addReplacement("$amount", parseMoney.amount().toPlainString());
    request.addReplacement("$currency", parseMoney.currency().getIdentifier());
    provider.get().message(request);
  }

  //ArgumentsParser: <player> <amount> [world] [currency]
  public static void onSet(final CmdSource<?> sender, final Account account, final ParseMoney parseMoney, final Currency currencyParam, final String regionParam) {

    parseMoney.normalizeParameters(currencyParam, regionParam);

    String region = parseMoney.region();
    final Currency currency = parseMoney.currency();

    final Optional<PlayerProvider> player = sender.player();
    if(EconomyManager.limitCurrency() && player.isPresent()) {
      if(!player.get().hasPermission("tne.money.set." + currency.getIdentifier())) {
        final MessageData data = new MessageData("Messages.Account.BlockedAction");
        data.addReplacement("$action", "set funds");
        data.addReplacement("$currency", currency.getDisplay());
        sender.message(data);
        return;
      }
    }

    if(player.isPresent() && region.equalsIgnoreCase("world-113")) {
      region = player.get().world();
    }

    region = TNECore.eco().region().resolve(region);

    final HoldingsModifier modifier = new HoldingsModifier(region,
                                                           currency.getUid(),
                                                           parseMoney.amount().setScale(currency.getDecimalPlaces(), RoundingMode.DOWN),
                                                           HoldingsOperation.SET);

    final UUID sourceID = (sender.identifier().isPresent())? sender.identifier().get() : TNECore.instance().getServerAccount();
    final Transaction transaction = new Transaction("set")
            .to(account, modifier)
            .processor(EconomyManager.baseProcessor())
            .source(new PlayerSource(sourceID));

    final Optional<Receipt> receipt = processTransaction(sender, transaction, account.getName(), parseMoney.amount());

    if(receipt.isPresent()) {
      final MessageData msg = new MessageData("Messages.Money.Set");
      msg.addReplacement("$player", account.getName());
      msg.addReplacement("$currency", currency.getIdentifier());
      msg.addReplacement("$amount", CurrencyFormatter.format(account,
                                                             modifier.asEntry())
                        );
      sender.message(msg);
    }
  }

  //ArgumentsParser: <amount> [world] [currency]
  public static void onSetAll(final CmdSource<?> sender, final ParseMoney parseMoney, final Currency currencyParam, final String regionParam) {

    parseMoney.normalizeParameters(currencyParam, regionParam);

    String region = parseMoney.region();
    final Currency currency = parseMoney.currency();

    final Optional<PlayerProvider> player = sender.player();
    if(EconomyManager.limitCurrency() && player.isPresent()) {
      if(!player.get().hasPermission("tne.money.setall." + currency.getIdentifier())) {
        final MessageData data = new MessageData("Messages.Account.BlockedAction");
        data.addReplacement("$action", "set all funds");
        data.addReplacement("$currency", currency.getDisplay());
        sender.message(data);
        return;
      }
    }

    if(player.isPresent() && region.equalsIgnoreCase("world-113")) {
      region = player.get().world();
    }

    region = TNECore.eco().region().resolve(region);

    final HoldingsModifier modifier = new HoldingsModifier(region,
                                                           currency.getUid(),
                                                           parseMoney.amount().setScale(currency.getDecimalPlaces(), RoundingMode.DOWN),
                                                           HoldingsOperation.SET);

    final UUID sourceID = (sender.identifier().isPresent())? sender.identifier().get() : TNECore.instance().getServerAccount();
    for(final Account account : TNECore.eco().account().getAccounts().values()) {
      final Transaction transaction = new Transaction("set")
              .to(account, modifier)
              .processor(EconomyManager.baseProcessor())
              .source(new PlayerSource(sourceID));

      final Optional<Receipt> receipt = processTransaction(sender, transaction, account.getName(), parseMoney.amount());

      if(receipt.isPresent()) {
        final MessageData msg = new MessageData("Messages.Money.Set");
        msg.addReplacement("$player", account.getName());
        msg.addReplacement("$currency", currency.getIdentifier());
        msg.addReplacement("$amount", CurrencyFormatter.format(account,
                                                               modifier.asEntry())
                          );

        msg.addReplacements(new String[]{
                ""
        }, new String[]{

        });
        sender.message(msg);
      }
    }
  }

  public static void onSwitch(final CmdSource<?> sender, final Account account) {

    if(account instanceof final SharedAccount shared) {

      if(sender.identifier().isEmpty()) {

        final MessageData data = new MessageData("Messages.Account.SwitchedFailed");
        data.addReplacement("$account", account.getName());
        sender.message(data);
        return;
      }

      if(!shared.hasPermission(sender.identifier().get(), MemberPermissions.WITHDRAW)) {

        final MessageData data = new MessageData("Messages.Account.SwitchedFailed");
        data.addReplacement("$account", account.getName());
        sender.message(data);
        return;
      }

      doSwitch(sender.identifier().get(), account.getIdentifier());
      final MessageData data = new MessageData("Messages.Account.Switched");
      data.addReplacement("$account", account.getName());
      sender.message(data);
      return;
    } else {
      if(sender.identifier().isPresent() && sender.player().isPresent()) {

        if(account.getIdentifier().equals(sender.identifier().get()) || sender.player().get().hasPermission("tne.money.switch.override")) {

          doSwitch(sender.identifier().get(), account.getIdentifier());
          final MessageData data = new MessageData("Messages.Account.Switched");
          data.addReplacement("$account", account.getName());
          sender.message(data);
          return;
        }
      }
    }
    final MessageData data = new MessageData("Messages.Account.SwitchedFailed");
    data.addReplacement("$account", account.getName());
    sender.message(data);
  }

  //helper method for onSwitch
  private static void doSwitch(final UUID account, final UUID swapAccount) {

    if(swapAccount.equals(account)) {
      TNECore.eco().account().removeSwap("balance", account);
      TNECore.eco().account().removeSwap("convert", account);
      TNECore.eco().account().removeSwap("deposit", account);
      TNECore.eco().account().removeSwap("note", account);
      TNECore.eco().account().removeSwap("pay", account);
      TNECore.eco().account().removeSwap("payreceive", account);
      TNECore.eco().account().removeSwap("withdraw", account);
      return;
    }
    TNECore.eco().account().addSwap("balance", account, swapAccount);
    TNECore.eco().account().addSwap("convert", account, swapAccount);
    TNECore.eco().account().addSwap("deposit", account, swapAccount);
    TNECore.eco().account().addSwap("note", account, swapAccount);
    TNECore.eco().account().addSwap("pay", account, swapAccount);
    TNECore.eco().account().addSwap("payreceive", account, swapAccount);
    TNECore.eco().account().addSwap("withdraw", account, swapAccount);
  }

  //ArgumentsParser: <player> <amount> [world] [currency]
  public static void onTake(final CmdSource<?> sender, final Account account, final ParseMoney parseMoney, final Currency currencyParam, final String regionParam) {

    MoneyAdminCommands.take(sender, account, parseMoney, currencyParam, regionParam);
  }

  //ArgumentsParser: [page] [currency:name] [world:world] [limit:#]
  public static void onTop(final CmdSource<?> sender, Integer page, final Currency currencyParam, final Boolean refresh) {

    MoneyAdminCommands.top(sender, page, currencyParam, refresh);
  }

  //ArgumentsParser: <amount> [currency]
  public static void onWithdraw(final CmdSource<?> sender, final ParseMoney parseMoney, final Currency currencyParam, final String regionParam) {

    MoneyTransferCommands.withdraw(sender, parseMoney, currencyParam, regionParam);
  }

  public static Optional<Receipt> processTransaction(final CmdSource<?> sender, final Transaction transaction, final String modifiedAccount, final BigDecimal modifier) {

    try {
      final TransactionResult result = transaction.process();

      if(!result.isSuccessful()) {
        final MessageData data = new MessageData(result.getMessage());
        data.addReplacement("$player", modifiedAccount);
        data.addReplacement("$amount", modifier.toPlainString());

        sender.message(data);
        return Optional.empty();
      }

      return result.getReceipt();
    } catch(final InvalidTransactionException e) {
      e.printStackTrace();
    }
    return Optional.empty();
  }
}
