package net.tnemc.folia;
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

import net.tnemc.menu.folia.FoliaMenuHandler;
import net.tnemc.paper.PaperCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * FoliaCore
 *
 * @author creatorfromhell
 * @since 0.1.3.4
 */
public class FoliaCore extends PaperCore {

  public FoliaCore(final JavaPlugin plugin) {

    super(plugin);
  }

  @Override
  public void registerMenuHandler() {

    this.menuHandler = new FoliaMenuHandler(plugin, true);
  }

  @Override
  public boolean canAccessInventory(final UUID identifier) {

    final Player player = Bukkit.getPlayer(identifier);
    //Folia also reports ownership on its shutdown thread, once region ticking has halted.
    return player != null && Bukkit.isOwnedByCurrentRegion(player);
  }

  @Override
  public CompletableFuture<Void> snapshotOnlinePlayers(final Consumer<UUID> snapshot) {

    final List<CompletableFuture<Void>> pending = new ArrayList<>();
    for(final Player player : Bukkit.getOnlinePlayers()) {
      final CompletableFuture<Void> result = new CompletableFuture<>();
      pending.add(result);
      final Runnable task = ()->{
        try {
          snapshot.accept(player.getUniqueId());
          result.complete(null);
        } catch(final Throwable failure) {
          result.completeExceptionally(failure);
        }
      };
      if(Bukkit.isOwnedByCurrentRegion(player)) {
        task.run();
      } else {
        scheduleSnapshot(player, task, result);
      }
    }
    return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
  }

  private void scheduleSnapshot(final Player player, final Runnable task, final CompletableFuture<Void> result) {

    try {
      //Retired players are snapshotted by PlayerQuitEvent before their entity scheduler is retired.
      if(!player.getScheduler().execute(plugin, task, ()->result.complete(null), 1L)) {
        result.complete(null);
      }
    } catch(final RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }

  @Override
  public CompletableFuture<Void> runPlayerTask(final UUID identifier, final Runnable action) {

    final Player player = Bukkit.getPlayer(identifier);
    final CompletableFuture<Void> result = new CompletableFuture<>();
    final Runnable task = ()->{
      try {
        action.run();
        result.complete(null);
      } catch(final Throwable failure) {
        result.completeExceptionally(failure);
      }
    };
    try {
      if(player == null) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
      } else if(Bukkit.isOwnedByCurrentRegion(player)) {
        task.run();
      } else if(!player.getScheduler().execute(plugin, task,
                                              ()->result.completeExceptionally(new IllegalStateException("Player retired before task")), 1L)) {
        result.completeExceptionally(new IllegalStateException("Player scheduler retired"));
      }
    } catch(final RuntimeException failure) {
      result.completeExceptionally(failure);
    }
    return result;
  }
}
