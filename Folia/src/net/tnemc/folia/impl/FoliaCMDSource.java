package net.tnemc.folia.impl;

import net.tnemc.plugincore.core.compatibility.CmdSource;
import net.tnemc.plugincore.core.compatibility.PlayerProvider;
import net.tnemc.plugincore.core.io.message.MessageData;
import net.tnemc.plugincore.core.io.message.MessageHandler;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.util.Optional;

/** Folia command source using native Paper audiences and the region-aware player provider. */
public final class FoliaCMDSource extends CmdSource<BukkitCommandActor> {

  public FoliaCMDSource(final BukkitCommandActor actor) {

    super(actor);
  }

  @Override
  public boolean isPlayer() {

    return actor.isPlayer();
  }

  @Override
  public Optional<PlayerProvider> player() {

    return actor.isPlayer()? Optional.of(new FoliaPlayerProvider(actor.asPlayer())) : Optional.empty();
  }

  @Override
  public void message(final MessageData data) {

    MessageHandler.translate(data, identifier().orElse(null), actor.sender());
  }
}
