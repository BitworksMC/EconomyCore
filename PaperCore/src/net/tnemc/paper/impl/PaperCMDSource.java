package net.tnemc.paper.impl;

/*
 * The New Economy
 * Copyright (C) 2022 - 2026 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import net.tnemc.plugincore.core.io.message.MessageData;
import net.tnemc.plugincore.core.io.message.MessageHandler;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

/**
 * Paper-native command source that sends Adventure components directly to Paper's audience.
 *
 * <p>The TNPC Paper command source creates and immediately closes a legacy Bukkit audience for
 * every message. That adapter no longer reliably delivers command replies on Paper 26.2.</p>
 */
public class PaperCMDSource extends net.tnemc.plugincore.paper.impl.PaperCMDSource {

  public PaperCMDSource(final BukkitCommandActor actor) {

    super(actor);
  }

  @Override
  public void message(final MessageData data) {

    MessageHandler.translate(data, identifier().orElse(null), actor.sender());
  }
}
