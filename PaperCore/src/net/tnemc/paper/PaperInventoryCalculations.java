package net.tnemc.paper;

import net.tnemc.item.paper.PaperCalculationsProvider;
import net.tnemc.item.paper.PaperItemStack;
import org.bukkit.Bukkit;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Function;

public final class PaperInventoryCalculations extends PaperCalculationsProvider {

  @Override
  public int removeAll(final PaperItemStack stack, final Inventory inventory, final boolean useShulker, final boolean useBundles) {

    return edit(inventory, staged -> Integer.MAX_VALUE - remove(stack, staged, Integer.MAX_VALUE, useShulker, useBundles));
  }

  @Override
  public int removeItem(final PaperItemStack stack, final Inventory inventory, final boolean useShulker, final boolean useBundles) {

    return edit(inventory, staged -> remove(stack, staged, stack.amount(), useShulker, useBundles));
  }

  @Override
  public void takeItems(final Collection<PaperItemStack> items, final Inventory inventory,
                        final boolean useShulker, final boolean useBundles) {

    edit(inventory, staged -> {
      items.forEach(item -> remove(item, staged, item.amount(), useShulker, useBundles));
      return null;
    });
  }

  @Override
  public Collection<PaperItemStack> giveItems(final Collection<PaperItemStack> items, final Inventory inventory,
                                             final boolean useShulker, final boolean useBundles) {

    return edit(inventory, staged -> super.giveItems(items, staged, useShulker, useBundles));
  }

  private int remove(final PaperItemStack currency, final Inventory inventory, final int amount,
                     final boolean useShulker, final boolean useBundles) {

    if(amount < 0) {
      throw new IllegalArgumentException("Cannot remove a negative item amount");
    }
    int remaining = amount;
    final int storageSize = inventory.getStorageContents().length;
    for(int slot = 0; slot < storageSize && remaining > 0; slot++) {
      final ItemStack item = inventory.getItem(slot);
      if(item == null || item.getType().isAir()) {
        continue;
      }
      if(currency.provider().similar(currency, item)) {
        final int removed = Math.min(remaining, item.getAmount());
        remaining -= removed;
        item.setAmount(item.getAmount() - removed);
        inventory.setItem(slot, item.getAmount() == 0 ? null : item);
        continue;
      }
      remaining = removeContents(currency, item, remaining, useShulker, useBundles);
      inventory.setItem(slot, item);
    }
    return remaining;
  }

  private int removeContents(final PaperItemStack currency, final ItemStack item, final int amount,
                             final boolean useShulker, final boolean useBundles) {

    int remaining = amount;
    final ItemMeta meta = item.getItemMeta();
    if(useShulker && meta instanceof BlockStateMeta block && block.getBlockState() instanceof ShulkerBox shulker) {
      remaining = remove(currency, shulker.getInventory(), remaining, false, false);
      if(remaining != amount) {
        block.setBlockState(shulker);
        item.setItemMeta(block);
      }
    }
    if(useBundles && meta instanceof BundleMeta bundle) {
      remaining = removeBundle(currency, bundle, remaining);
      if(remaining != amount) {
        item.setItemMeta(bundle);
      }
    }
    return remaining;
  }

  private int removeBundle(final PaperItemStack currency, final BundleMeta bundle, final int amount) {

    int remaining = amount;
    final ArrayList<ItemStack> contents = new ArrayList<>(bundle.getItems());
    final Iterator<ItemStack> iterator = contents.iterator();
    while(iterator.hasNext() && remaining > 0) {
      final ItemStack item = iterator.next();
      if(currency.provider().similar(currency, item)) {
        final int removed = Math.min(remaining, item.getAmount());
        remaining -= removed;
        if(removed == item.getAmount()) {
          iterator.remove();
        } else {
          item.setAmount(item.getAmount() - removed);
        }
      }
    }
    bundle.setItems(contents);
    return remaining;
  }

  private <Result> Result edit(final Inventory inventory, final Function<Inventory, Result> operation) {

    final ItemStack[] contents = inventory.getStorageContents();
    final Inventory staged = contents.length % 9 == 0 ? Bukkit.createInventory(null, contents.length)
                                                     : Bukkit.createInventory(null, inventory.getType());
    if(staged.getStorageContents().length != contents.length) {
      throw new IllegalArgumentException("Unsupported inventory storage layout: " + inventory.getType());
    }
    staged.setMaxStackSize(inventory.getMaxStackSize());
    for(int slot = 0; slot < contents.length; slot++) {
      if(contents[slot] != null) {
        staged.setItem(slot, contents[slot].clone());
      }
    }
    final Result result = operation.apply(staged);
    inventory.setStorageContents(staged.getStorageContents());
    return result;
  }
}
