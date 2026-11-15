package net.tnemc.core.transaction.history;

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
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.Receipt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SortedHistory
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class SortedHistory {

  //Newest first. Not keyed by time: receipts stored with second precision share timestamps.
  protected final List<UUID> receipts = new ArrayList<>();

  private final UUID account;
  private long lastSort;

  public SortedHistory(final UUID account) {

    this.account = account;
    this.lastSort = -1;

    sort();
  }

  public void sort() {

    final long time = new Date().getTime();

    if(lastSort != -1 && time - lastSort < MainConfig.yaml().getLong("Core.Transactions.History.Refresh", 1200L)) {
      return;
    }

    final Optional<Account> acc = TNECore.eco().account().findAccount(account);

    if(acc.isPresent()) {
      receipts.clear();
      TransactionManager.receipts().getReceiptsByParticipant(acc.get().getIdentifier()).stream()
              .sorted(Comparator.comparingLong(Receipt::getTime).reversed())
              .limit(200)
              .forEach(receipt->receipts.add(receipt.getId()));
    }
    lastSort = new Date().getTime();
  }

  public List<UUID> getPage(int page) {

    sort();

    if(page < 1) page = 1;
    if(page > maxPages()) page = 1;

    final int start = (page - 1) * 5;
    return List.copyOf(receipts.subList(Math.min(start, receipts.size()), Math.min(start + 5, receipts.size())));
  }

  public int maxPages() {

    int max = receipts.size() / 5;

    if((receipts.size() % 5) > 0) {
      max += 1;
    }
    return max;
  }

  public List<UUID> getReceipts() {

    return receipts;
  }
}