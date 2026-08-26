package net.tnemc.core.command;

/*
 * The New Economy
 * Copyright (C) 2022 - 2025 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.currency.Currency;
import net.tnemc.plugincore.core.compatibility.CmdSource;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.util.Optional;
import java.util.UUID;

final class MoneyCommandSupport {

  private MoneyCommandSupport() {
  }

  static boolean currencyDenied(final CmdSource<?> sender, final String permission,
                                final String action, final Currency currency) {

    final Optional<PlayerProvider> player = sender.player();
    if(!EconomyManager.limitCurrency() || player.isEmpty() || player.get().hasPermission(permission)) {
      return false;
    }
    final MessageData data = new MessageData("Messages.Account.BlockedAction");
    data.addReplacement("$action", action);
    data.addReplacement("$currency", currency.getDisplay());
    sender.message(data);
    return true;
  }

  static String playerRegion(final Optional<PlayerProvider> player, final String region) {

    if(player.isPresent() && region.equalsIgnoreCase(TNECore.DEFAULT_WORLD)) {
      return player.get().world();
    }
    return region;
  }

  static UUID sourceID(final CmdSource<?> sender) {

    return sender.identifier().orElseGet(()->TNECore.instance().getServerAccount());
  }

  static void noPlayer(final CmdSource<?> sender) {

    final MessageData data = new MessageData("Messages.General.NoPlayer");
    data.addReplacement("$player", sender.name());
    sender.message(data);
  }
}
