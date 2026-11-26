package net.tnemc.core.channel;

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
import net.tnemc.core.transaction.Transaction;
import net.tnemc.core.transaction.TransactionResult;
import net.tnemc.core.utils.Identifier;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;

import java.io.DataInput;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Syncs balance changes between servers.
 *
 * <p>Each message carries the change (delta), not the resulting balance. Every server adds it to
 * its own current balance under the balance lock, so changes made on several servers at once all
 * count instead of overwriting each other. Each change has an ID and is applied at most once.</p>
 */
public final class BalanceHandler {

  private static final int REMEMBERED_CHANGES = 10_000;
  private static final ThreadLocal<Boolean> APPLYING_REMOTE = ThreadLocal.withInitial(()->false);
  private static final ThreadLocal<List<Change>> TRANSACTION_CHANGES = new ThreadLocal<>();
  private static final Map<UUID, Boolean> APPLIED = Collections.synchronizedMap(new LinkedHashMap<>() {
    @Override
    protected boolean removeEldestEntry(final Map.Entry<UUID, Boolean> eldest) {

      return size() > REMEMBERED_CHANGES;
    }
  });

  private BalanceHandler() {
  }

  /**
   * @return true while this thread applies a change received from another server; such a change
   * must not be sent back out.
   */
  public static boolean applyingRemote() {

    return APPLYING_REMOTE.get();
  }

  public static boolean deferringTransactionChanges() {

    return TRANSACTION_CHANGES.get() != null;
  }

  /**
   * Captures local balance deltas until the default transaction processor has accepted every leg.
   */
  public static TransactionResult afterTransaction(final Supplier<TransactionResult> apply) {

    if(deferringTransactionChanges()) {
      throw new IllegalStateException("A balance transaction is already processing on this thread");
    }
    final List<Change> pending = new ArrayList<>();
    TRANSACTION_CHANGES.set(pending);
    final TransactionResult result;
    try {
      result = apply.get();
    } finally {
      TRANSACTION_CHANGES.remove();
    }
    if(result.isSuccessful()) {
      for(final Change change : pending) {
        try {
          publish(change);
        } catch(final RuntimeException failure) {
          PluginCore.log().error("Unable to queue committed Redis balance delta " + change.id() + " for account "
                                 + change.account() + " (" + change.region() + ", " + change.currency() + ", "
                                 + change.handler().asID() + ", " + change.delta().toPlainString()
                                 + "). Reconcile this balance across servers.", failure, DebugLevel.OFF);
        }
      }
    }
    return result;
  }

  public static void send(final String identifier, final String name, final String region, final UUID currency,
                          final Identifier handler, final BigDecimal delta) {

    if(!TNECore.syncEnabled() || delta.signum() == 0) {
      return;
    }

    final Change change = new Change(UUID.randomUUID(), identifier, name, region, currency, handler, delta);
    final List<Change> pending = TRANSACTION_CHANGES.get();
    if(pending != null) {
      pending.add(change);
      return;
    }
    publish(change);
  }

  private static void publish(final Change change) {

    RedisSync.publish(RedisSync.BALANCE, out->{
      out.writeUTF(change.id().toString());
      out.writeUTF(change.account());
      out.writeUTF(change.name());
      out.writeUTF(change.region());
      out.writeUTF(change.currency().toString());
      out.writeUTF(change.handler().asID());
      out.writeUTF(change.delta().toPlainString());
    });
  }

  private record Change(UUID id, String account, String name, String region, UUID currency, Identifier handler,
                        BigDecimal delta) {
  }

  static void handle(final DataInput in) throws IOException {

    final UUID change = UUID.fromString(in.readUTF());
    final String accountID = in.readUTF();
    final String accountName = in.readUTF();
    final String region = in.readUTF();
    final UUID currency = UUID.fromString(in.readUTF());
    final Identifier handler = Identifier.fromID(in.readUTF());
    final BigDecimal delta = new BigDecimal(in.readUTF());

    if(APPLIED.containsKey(change)) {
      return;
    }

    try {
      if(TNECore.eco().currency().find(currency)
              .map(value->value.type().name().equals("virtual") && !value.type().supportsItems()).orElse(false)) {
        //Virtual balances have no player-thread state; serialise them directly without a tick delay.
        apply(change, accountID, accountName, region, currency, handler, delta);
        return;
      }
      //The reader advances its stream cursor only after the owning player thread applied the delta.
      TNECore.instance().runPlayerTask(UUID.fromString(accountID),
              ()->apply(change, accountID, accountName, region, currency, handler, delta)).get(20, TimeUnit.SECONDS);
    } catch(final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SyncApplyException(interrupted);
    } catch(final ExecutionException | TimeoutException | RuntimeException failure) {
      throw new SyncApplyException(failure);
    }
  }

  private static void apply(final UUID change, final String accountID, final String accountName, final String region, final UUID currency,
                            final Identifier handler, final BigDecimal delta) {

    synchronized(Transaction.BALANCE_LOCK) {
      if(APPLIED.containsKey(change)) {
        return;
      }
      Optional<Account> account = TNECore.eco().account().findAccount(accountID);
      if(account.isEmpty()) {
        account = TNECore.eco().account().createAccount(accountID, accountName).getAccount();
      }
      if(account.isEmpty()) {
        throw new IllegalStateException("Unable to create account for synced balance " + accountID);
      }

      final BigDecimal current = account.get().getHoldingsTotal(region, currency, handler);
      APPLYING_REMOTE.set(true);
      try {
        if(!account.get().setHoldings(new HoldingsEntry(region, currency, current.add(delta), handler), handler)) {
          throw new IllegalStateException("Unable to apply synced balance to " + accountID);
        }
        APPLIED.put(change, Boolean.TRUE);
      } finally {
        APPLYING_REMOTE.set(false);
      }
    }
  }

  /** Transient dispatch/application failures must reconnect and retry, rather than skip the entry. */
  static final class SyncApplyException extends RuntimeException {

    private SyncApplyException(final Exception cause) {

      super("Unable to apply Redis balance update", cause);
    }
  }
}
