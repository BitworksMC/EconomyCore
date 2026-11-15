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
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.account.holdings.modify.HoldingsOperation;
import net.tnemc.core.config.MessageConfig;
import net.tnemc.core.currency.format.CurrencyFormatter;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.TransactionParticipant;
import net.tnemc.core.transaction.history.AwayHistory;
import net.tnemc.core.transaction.history.SortedHistory;
import net.tnemc.plugincore.core.compatibility.CmdSource;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * TransactionCommand
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class TransactionCommand {

  //[page #]
  public static void away(final CmdSource<?> sender, final int page) {

    final Optional<Account> account = BaseCommand.account(sender, "away");
    final Optional<AwayHistory> away = account.filter(PlayerAccount.class::isInstance)
            .flatMap(found->found.away(((PlayerAccount)found).getUUID()));
    if(away.isEmpty() || away.get().getReceipts().isEmpty()) {
      sender.message(new MessageData("Messages.Transaction.AwayNone"));
      return;
    }

    final int selectedPage = Math.min(Math.max(page, 1), away.get().maxPages());
    final MessageData heading = new MessageData("Messages.Transaction.AwayHeader");
    heading.addReplacement("$page", String.valueOf(selectedPage));
    heading.addReplacement("$page_top", String.valueOf(away.get().maxPages()));
    sender.message(heading);

    for(final UUID id : away.get().getPage(selectedPage)) {
      account.get().findReceipt(id).ifPresent(receipt->sender.message(line(account.get(), receipt)));
    }
  }

  //[page:#] [world:name/all] [player:name]
  public static void history(final CmdSource<?> sender, final int page, String region, final Account account) {

    if(sender.player().isPresent() && !account.getIdentifier().equals(sender.identifier().orElse(null))
       && !sender.player().get().hasPermission("tne.transaction.history.other")) {
      sender.message(new MessageData("Messages.General.NoPerm"));
      return;
    }

    region = TNECore.eco().region().resolve(region);

    final SortedHistory sorted = account.getSorted(account.getIdentifier());

    if(sorted == null || sorted.getReceipts().isEmpty()) {
      sender.message(new MessageData("Messages.Transaction.HistoryNone"));
      return;
    }

    final int selectedPage = Math.min(Math.max(page, 1), sorted.maxPages());
    final MessageData heading = new MessageData("Messages.Transaction.HistoryHeader");
    heading.addReplacement("$page", String.valueOf(selectedPage));
    heading.addReplacement("$page_top", String.valueOf(sorted.maxPages()));
    sender.message(heading);

    for(final UUID id : sorted.getPage(selectedPage)) {
      sendHistoryEntry(sender, account, id);
    }
  }

  private static void sendHistoryEntry(final CmdSource<?> sender, final Account account,
                                       final UUID id) {

    account.findReceipt(id).ifPresent(receipt->sender.message(line(account, receipt)));
  }

  /**
   * One transaction as the given account saw it: when, what, how much it gained or lost and with
   * whom. The ID is in the hover text and the click opens its details.
   */
  private static MessageData line(final Account account, final Receipt receipt) {

    final boolean received = receipt.getTo() != null && receipt.getTo().getId().equals(account.getIdentifier());
    final MessageData line = new MessageData("Messages.Transaction.Line");
    line.addReplacement("$id", receipt.getId().toString());
    line.addReplacement("$date", date(receipt.getTime()));
    line.addReplacement("$type", receipt.getType());
    line.addReplacement("$amount", amount(account, received? receipt.getModifierTo() : receipt.getModifierFrom()));
    line.addReplacement("$other", otherName(receipt, received));
    return line;
  }

  private static String date(final long time) {

    try {
      return DateTimeFormatter.ofPattern(MessageConfig.yaml().getString("Messages.Transaction.DateFormat", "yyyy-MM-dd HH:mm"))
              .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    } catch(final IllegalArgumentException invalidPattern) {
      return DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }
  }

  /**
   * The signed change, e.g. "+$10.00" or "-$5.00". Receipts saved before modifiers were stored
   * have none.
   */
  private static String amount(final Account account, final HoldingsModifier modifier) {

    if(modifier == null) {
      return "-";
    }
    final BigDecimal change = modifier.getOperation() == HoldingsOperation.SUBTRACT? modifier.getModifier().negate()
                                                                                : modifier.getModifier();
    final String sign = change.signum() < 0? "-" : "+";
    if(modifier.isPercent()) {
      return sign + change.abs().toPlainString() + "%";
    }
    return sign + CurrencyFormatter.format(account, new HoldingsEntry(modifier.getRegion(), modifier.getCurrency(),
                                                                      change.abs(), EconomyManager.NORMAL));
  }

  /**
   * The account on the other side, or for gives, takes and plugin transactions without one,
   * whoever caused it.
   */
  private static String otherName(final Receipt receipt, final boolean received) {

    final TransactionParticipant other = received? receipt.getFrom() : receipt.getTo();
    if(other != null || receipt.getSource() == null) {
      return participantName(other);
    }
    return TNECore.eco().account().findAccount(receipt.getSource().name())
            .map(Account::getName)
            .orElse(receipt.getSource().name());
  }

  private static String participantName(final TransactionParticipant participant) {

    if(participant == null) {
      return "None";
    }
    return participant.asAccount().map(Account::getName).orElse("None");
  }

  //<uuid>
  public static void info(final CmdSource<?> sender, final UUID uuid) {

    final Optional<Receipt> receipt = TransactionManager.receipts().getReceiptByUUID(uuid);

    if(receipt.isEmpty()) {
      final MessageData message = new MessageData("Messages.Transaction.Invalid");
      message.addReplacement("$transaction", uuid.toString());
      sender.message(message);
      return;
    }

    final MessageData message = new MessageData("Messages.Transaction.Info");
    message.addReplacement("$id", uuid.toString());
    message.addReplacement("$type", receipt.get().getType());
    sender.message(message);
  }

  //<uuid>
  public static void voidT(final CmdSource<?> sender, final Account account, final UUID uuid) {

    final Optional<Receipt> receipt = account.findReceipt(uuid);

    if(receipt.isEmpty()) {
      final MessageData message = new MessageData("Messages.Transaction.Invalid");
      message.addReplacement("$transaction", uuid.toString());
      sender.message(message);
      return;
    }

    if(receipt.get().isVoided()) {
      final MessageData message = new MessageData("Messages.Transaction.Already");
      message.addReplacement("$transaction", uuid.toString());
      sender.message(message);
      return;
    }

    final boolean voided = receipt.get().voidTransaction();
    if(!voided) {
      final MessageData message = new MessageData("Messages.Transaction.Unable");
      message.addReplacement("$transaction", uuid.toString());
      sender.message(message);
      return;
    }
    final MessageData message = new MessageData("Messages.Transaction.Voided");
    message.addReplacement("$transaction", uuid.toString());
    sender.message(message);
  }
}
