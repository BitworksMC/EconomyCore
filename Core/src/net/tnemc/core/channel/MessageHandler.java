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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;

import java.io.DataInput;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivers a message to a player who is online on another server.
 */
public final class MessageHandler {

  private MessageHandler() {
  }

  public static void send(final UUID identifier, final Component component) {

    if(!TNECore.syncEnabled()) {
      return;
    }

    final String message = MiniMessage.miniMessage().serialize(component);
    RedisSync.publish(RedisSync.MESSAGE, out->{
      out.writeUTF(identifier.toString());
      out.writeUTF(message);
    });
  }

  static void handle(final DataInput in) throws IOException {

    final UUID identifier = UUID.fromString(in.readUTF());
    final String message = in.readUTF();
    PluginCore.server().scheduler().createDelayedTask(()->{
      final Optional<PlayerAccount> player = TNECore.eco().account().findPlayerAccount(identifier);
      if(player.isPresent() && player.get().getPlayer().isPresent()) {
        player.get().getPlayer().get().message(message);
      }
    }, new ChoreTime(0), ChoreExecution.MAIN_THREAD);
  }
}
