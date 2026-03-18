package net.tnemc.bukkit;

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

import net.tnemc.core.currency.calculations.ItemCalculations;
import net.tnemc.core.currency.item.ItemCurrency;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Locale;

/**
 * BukkitItemCalculations
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class BukkitItemCalculations extends ItemCalculations<Inventory> {

  @Override
  public Collection<AbstractItemStack<Object>> tryInsertIntoContainers(final Collection<AbstractItemStack<Object>> left,
                                                                        final Inventory inventory,
                                                                        final ItemCurrency currency) {

    if(left.isEmpty() || !currency.shulker()) {
      PluginCore.log().debug("Shulker insert skip early. leftEmpty=" + left.isEmpty()
                               + " shulkerEnabled=" + currency.shulker(), DebugLevel.DEVELOPER);
      return left;
    }

    PluginCore.log().debug("Shulker insert start. inventoryType=" + inventory.getType()
                             + " inventorySize=" + inventory.getSize()
                             + " stackCount=" + left.size(), DebugLevel.DEVELOPER);

    final Collection<AbstractItemStack<Object>> remaining = new ArrayList<>();

    for(final AbstractItemStack<Object> abstractStack : left) {

      final ItemStack insertion = resolveInsertionStack(abstractStack);
      if(insertion == null) {
        PluginCore.log().debug("Shulker insert skip - unknown material: " + abstractStack.material(), DebugLevel.DEVELOPER);
        remaining.add(abstractStack);
        continue;
      }

      PluginCore.log().debug("Shulker insert processing stack. material=" + insertion.getType().name()
                               + " amount=" + abstractStack.amount()
                               + " hasMeta=" + insertion.hasItemMeta(), DebugLevel.DEVELOPER);

      int amountLeft = abstractStack.amount();
      final int originalAmount = amountLeft;
      final ItemStack[] contents = inventory.getContents();
      boolean sawShulker = false;
      int shulkerCount = 0;

      for(int slot = 0; slot < contents.length && amountLeft > 0; slot++) {

        final ItemStack containerStack = contents[slot];
        if(containerStack == null || !isShulkerBox(containerStack.getType())) {
          continue;
        }

        sawShulker = true;
        shulkerCount++;

        final ItemMeta itemMeta = containerStack.getItemMeta();
        if(!(itemMeta instanceof final BlockStateMeta blockStateMeta)) {
          continue;
        }

        final BlockState blockState = blockStateMeta.getBlockState();
        if(!(blockState instanceof final Container container)) {
          PluginCore.log().debug("Shulker insert slot=" + slot + " has BlockStateMeta but not a Container state.", DebugLevel.DEVELOPER);
          continue;
        }

        PluginCore.log().debug("Shulker insert attempting slot=" + slot
                                 + " shulkerInvSize=" + container.getInventory().getSize()
                                 + " amountLeftBefore=" + amountLeft, DebugLevel.DEVELOPER);

        amountLeft = addToInventory(container.getInventory(), insertion, amountLeft);

        PluginCore.log().debug("Shulker insert completed slot=" + slot
                                 + " amountLeftAfter=" + amountLeft, DebugLevel.DEVELOPER);

        blockStateMeta.setBlockState(blockState);
        containerStack.setItemMeta(blockStateMeta);
        inventory.setItem(slot, containerStack);
      }

      if(amountLeft > 0) {
        if(!sawShulker) {
          PluginCore.log().debug("Shulker insert found no shulker boxes in target inventory.", DebugLevel.DEVELOPER);
        } else {
          PluginCore.log().debug("Shulker insert left amount after scan: " + amountLeft
                                   + " material: " + insertion.getType().name()
                                   + " shulkerCount=" + shulkerCount, DebugLevel.DEVELOPER);
        }
        remaining.add(abstractStack.amount(amountLeft));
      } else {
        PluginCore.log().debug("Shulker insert success amount=" + originalAmount
                                 + " material=" + insertion.getType().name()
                                 + " shulkerCount=" + shulkerCount, DebugLevel.DEVELOPER);
      }
    }

    PluginCore.log().debug("Shulker insert done. remainingStacks=" + remaining.size()
                             + " remainingAmount=" + remaining.stream().mapToInt(AbstractItemStack::amount).sum(), DebugLevel.DEVELOPER);

    return remaining;
  }

  private Material resolveMaterial(final String materialName) {

    final Material direct = Material.matchMaterial(materialName);
    if(direct != null) {
      return direct;
    }

    final int namespace = materialName.indexOf(':');
    final String normalized = (namespace > -1)? materialName.substring(namespace + 1) : materialName;

    return Material.matchMaterial(normalized.toUpperCase(Locale.ROOT));
  }

  private boolean isShulkerBox(final Material material) {

    return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
  }

  private ItemStack resolveInsertionStack(final AbstractItemStack<Object> stack) {

    final Object cached = stack.cacheLocale();
    if(cached instanceof final ItemStack cachedStack) {
      final ItemStack copy = cachedStack.clone();
      copy.setAmount(1);
      return copy;
    }

    final Material material = resolveMaterial(stack.material());
    if(material == null) {
      return null;
    }
    return new ItemStack(material, 1);
  }

  private int addToInventory(final Inventory inventory, final ItemStack insertion, final int amount) {

    int amountLeft = amount;
    int pass = 0;

    PluginCore.log().debug("Shulker internal add start. material=" + insertion.getType().name()
                             + " amount=" + amount
                             + " invType=" + inventory.getType()
                             + " invSize=" + inventory.getSize(), DebugLevel.DEVELOPER);

    while(amountLeft > 0) {
      pass++;

      final int toTry = Math.min(insertion.getMaxStackSize(), amountLeft);
      final ItemStack toInsert = insertion.clone();
      toInsert.setAmount(toTry);

      final Map<Integer, ItemStack> overflow = inventory.addItem(toInsert);
      if(overflow.isEmpty()) {
        PluginCore.log().debug("Shulker internal add pass=" + pass + " inserted=" + toTry + " overflow=0", DebugLevel.DEVELOPER);
        amountLeft -= toTry;
        continue;
      }

      final int leftInOverflow = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
      final int inserted = toTry - leftInOverflow;
      PluginCore.log().debug("Shulker internal add pass=" + pass
                               + " tried=" + toTry
                               + " inserted=" + inserted
                               + " overflowAmount=" + leftInOverflow
                               + " overflowSlots=" + overflow.keySet(), DebugLevel.DEVELOPER);
      if(inserted <= 0) {
        break;
      }
      amountLeft -= inserted;
    }

    PluginCore.log().debug("Shulker internal add complete. left=" + amountLeft, DebugLevel.DEVELOPER);

    return amountLeft;
  }
}
