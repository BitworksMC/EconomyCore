package net.tnemc.bukkit.depend.towny;


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

import net.tnemc.plugincore.PluginCore;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.Usage;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.orphan.OrphanCommand;

/**
 * TownyCommand
 *
 * @author creatorfromhell
 * @since 0.1.4.0
 */
public class TownyCommand implements OrphanCommand {

  @Subcommand({ "towny" })
  @Usage("")
  @Description("Used to realign old towny data with the new enhanced data for when you're using VaultUnlocked")
  @CommandPermission("tne.admin.towny")
  public void command(final BukkitCommandActor sender) {

    PluginCore.log().inform("Updating Towny accounts...");
    TownyHandler.synchronizeAccounts();

    PluginCore.log().inform("Finished updating Towny accounts!");
  }
}
