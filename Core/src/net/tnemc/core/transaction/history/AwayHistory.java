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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AwayHistory
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class AwayHistory {

  //Oldest first. Not keyed by time: receipts stored with second precision share timestamps.
  protected final List<UUID> receipts = new ArrayList<>();

  private final UUID account;

  public AwayHistory(final UUID account) {

    this.account = account;
  }

  public List<UUID> getReceipts() {

    return receipts;
  }

  public UUID getAccount() {

    return account;
  }

  public List<UUID> getPage(int page) {

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
}