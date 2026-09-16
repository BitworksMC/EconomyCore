package net.tnemc.core.hook.papi.impl.top;
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

import net.tnemc.core.TNECore;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.hook.papi.Placeholder;
import net.tnemc.core.manager.TopManager;
import net.tnemc.core.manager.top.TopPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Optional;

import static net.tnemc.core.EconomyManager.TOP_PER_PAGE;

/**
 * TopPosPlaceholder
 *
 * @author creatorfromhell
 * @since 0.1.4.0
 */
public class TopPosBalancePlaceholder implements Placeholder {

  /**
   * Retrieves the identifier associated with this symbol.
   *
   * @return The identifier as a string
   */
  @Override
  public String identifier() {

    return "tne_toppos_balance";
  }

  /**
   * Checks if the specified parameters are valid for this method.
   *
   * @param params The parameters to be checked
   *
   * @return True if the parameters are valid, false otherwise
   */
  @Override
  public boolean applies(final String[] params) {

    return params[0].equalsIgnoreCase("toppos") && params.length >= 4
           && params[2].equalsIgnoreCase("balance");
  }

  /**
   * Processes a request with the given account and parameters.
   *
   * @param account Optional account associated with the request
   * @param params  Required parameters for the request
   *
   * @return Nullable string response from the request process
   */
  @Override
  public @Nullable String onRequest(@Nullable final String account, @NotNull final String[] params) {

    final Optional<Currency> currency = TNECore.eco().currency().find(params[1]);
    if(currency.isEmpty()) {

      return null;
    }
    final int pos;
    try {
      pos = Integer.parseInt(params[3]);
    } catch(final Exception ignore) {
      return null;
    }

    final TopManager manager = TNECore.eco().getTopManager();
    if(pos < 1 || pos > manager.page(currency.get().getUid()) * TOP_PER_PAGE) {
      return null;
    }

    final TopPage<String> page = manager.page(manager.positionToPage(pos), currency.get().getUid());
    if(page == null) {
      return null;
    }

    final int pagePosition = (pos - 1) % TOP_PER_PAGE + 1;
    final BigDecimal balance = page.getValues().get(page.getAt(pagePosition));
    return balance == null ? null : balance.toPlainString();
  }
}
