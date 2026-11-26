package net.tnemc.core.transaction;

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


import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.channel.BalanceHandler;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Coordinates writes and compensation for the default transaction processor. */
final class TransactionSettlement {

  private static final String FAILED = "Messages.Money.Failed";

  private TransactionSettlement() {
  }

  static boolean inProgress() {

    return BalanceHandler.deferringTransactionChanges();
  }

  static TransactionResult apply(final Transaction transaction) {

    synchronized(Transaction.BALANCE_LOCK) {
      final List<Change> changes = new ArrayList<>();
      if(!prepare(transaction, changes)) {
        return new TransactionResult(false, FAILED);
      }
      return BalanceHandler.afterTransaction(() -> writeChanges(transaction, changes));
    }
  }

  private static boolean prepare(final Transaction transaction, final List<Change> changes) {

    try {
      return (transaction.getFrom() != null || transaction.getTo() != null)
             && plan(changes, transaction.getFrom(), transaction.getModifierFrom())
             && plan(changes, transaction.getTo(), transaction.getModifierTo());
    } catch(final RuntimeException failure) {
      PluginCore.log().error("Unable to read holdings for transaction " + transaction.getType(), failure, DebugLevel.OFF);
      return false;
    }
  }

  private static TransactionResult writeChanges(final Transaction transaction, final List<Change> changes) {

    int attempted = 0;
    try {
      for(final Change change : changes) {
        requireRegistered(change.account());
        // A handler may mutate its holdings before returning false or throwing.
        attempted++;
        if(!change.account().setHoldings(copy(change.after()), change.after().getHandler())) {
          throw new IllegalStateException("Holdings handler rejected update for " + change.account().getIdentifier());
        }
      }
    } catch(final RuntimeException failure) {
      return compensate(transaction, changes, attempted, failure);
    }

    final TransactionResult result = new TransactionResult(true, "");
    result.setReceipt(new Receipt(UUID.randomUUID(), System.currentTimeMillis(), transaction));
    return result;
  }

  private static boolean plan(final List<Change> changes, final TransactionParticipant participant, final HoldingsModifier modifier) {

    if(participant == null) {
      return true;
    }
    final Optional<Account> found = participant.asAccount();
    if(found.isEmpty() || modifier == null || participant.getStartingBalances().isEmpty() || participant.getEndingBalances().isEmpty()) {
      return false;
    }

    final Account account = found.get();
    final List<HoldingsEntry> current = account.getHoldings(modifier.getRegion(), modifier.getCurrency(), modifier.getType());
    final List<HoldingsEntry> starting = participant.getStartingBalances();
    return current.size() == starting.size() && starting.stream().allMatch(before -> hasAmount(current, before))
           && appendChanges(changes, participant, account);
  }

  private static boolean appendChanges(final List<Change> changes, final TransactionParticipant participant, final Account account) {

    for(final HoldingsEntry after : participant.getEndingBalances()) {
      final Optional<HoldingsEntry> before = holding(participant.getStartingBalances(), after);
      if(before.isEmpty()) {
        return false;
      }
      if(before.get().getAmount().compareTo(after.getAmount()) == 0) {
        continue;
      }
      if(changes.stream().anyMatch(existing -> existing.account() == account && sameHolding(existing.before(), after))) {
        return false;
      }
      changes.add(new Change(account, copy(before.get()), copy(after)));
    }
    return true;
  }

  private static TransactionResult compensate(final Transaction transaction, final List<Change> changes,
                                              final int attempted, final RuntimeException failure) {

    final Map<Integer, String> unresolved = new HashMap<>();
    for(int i = attempted - 1; i >= 0; i--) {
      try {
        restore(changes.get(i));
      } catch(final RuntimeException rollbackFailure) {
        if(failure != rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        unresolved.put(i, rollbackFailure.toString());
      }
    }

    if(unresolved.isEmpty()) {
      PluginCore.log().error("Transaction " + transaction.getType() + " failed and attempted holdings were restored",
                             failure, DebugLevel.OFF);
      return new TransactionResult(false, FAILED);
    }

    final UUID reference = UUID.randomUUID();
    writeRecovery(reference, transaction, changes, attempted, unresolved, failure);
    return new TransactionResult(false, "Transaction requires reconciliation by a server administrator before retrying. Reference " + reference);
  }

  private static void restore(final Change change) {

    final Account account = change.account();
    final HoldingsEntry before = change.before();
    requireRegistered(account);
    if(unchanged(change)) {
      return;
    }
    if(!account.setHoldings(copy(before), before.getHandler()) || !accountAmountMatches(account, before)) {
      throw new IllegalStateException("Restoring the starting balance failed");
    }
  }

  private static boolean unchanged(final Change change) {

    try {
      return accountAmountMatches(change.account(), change.before());
    } catch(final RuntimeException failure) {
      // A read failure leaves the outcome uncertain. Attempt restoration and verify it afterward.
      return false;
    }
  }

  private static void requireRegistered(final Account account) {

    if(TNECore.eco().account().findAccount(account.getIdentifier()).orElse(null) != account) {
      throw new IllegalStateException("Transaction account is no longer available: " + account.getIdentifier());
    }
  }

  private static boolean accountAmountMatches(final Account account, final HoldingsEntry expected) {

    return hasAmount(account.getHoldings(expected.getRegion(), expected.getCurrency(), expected.getHandler()), expected);
  }

  private static boolean hasAmount(final List<HoldingsEntry> entries, final HoldingsEntry expected) {

    return holding(entries, expected)
            .map(entry -> entry.getAmount().compareTo(expected.getAmount()) == 0)
            .orElse(false);
  }

  private static Optional<HoldingsEntry> holding(final List<HoldingsEntry> entries, final HoldingsEntry target) {

    return entries.stream().filter(entry -> sameHolding(entry, target)).findFirst();
  }

  private static boolean sameHolding(final HoldingsEntry first, final HoldingsEntry second) {

    return first.getRegion().equals(second.getRegion()) && first.getCurrency().equals(second.getCurrency())
           && first.getHandler().equals(second.getHandler());
  }

  private static HoldingsEntry copy(final HoldingsEntry entry) {

    return new HoldingsEntry(entry.getRegion(), entry.getCurrency(), entry.getAmount(), entry.getHandler());
  }

  private static void writeRecovery(final UUID reference, final Transaction transaction, final List<Change> changes,
                                    final int attempted, final Map<Integer, String> unresolved,
                                    final RuntimeException failure) {

    final StringBuilder report = new StringBuilder()
            .append("Transaction requires reconciliation: ").append(reference).append('\n')
            .append("Time: ").append(Instant.now()).append('\n')
            .append("Type: ").append(transaction.getType()).append('\n')
            .append("Failure: ").append(failure).append('\n')
            .append("The default processor discarded this transaction's Redis balance deltas.\n")
            .append("Compare current holdings and external effects with the starting amounts before making corrections.\n");
    for(int i = 0; i < changes.size(); i++) {
      final Change change = changes.get(i);
      final HoldingsEntry before = change.before();
      report.append("Account=").append(change.account().getIdentifier())
              .append(" Region=").append(before.getRegion())
              .append(" Currency=").append(before.getCurrency())
              .append(" Handler=").append(before.getHandler().asID())
              .append(" Starting=").append(before.getAmount().toPlainString())
              .append(" Planned=").append(change.after().getAmount().toPlainString())
              .append(" Compensation=").append(i >= attempted ? "not attempted" : unresolved.getOrDefault(i, "restored"))
              .append('\n');
    }
    try {
      final Path directory = Files.createDirectories(PluginCore.directory().toPath().resolve("failed-transactions"));
      Files.writeString(directory.resolve(reference + ".txt"), report, StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC);
    } catch(final IOException | RuntimeException reportFailure) {
      failure.addSuppressed(reportFailure);
    }
    PluginCore.log().error(report.toString(), failure, DebugLevel.OFF);
  }

  private record Change(Account account, HoldingsEntry before, HoldingsEntry after) {
  }
}
