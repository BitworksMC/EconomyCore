package net.tnemc.core.channel;

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

import com.google.common.io.ByteArrayDataOutput;
import net.tnemc.core.config.DataConfig;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.channel.ChannelBytesWrapper;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;

import java.io.IOException;

/**
 * Shared security helpers for proxy sync messages.
 *
 * @author creatorfromhell
 * @since 0.1.4.3
 */
public final class ChannelSecurity {

  private static final String DEFAULT_TOKEN = "CHANGE_ME";
  private static boolean warnedInvalidConfig = false;

  private ChannelSecurity() {
  }

  public static void writeToken(final ByteArrayDataOutput out) {

    out.writeUTF(token());
  }

  public static boolean validate(final ChannelBytesWrapper wrapper, final String type) {

    final String expected = token();
    if(!isConfigured(expected)) {
      warnInvalidConfig();
      return false;
    }

    final String provided;
    try {
      provided = wrapper.readUTF();
    } catch(final IOException e) {
      PluginCore.log().error("Rejected " + type + " sync payload due to malformed security token.", e, DebugLevel.STANDARD);
      return false;
    }

    if(expected.equals(provided)) {
      return true;
    }

    PluginCore.log().error("Rejected " + type + " sync payload due to invalid security token.", DebugLevel.STANDARD);
    return false;
  }

  public static String token() {

    final String configured = DataConfig.yaml().getString("Data.Sync.Security.Token", "");
    if(configured == null) {
      return "";
    }
    return configured.trim();
  }

  private static boolean isConfigured(final String token) {

    return !token.isEmpty()
           && !token.equalsIgnoreCase("none")
           && !token.equalsIgnoreCase(DEFAULT_TOKEN);
  }

  private static void warnInvalidConfig() {

    if(warnedInvalidConfig) {
      return;
    }

    PluginCore.log().error("Data.Sync.Security.Token must be configured to a non-default value before cross-server sync is accepted.",
                           DebugLevel.OFF);
    warnedInvalidConfig = true;
  }
}
