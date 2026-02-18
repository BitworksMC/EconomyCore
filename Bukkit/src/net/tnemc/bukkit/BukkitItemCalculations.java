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
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;

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
      return left;
    }

    final Collection<AbstractItemStack<Object>> remaining = new ArrayList<>();

    for(final AbstractItemStack<Object> abstractStack : left) {

      final Material material = Material.matchMaterial(abstractStack.material());
      if(material == null) {
        remaining.add(abstractStack);
        continue;
      }

      int amountLeft = abstractStack.amount();
      final ItemStack[] contents = inventory.getContents();

      for(int slot = 0; slot < contents.length && amountLeft > 0; slot++) {

        final ItemStack containerStack = contents[slot];
        if(containerStack == null || !isShulkerBox(containerStack.getType())) {
          continue;
        }

        final ItemMeta itemMeta = containerStack.getItemMeta();
        if(!(itemMeta instanceof final BlockStateMeta blockStateMeta)) {
          continue;
        }

        final BlockState blockState = blockStateMeta.getBlockState();
        if(!(blockState instanceof final Container container)) {
          continue;
        }

        amountLeft = addToInventory(container.getInventory(), material, amountLeft);

        blockStateMeta.setBlockState(blockState);
        containerStack.setItemMeta(blockStateMeta);
        inventory.setItem(slot, containerStack);
      }

      if(amountLeft > 0) {
        remaining.add(abstractStack.amount(amountLeft));
      }
    }

    return remaining;
  }

  private boolean isShulkerBox(final Material material) {

    return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
  }

  private int addToInventory(final Inventory inventory, final Material material, final int amount) {

    int amountLeft = amount;
    final ItemStack[] contents = inventory.getContents();

    for(int slot = 0; slot < contents.length && amountLeft > 0; slot++) {

      final ItemStack stack = contents[slot];
      if(stack == null || stack.getType() != material) {
        continue;
      }

      final int max = stack.getMaxStackSize();
      final int space = max - stack.getAmount();
      if(space <= 0) {
        continue;
      }

      final int toAdd = Math.min(space, amountLeft);
      stack.setAmount(stack.getAmount() + toAdd);
      inventory.setItem(slot, stack);
      amountLeft -= toAdd;
    }

    for(int slot = 0; slot < contents.length && amountLeft > 0; slot++) {

      if(contents[slot] != null) {
        continue;
      }

      final int toAdd = Math.min(material.getMaxStackSize(), amountLeft);
      inventory.setItem(slot, new ItemStack(material, toAdd));
      amountLeft -= toAdd;
    }

    return amountLeft;
  }
}
