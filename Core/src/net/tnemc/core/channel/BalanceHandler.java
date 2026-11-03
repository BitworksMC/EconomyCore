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
import net.tnemc.core.utils.Identifier;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;

import java.io.DataInput;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

  public static void send(final String identifier, final String name, final String region, final UUID currency,
                          final Identifier handler, final BigDecimal delta) {

    if(!TNECore.syncEnabled() || delta.signum() == 0) {
      return;
    }

    final String change = UUID.randomUUID().toString();
    RedisSync.publish(RedisSync.BALANCE, out->{
      out.writeUTF(change);
      out.writeUTF(identifier);
      out.writeUTF(name);
      out.writeUTF(region);
      out.writeUTF(currency.toString());
      out.writeUTF(handler.asID());
      out.writeUTF(delta.toPlainString());
    });
  }

  static void handle(final DataInput in) throws IOException {

    final UUID change = UUID.fromString(in.readUTF());
    final String accountID = in.readUTF();
    final String accountName = in.readUTF();
    final String region = in.readUTF();
    final UUID currency = UUID.fromString(in.readUTF());
    final Identifier handler = Identifier.fromID(in.readUTF());
    final BigDecimal delta = new BigDecimal(in.readUTF());

    if(APPLIED.put(change, Boolean.TRUE) != null) {
      return;
    }

    //Balances, and item balances in inventories, are only changed on the server thread.
    PluginCore.server().scheduler().createDelayedTask(()->apply(accountID, accountName, region, currency, handler, delta),
                                                      new ChoreTime(0), ChoreExecution.MAIN_THREAD);
  }

  private static void apply(final String accountID, final String accountName, final String region, final UUID currency,
                            final Identifier handler, final BigDecimal delta) {

    synchronized(Transaction.BALANCE_LOCK) {
      Optional<Account> account = TNECore.eco().account().findAccount(accountID);
      if(account.isEmpty()) {
        account = TNECore.eco().account().createAccount(accountID, accountName).getAccount();
      }
      if(account.isEmpty()) {
        return;
      }

      final BigDecimal current = account.get().getHoldingsTotal(region, currency, handler);
      APPLYING_REMOTE.set(true);
      try {
        account.get().setHoldings(new HoldingsEntry(region, currency, current.add(delta), handler), handler);
      } finally {
        APPLYING_REMOTE.set(false);
      }
    }
  }
}
