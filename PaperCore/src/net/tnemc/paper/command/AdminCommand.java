package net.tnemc.paper.command;

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

import dev.dejvokep.boostedyaml.YamlDocument;
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.AccountStatus;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.api.response.AccountAPIResponse;
import net.tnemc.core.command.BaseCommand;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.utils.Identifier;
import net.tnemc.core.utils.LegacyAccountRestorer;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;
import net.tnemc.plugincore.core.io.message.MessageData;
import net.tnemc.paper.impl.PaperCMDSource;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.Usage;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.help.Help;

import java.io.File;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AdminCommand
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
@Command({ "tne", "myeco", "ecomenu", "ecomin", "ecoadmin", "ecomanage", "theneweconomy" })
@Description("Admin.Main.Description")
public class AdminCommand {

  public static boolean restoreOld(@Nullable final Integer extraction) {

    return LegacyAccountRestorer.restore(extraction, AdminCommand::get);
  }

  protected static UUID get(final String name) {

    final OfflinePlayer player = Bukkit.getServer().getOfflinePlayerIfCached(name);
    if(player != null) {

      return player.getUniqueId();
    }
    return null;
  }

  //@DefaultFor({ "tne", "myeco", "ecomenu", "ecomin", "ecoadmin", "ecomanage", "theneweconomy" })
  @Subcommand({ "ecomenu", "menu", "myeco" })
  @Usage("Admin.MyEco.Arguments")
  @Description("Admin.MyEco.Description")
  @CommandPermission("tne.admin.menu")
  public void onMyEco(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onMyEco(new PaperCMDSource(sender));
  }

  @Subcommand({ "help", "?" })
  @Usage("Help.Arguments")
  @Description("Help.Description")
  public void help(final BukkitCommandActor actor, final Help.RelatedCommands<?> commands, @Default("1") final int page) {

    BaseCommand.help(new PaperCMDSource(actor), commands, page);
  }

  @Subcommand({ "backup", "archive" })
  @Usage("Admin.Backup.Arguments")
  @Description("Admin.Backup.Description")
  @CommandPermission("tne.admin.backup")
  public void backup(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onBackup(new PaperCMDSource(sender));
  }

  @Subcommand({ "create", "add", "new", "make", "+" })
  @Usage("Admin.Create.Arguments")
  @Description("Admin.Create.Description")
  @CommandPermission("tne.admin.create")
  public void create(final BukkitCommandActor sender, final String name) {

    net.tnemc.core.command.AdminCommand.onCreate(new PaperCMDSource(sender), name);
  }

  @Subcommand({ "debug" })
  @Usage("Admin.Debug.Arguments")
  @Description("Admin.Debug.Description")
  @CommandPermission("tne.admin.debug")
  public void debug(final BukkitCommandActor sender, final DebugLevel level) {

    net.tnemc.core.command.AdminCommand.onDebug(new PaperCMDSource(sender), level);
  }

  @Subcommand({ "delete", "destroy", "del", "remove", "-" })
  @Usage("Admin.Delete.Arguments")
  @Description("Admin.Delete.Description")
  @CommandPermission("tne.admin.delete")
  public void delete(final BukkitCommandActor sender, final String name) {

    net.tnemc.core.command.AdminCommand.onDelete(new PaperCMDSource(sender), name);
  }

  @Subcommand({ "extract" })
  @Usage("Admin.Extract.Arguments")
  @Description("Admin.Extract.Description")
  @CommandPermission("tne.admin.extract")
  public void extract(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onExtract(new PaperCMDSource(sender));
  }

  @Subcommand({ "purge" })
  @Usage("Admin.Purge.Arguments")
  @Description("Admin.Purge.Description")
  @CommandPermission("tne.admin.purge")
  public void purge(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onPurge(new PaperCMDSource(sender));
  }

  @Subcommand({ "reload" })
  @Usage("Admin.Reload.Arguments")
  @Description("Admin.Reload.Description")
  @CommandPermission("tne.admin.reload")
  public void reload(final BukkitCommandActor sender, @Default("all") final String type) {

    net.tnemc.core.command.AdminCommand.onReload(new PaperCMDSource(sender), type);
  }

  @Subcommand({ "reloaddb" })
  @Usage("Admin.ReloadDB.Arguments")
  @Description("Admin.ReloadDB.Description")
  @CommandPermission("tne.admin.reloaddb")
  public void reloadDB(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onReloadDB(new PaperCMDSource(sender));
  }

  @Subcommand({ "reset", "nuke" })
  @Usage("Admin.Reset.Arguments")
  @Description("Admin.Reset.Description")
  @CommandPermission("tne.admin.reset")
  public void reset(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onReset(new PaperCMDSource(sender));
  }

  @Subcommand({ "restore" })
  @Usage("Admin.Restore.Arguments")
  @Description("Admin.Restore.Description")
  @CommandPermission("tne.admin.restore")
  public void restore(final BukkitCommandActor sender, @Default("0") final int extraction) {

    net.tnemc.core.command.AdminCommand.onRestore(new PaperCMDSource(sender), extraction);
  }

  @Subcommand({ "old" })
  @Usage("Admin.Restore.Arguments")
  @Description("Admin.Restore.Description")
  @CommandPermission("tne.admin.old")
  public void old(final BukkitCommandActor sender, @Default("0") final int extraction) {

    PluginCore.server().scheduler().createDelayedTask(()->restoreOld(extraction), new ChoreTime(0), ChoreExecution.SECONDARY);
    new PaperCMDSource(sender).message(new MessageData("Messages.Admin.Restoration"));
  }

  @Subcommand({ "save" })
  @Usage("Admin.Save.Arguments")
  @Description("Admin.Save.Description")
  @CommandPermission("tne.admin.save")
  public void save(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onSave(new PaperCMDSource(sender));
  }

  @Subcommand({ "status" })
  @Usage("Admin.Status.Arguments")
  @Description("Admin.Status.Description")
  @CommandPermission("tne.admin.status")
  public void status(final BukkitCommandActor sender, final Account account, @Default("normal") final AccountStatus status) {

    net.tnemc.core.command.AdminCommand.onStatus(new PaperCMDSource(sender), account, status);
  }

  @Subcommand({ "towny" })
  @Usage("")
  @Description("Used to realign old towny data with the new enhanced data for when you're using VaultUnlocked")
  @CommandPermission("tne.admin.towny")
  public void towny(final BukkitCommandActor sender) {

  }

  @Subcommand({ "version", "ver", "build" })
  @Usage("Admin.Version.Arguments")
  @Description("Admin.Version.Description")
  @CommandPermission("tne.admin.version")
  public void version(final BukkitCommandActor sender) {

    net.tnemc.core.command.AdminCommand.onVersion(new PaperCMDSource(sender));
  }
}
