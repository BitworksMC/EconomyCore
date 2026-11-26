package net.tnemc.bukkit.depend.towny;

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
import net.tnemc.core.config.DataConfig;
import net.tnemc.core.io.storage.AccountIdentifierMigration;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.id.UUIDPair;
import net.tnemc.plugincore.core.io.storage.connect.YAMLConnector;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.event.TownyLoadedDatabaseEvent;
import com.palmergames.bukkit.towny.object.Government;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.util.StringMgmt;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * TownyHandler
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class TownyHandler implements Listener {

  public static void addTypes() {

    TNECore.eco().account().addAccountType(TownAccount.class, new TownCheck().check());
    TNECore.eco().account().addAccountType(NationAccount.class, new NationCheck().check());
  }

  public static void synchronizeAccounts() {

    synchronizeAccounts(true);
  }

  @EventHandler
  public void afterTownyDatabaseLoad(final TownyLoadedDatabaseEvent event) {

    // Run before Towny's first economy read can create an empty destination account.
    repairLocalAccounts();
  }

  @EventHandler
  public void afterServerLoad(final ServerLoadEvent event) {

    if(Bukkit.getPluginManager().isPluginEnabled("Towny")) {
      repairLocalAccounts();
    }
  }

  private void repairLocalAccounts() {

    // A second JVM could still write the old key. SQL and Redis networks use the
    // explicit command during single-server maintenance.
    if(!DataConfig.yaml().getBoolean("Data.Sync.Enabled", false)
       && TNECore.instance().storage().getConnector() instanceof YAMLConnector) {
      synchronizeAccounts(false);
    }
  }

  private static void synchronizeAccounts(final boolean removeStale) {

    for(final UUIDPair pair : List.copyOf(TNECore.eco().account().uuidProvider().pairs().values())) {
      final String name = pair.getUsername();
      if(name.startsWith(TownySettings.getTownAccountPrefix())) {
        synchronizeTown(pair.getIdentifier(), name, removeStale);
      } else if(name.startsWith(TownySettings.getNationAccountPrefix())) {
        synchronizeNation(pair.getIdentifier(), name, removeStale);
      }
    }
  }

  private static void synchronizeTown(final UUID identifier, final String name, final boolean removeStale) {

    if(TownyAPI.getInstance().getTown(identifier) != null) {
      repairIdentifier(identifier, name, "town", TownyEconomyHandler.modifyNPCUUID(identifier));
      return;
    }
    final String prefix = TownySettings.getTownAccountPrefix();
    if(name.length() == 32 && isTruncatedBankName(name, prefix, TownyAPI.getInstance().getTowns())) {
      return;
    }
    final Town town = TownyAPI.getInstance().getTown(name.substring(prefix.length()));
    if(town == null) {
      if(removeStale) {
        removeStaleAccount("town", name, identifier);
      }
      return;
    }
    repairIdentifier(identifier, name, "town", TownyEconomyHandler.modifyNPCUUID(town.getUUID()));
  }

  private static void synchronizeNation(final UUID identifier, final String name, final boolean removeStale) {

    if(TownyAPI.getInstance().getNation(identifier) != null) {
      repairIdentifier(identifier, name, "nation", TownyEconomyHandler.modifyNPCUUID(identifier));
      return;
    }
    final String prefix = TownySettings.getNationAccountPrefix();
    if(name.length() == 32 && isTruncatedBankName(name, prefix, TownyAPI.getInstance().getNations())) {
      return;
    }
    final Nation nation = TownyAPI.getInstance().getNation(name.substring(prefix.length()));
    if(nation == null) {
      if(removeStale) {
        removeStaleAccount("nation", name, identifier);
      }
      return;
    }
    repairIdentifier(identifier, name, "nation", TownyEconomyHandler.modifyNPCUUID(nation.getUUID()));
  }

  private static boolean isTruncatedBankName(final String name, final String prefix,
                                             final Collection<? extends Government> governments) {

    // Match Towny's bank-name format without calling getAccount(), which can call back
    // into the economy provider. Never migrate an account based on an ambiguous prefix.
    return governments.stream().map(government->prefix + government.getName())
            .anyMatch(bankName->bankName.length() > 32 && name.equalsIgnoreCase(StringMgmt.trimMaxLength(bankName, 32)));
  }

  private static void removeStaleAccount(final String type, final String name, final UUID identifier) {

    PluginCore.log().inform("Removing stale Towny " + type + " account: " + name);
    TNECore.eco().account().deleteAccount(identifier);
  }

  private static void repairIdentifier(final UUID source, final String name, final String type, final UUID target) {

    if(source.equals(target)) {
      return;
    }

    if(AccountIdentifierMigration.moveAccount(TNECore.eco().account(), source, target, name)) {
      PluginCore.log().inform("Repaired Towny " + type + " account " + name + " (" + source + " -> " + target + ").");
    } else {
      PluginCore.log().warning("Kept Towny " + type + " account " + name + " at " + source
                               + " because it could not be moved safely to " + target + '.');
    }
  }
}
