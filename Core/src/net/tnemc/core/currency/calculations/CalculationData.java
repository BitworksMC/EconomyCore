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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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

    PluginCore.log().debug("ProvideMaterials start. player=" + player
                             + " denomWeight=" + denomination.weight()
                             + " addAmount=" + amount
                             + " trackedContainsBeforeInsert=" + contains
                             + " enderFill=" + currency.isEnderFill()
                             + " shulker=" + currency.shulker()
                             + " bundle=" + currency.bundle(),
                           DebugLevel.DEVELOPER);

    final AbstractItemStack<?> stack = TNECore.instance().denominationToStack((ItemDenomination)denomination).amount(amount);
    final Collection<AbstractItemStack<Object>> left = giveItemsRetry(Collections.singletonList((AbstractItemStack<Object>)stack), inventory);

    PluginCore.log().debug("ProvideMaterials after primary inventory give. player=" + player
                             + " leftStacks=" + left.size()
                             + " leftAmount=" + countAmount(left),
                           DebugLevel.DEVELOPER);


    final Optional<PlayerAccount> account = TNECore.eco().account().findPlayerAccount(player);
    if(!left.isEmpty() && account.isPresent()) {

      if(currency.isEnderFill()) {

        //PluginCore.log().debug("Ender Fill: " + contains, DebugLevel.DETAILED);
        //PluginCore.log().debug("Ender Fill: " + amount, DebugLevel.DETAILED);
        final Collection<AbstractItemStack<Object>> enderLeft = giveItemsRetry(left, account.get().getPlayer().get().inventory().getInventory(true));

        PluginCore.log().debug("ProvideMaterials after ender fill attempt. player=" + player
                                 + " enderLeftStacks=" + enderLeft.size()
                                 + " enderLeftAmount=" + countAmount(enderLeft),
                               DebugLevel.DEVELOPER);

        if(!enderLeft.isEmpty()) {
          PluginCore.log().debug("ProvideMaterials ender fill unresolved, attempting drop. player=" + player
                                   + " dropAmount=" + countAmount(enderLeft),
                                 DebugLevel.DEVELOPER);
          drop(enderLeft, account.get());
          if(!isFailedDrop()) {

            contains = contains - countAmount(enderLeft);
            PluginCore.log().debug("ProvideMaterials drop succeeded after ender fill. player=" + player
                                     + " newTrackedContains=" + contains,
                                   DebugLevel.DEVELOPER);
          } else {
            PluginCore.log().debug("ProvideMaterials drop failed after ender fill. player=" + player
                                     + " trackedContainsUnchanged=" + contains,
                                   DebugLevel.DEVELOPER);
          }
        } else {

          contains = contains - countAmount(left);

          PluginCore.log().debug("ProvideMaterials ender fill fully succeeded. player=" + player
                                   + " movedAmount=" + countAmount(left)
                                   + " newTrackedContains=" + contains,
                                 DebugLevel.DEVELOPER);

          final MessageData messageData = new MessageData("Messages.Money.EnderChest");
          account.get().getPlayer().ifPresent(player->player.message(messageData));
        }
      } else {
        PluginCore.log().debug("ProvideMaterials enderFill disabled, attempting drop from primary leftovers. player=" + player
                                 + " dropAmount=" + countAmount(left),
                               DebugLevel.DEVELOPER);
        drop(left, account.get());
        if(!isFailedDrop()) {

          contains = contains - countAmount(left);
          PluginCore.log().debug("ProvideMaterials drop succeeded from primary leftovers. player=" + player
                                   + " newTrackedContains=" + contains,
                                 DebugLevel.DEVELOPER);
        } else {
          PluginCore.log().debug("ProvideMaterials drop failed from primary leftovers. player=" + player
                                   + " trackedContainsUnchanged=" + contains,
                                 DebugLevel.DEVELOPER);
        }
      }
    } else if(!left.isEmpty()) {
      PluginCore.log().debug("ProvideMaterials has leftovers but no account present. player=" + player
                               + " leftAmount=" + countAmount(left)
                               + " trackedContainsUnchanged=" + contains,
                             DebugLevel.DEVELOPER);
    }

    //PluginCore.log().debug("Weight: " + denomination.weight() + " - Amount: " + amount, DebugLevel.DETAILED);

    inventoryMaterials.put(denomination.weight(), contains);

    PluginCore.log().debug("ProvideMaterials complete. player=" + player
                             + " denomWeight=" + denomination.weight()
                             + " finalTrackedContains=" + contains,
                           DebugLevel.DEVELOPER);
  }

  private <T> Collection<AbstractItemStack<Object>> giveItemsRetry(final Collection<AbstractItemStack<Object>> toGive, final T targetInventory) {

    Collection<AbstractItemStack<Object>> left = PluginCore.server().calculations().giveItems(toGive,
                                                                                               targetInventory,
                                                                                               currency.shulker(),
                                                                                               currency.bundle());

    PluginCore.log().debug("giveItemsRetry initial give. player=" + player
                             + " requestedStacks=" + toGive.size()
                             + " requestedAmount=" + countAmount(toGive)
                             + " leftStacks=" + left.size()
                             + " leftAmount=" + countAmount(left),
                           DebugLevel.DEVELOPER);

    if(left.isEmpty() || !currency.shulker()) {
      if(!currency.shulker() && !left.isEmpty()) {
        PluginCore.log().debug("giveItemsRetry skipping container pass because shulker support is disabled. player=" + player
                                 + " leftAmount=" + countAmount(left),
                               DebugLevel.DEVELOPER);
      }
      return left;
    }

    PluginCore.log().debug("Container pass start. Leftover=" + countAmount(left)
                             + " target=" + ((targetInventory == null)? "null" : targetInventory.getClass().getSimpleName()),
                           DebugLevel.DEVELOPER);

    final Collection<AbstractItemStack<Object>> remaining = ((ItemCalculations<Object>)TNECore.instance().itemCalculations()).tryInsertIntoContainers(left,
                                                                                                       targetInventory,
                                                                                                       currency);

    if(remaining.isEmpty()) {
      PluginCore.log().debug("Container pass inserted all leftovers.", DebugLevel.DEVELOPER);
    } else {
      PluginCore.log().debug("Container pass remaining after insert=" + countAmount(remaining)
                               + " stacks=" + remaining.size(), DebugLevel.DEVELOPER);
    }

    return remaining;
  }

  public void drop(final Collection<AbstractItemStack<Object>> toDrop, final PlayerAccount account) {

    failedDrop = false;

    PluginCore.log().debug("Drop start. player=" + player
                             + " stacks=" + toDrop.size()
                             + " amount=" + countAmount(toDrop),
                           DebugLevel.DEVELOPER);

    final CurrencyDropCallback currencyDrop = new CurrencyDropCallback(player, currency, toDrop);
    if(PluginCore.callbacks().call(currencyDrop)) {
      PluginCore.log().error("Cancelled currency drop through callback.", DebugLevel.STANDARD);
      failedDrop = true;
      PluginCore.log().debug("Drop cancelled by callback. player=" + player, DebugLevel.DEVELOPER);
      return;
    }

    failedDrop = PluginCore.server().calculations().drop(toDrop, player, true);
    PluginCore.log().debug("Drop result. player=" + player + " failedDrop=" + failedDrop, DebugLevel.DEVELOPER);
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
