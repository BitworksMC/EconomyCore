package net.tnemc.core.handlers.player;

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
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.currency.Currency;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.utils.HandlerResponse;

import java.util.Date;
import java.util.Optional;

/**
 * PlayerLeaveHandler
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class PlayerLeaveHandler {

  /**
   * Used to handle a PlayerLeaveEvent using the specified {@link PlayerProvider} class.
   *
   * @param provider The {@link PlayerProvider} associated with the platform event.
   *
   * @return True if the event should be cancelled, otherwise false.
   */
  public HandlerResponse handle(final PlayerProvider provider) {

    final HandlerResponse response = new HandlerResponse("", false);

    TNECore.eco().account().getLoading().remove(provider.identifier());
    TNECore.eco().account().getImporting().remove(provider.identifier());

    final Optional<Account> account = TNECore.eco().account().findAccount(provider.identifier());
    if(account.isPresent() && (account.get() instanceof PlayerAccount)) {

      syncItemHoldings(provider, account.get());

      ((PlayerAccount)account.get()).setLastOnline(new Date().getTime());
      account.get().clearAwayReceipts();
      TNECore.instance().saver().save(account.get());
    }
    return response;
  }

  /**
   * Copies the player's item balances from their inventories into the wallet, which is what gets
   * saved and what other threads read. Server thread only.
   */
  public static void syncItemHoldings(final PlayerProvider provider, final Account account) {

    final String region = TNECore.eco().region().getMode().region(provider);
    for(final Currency currency : TNECore.eco().currency().getCurrencies(region)) {

      if(currency.type().supportsItems()) {

        for(final HoldingsEntry entry : account.getHoldings(region, currency.getUid())) {

          account.getWallet().setHoldings(entry);
        }
      }
    }
  }
}