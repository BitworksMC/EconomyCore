package net.tnemc.core.currency.calculations;

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

import net.tnemc.core.TNECore;
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.core.api.callback.currency.CurrencyDropCallback;
import net.tnemc.core.currency.Denomination;
import net.tnemc.core.currency.item.ItemCurrency;
import net.tnemc.core.currency.item.ItemDenomination;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import net.tnemc.plugincore.core.io.message.MessageData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * CalculationData is used to take the information for an
 * {@link net.tnemc.core.currency.item.ItemCurrency} and break it down in order to perform
 * Item-based calculations.
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class CalculationData<I> {

  private final Map<BigDecimal, Integer> inventoryMaterials = new HashMap<>();

  //Our Calculator
  private final MonetaryCalculation calculator = new MonetaryCalculation();

  private final I inventory;
  private final ItemCurrency currency;
  private final UUID player;
  private boolean dropped = false;
  private boolean failedDrop = false;

  public CalculationData(final ItemCurrency currency, final I inventory, final UUID player) {

    this.currency = currency;
    this.inventory = inventory;
    this.player = player;

    inventoryCounts();
  }

  public Map<BigDecimal, Integer> getInventoryMaterials() {

    return inventoryMaterials;
  }

  public TreeMap<BigDecimal, Denomination> getDenominations() {

    return currency.getDenominations();
  }

  public MonetaryCalculation getCalculator() {

    return calculator;
  }

  public ItemCurrency getCurrency() {

    return currency;
  }

  public void inventoryCounts() {

    for(final Map.Entry<BigDecimal, Denomination> entry : currency.getDenominations().entrySet()) {
      if(entry.getValue() instanceof final ItemDenomination denomination) {

        final AbstractItemStack<?> stack = TNECore.instance().denominationToStack(denomination);
        final int count = PluginCore.server().calculations().count((AbstractItemStack<Object>)stack, inventory, currency.shulker(), currency.bundle());

        if(count > 0) {
          inventoryMaterials.put(entry.getKey(), count);
        }
      }
    }
  }

  public void removeMaterials(final Denomination denomination, final Integer amount) {

    final AbstractItemStack<?> stack = TNECore.instance().denominationToStack((ItemDenomination)denomination);
    final int contains = inventoryMaterials.getOrDefault(denomination.weight(), 0);

    if(contains == amount) {
      inventoryMaterials.remove(denomination.weight());
      PluginCore.log().debug("CalculationData - removeMaterials - equals: Everything equals, remove all materials. Weight: " + denomination.weight(), DebugLevel.DEVELOPER);
      PluginCore.server().calculations().removeAll((AbstractItemStack<Object>)stack, inventory, currency.shulker(), currency.bundle());
      return;
    }

    final int left = contains - amount;
    PluginCore.log().debug("CalculationData - removeMaterials - left: " + left + "Weight: " + denomination.weight(), DebugLevel.DEVELOPER);
    inventoryMaterials.put(denomination.weight(), left);
    final AbstractItemStack<?> stackClone = stack.amount(amount);
    PluginCore.server().calculations().removeItem((AbstractItemStack<Object>)stackClone, inventory, currency.shulker(), currency.bundle());
  }

  public void provideMaterials(final Denomination denomination, final Integer amount) {

    int contains = (inventoryMaterials.getOrDefault(denomination.weight(), 0)) + amount;

    final AbstractItemStack<?> stack = TNECore.instance().denominationToStack((ItemDenomination)denomination).amount(amount);
    final Collection<AbstractItemStack<Object>> left = giveItemsRetry(Collections.singletonList((AbstractItemStack<Object>)stack), inventory);


    final Optional<PlayerAccount> account = TNECore.eco().account().findPlayerAccount(player);
    if(!left.isEmpty() && account.isPresent()) {

      if(currency.isEnderFill()) {

        //PluginCore.log().debug("Ender Fill: " + contains, DebugLevel.DETAILED);
        //PluginCore.log().debug("Ender Fill: " + amount, DebugLevel.DETAILED);
        final Collection<AbstractItemStack<Object>> enderLeft = giveItemsRetry(left, account.get().getPlayer().get().inventory().getInventory(true));

        if(!enderLeft.isEmpty()) {
          drop(enderLeft, account.get());
          if(!isFailedDrop()) {

            contains = contains - countAmount(enderLeft);
          }
        } else {

          contains = contains - countAmount(left);

          final MessageData messageData = new MessageData("Messages.Money.EnderChest");
          account.get().getPlayer().ifPresent(player->player.message(messageData));
        }
      } else {
        drop(left, account.get());
        if(!isFailedDrop()) {

          contains = contains - countAmount(left);
        }
      }
    }

    //PluginCore.log().debug("Weight: " + denomination.weight() + " - Amount: " + amount, DebugLevel.DETAILED);

    inventoryMaterials.put(denomination.weight(), contains);
  }

  private <T> Collection<AbstractItemStack<Object>> giveItemsRetry(final Collection<AbstractItemStack<Object>> toGive, final T targetInventory) {

    Collection<AbstractItemStack<Object>> left = PluginCore.server().calculations().giveItems(toGive,
                                                                                               targetInventory,
                                                                                               currency.shulker(),
                                                                                               currency.bundle());

    if(left.isEmpty() || !currency.shulker()) {
      return left;
    }

    final Collection<AbstractItemStack<Object>> remaining = new ArrayList<>();
    for(final AbstractItemStack<Object> stack : left) {

      int amountLeft = stack.amount();
      while(amountLeft > 0) {

        final int piece = Math.min(amountLeft, 64);
        final Collection<AbstractItemStack<Object>> pieceLeft = PluginCore.server().calculations().giveItems(Collections.singletonList(stack.amount(piece)),
                                                                                                                targetInventory,
                                                                                                                currency.shulker(),
                                                                                                                currency.bundle());
        if(!pieceLeft.isEmpty()) {
          remaining.addAll(pieceLeft);
        }
        amountLeft -= piece;
      }
    }

    return tryInsertIntoBukkitShulkers(remaining, targetInventory);
  }

  private <T> Collection<AbstractItemStack<Object>> tryInsertIntoBukkitShulkers(final Collection<AbstractItemStack<Object>> left, final T targetInventory) {

    if(left.isEmpty() || !currency.shulker()) {
      return left;
    }

    try {
      final Class<?> inventoryClass = Class.forName("org.bukkit.inventory.Inventory");
      if(!inventoryClass.isInstance(targetInventory)) {
        return left;
      }

      final Class<?> itemStackClass = Class.forName("org.bukkit.inventory.ItemStack");
      final Class<?> itemMetaClass = Class.forName("org.bukkit.inventory.meta.ItemMeta");
      final Class<?> blockStateClass = Class.forName("org.bukkit.block.BlockState");
      final Class<?> blockStateMetaClass = Class.forName("org.bukkit.inventory.meta.BlockStateMeta");
      final Class<?> containerClass = Class.forName("org.bukkit.block.Container");
      final Class<?> materialClass = Class.forName("org.bukkit.Material");

      final Method inventoryGetContents = inventoryClass.getMethod("getContents");
      final Method inventorySetItem = inventoryClass.getMethod("setItem", int.class, itemStackClass);

      final Method itemStackGetType = itemStackClass.getMethod("getType");
      final Method itemStackHasMeta = itemStackClass.getMethod("hasItemMeta");
      final Method itemStackGetMeta = itemStackClass.getMethod("getItemMeta");
      final Method itemStackSetMeta = itemStackClass.getMethod("setItemMeta", itemMetaClass);
      final Method itemStackGetAmount = itemStackClass.getMethod("getAmount");
      final Method itemStackSetAmount = itemStackClass.getMethod("setAmount", int.class);
      final Method itemStackGetMaxStack = itemStackClass.getMethod("getMaxStackSize");

      final Method blockStateMetaGetState = blockStateMetaClass.getMethod("getBlockState");
      final Method blockStateMetaSetState = blockStateMetaClass.getMethod("setBlockState", blockStateClass);
      final Method blockStateUpdate = blockStateClass.getMethod("update");
      final Method containerGetInventory = containerClass.getMethod("getInventory");

      final Method materialValueOf = materialClass.getMethod("valueOf", String.class);
      final Constructor<?> itemStackCtor = itemStackClass.getConstructor(materialClass, int.class);

      final Collection<AbstractItemStack<Object>> remaining = new ArrayList<>();

      final Object[] contents = (Object[])inventoryGetContents.invoke(targetInventory);
      for(final AbstractItemStack<Object> abstractStack : left) {

        int amountLeft = abstractStack.amount();
        final Object material;
        try {
          material = materialValueOf.invoke(null, abstractStack.material().toUpperCase(Locale.ROOT));
        } catch(final Exception ignored) {
          remaining.add(abstractStack);
          continue;
        }

        for(int slot = 0; slot < contents.length && amountLeft > 0; slot++) {

          final Object containerStack = contents[slot];
          if(containerStack == null) continue;

          final String typeName = itemStackGetType.invoke(containerStack).toString();
          if(!typeName.equals("SHULKER_BOX") && !typeName.endsWith("_SHULKER_BOX")) continue;
          if(!(Boolean)itemStackHasMeta.invoke(containerStack)) continue;

          final Object meta = itemStackGetMeta.invoke(containerStack);
          if(!blockStateMetaClass.isInstance(meta)) continue;

          final Object blockState = blockStateMetaGetState.invoke(meta);
          if(!containerClass.isInstance(blockState)) continue;

          final Object shulkerInventory = containerGetInventory.invoke(blockState);
          final Object[] shulkerContents = (Object[])inventoryGetContents.invoke(shulkerInventory);

          for(int s = 0; s < shulkerContents.length && amountLeft > 0; s++) {

            final Object shulkerStack = shulkerContents[s];
            if(shulkerStack == null) continue;
            if(!itemStackGetType.invoke(shulkerStack).equals(material)) continue;

            final int stackAmount = (Integer)itemStackGetAmount.invoke(shulkerStack);
            final int max = (Integer)itemStackGetMaxStack.invoke(shulkerStack);
            final int space = max - stackAmount;
            if(space <= 0) continue;

            final int toAdd = Math.min(space, amountLeft);
            itemStackSetAmount.invoke(shulkerStack, stackAmount + toAdd);
            inventorySetItem.invoke(shulkerInventory, s, shulkerStack);
            amountLeft -= toAdd;
          }

          for(int s = 0; s < shulkerContents.length && amountLeft > 0; s++) {

            if(shulkerContents[s] != null) continue;

            final int toAdd = Math.min(64, amountLeft);
            final Object newItem = itemStackCtor.newInstance(material, toAdd);
            inventorySetItem.invoke(shulkerInventory, s, newItem);
            amountLeft -= toAdd;
          }

          blockStateMetaSetState.invoke(meta, blockState);
          itemStackSetMeta.invoke(containerStack, meta);
          inventorySetItem.invoke(targetInventory, slot, containerStack);
          blockStateUpdate.invoke(blockState);
        }

        if(amountLeft > 0) {
          remaining.add(abstractStack.amount(amountLeft));
        }
      }

      return remaining;

    } catch(final Throwable throwable) {
      PluginCore.log().debug("Shulker insertion retry failed: " + throwable.getMessage(), DebugLevel.DEVELOPER);
      return left;
    }
  }

  public void drop(final Collection<AbstractItemStack<Object>> toDrop, final PlayerAccount account) {

    failedDrop = false;

    final CurrencyDropCallback currencyDrop = new CurrencyDropCallback(player, currency, toDrop);
    if(PluginCore.callbacks().call(currencyDrop)) {
      PluginCore.log().error("Cancelled currency drop through callback.", DebugLevel.STANDARD);
      failedDrop = true;
      return;
    }

    failedDrop = PluginCore.server().calculations().drop(toDrop, player, true);
    if(!failedDrop) {

      final MessageData messageData = new MessageData("Messages.Money.Dropped");
      account.getPlayer().ifPresent(player->player.message(messageData));

      dropped = true;
    }
  }

  private int countAmount(final Collection<AbstractItemStack<Object>> stacks) {

    return stacks.stream().mapToInt(AbstractItemStack::amount).sum();
  }

  public boolean isDropped() {

    return dropped;
  }

  public boolean isFailedDrop() {

    return failedDrop;
  }
}
