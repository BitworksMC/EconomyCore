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

import net.tnemc.core.currency.Denomination;
import net.tnemc.core.currency.item.ItemCurrency;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ItemCalculations
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class ItemCalculations<I> {

  /**
   * Used to calculate the holdings of the inventory materials present.
   *
   * @return The {@link BigDecimal} representation of the inventory materials balance value.
   */
  public BigDecimal calculateHoldings(final CalculationData<I> data) {

    BigDecimal holdings = BigDecimal.ZERO;

    for(final Map.Entry<BigDecimal, Denomination> entry : data.getDenominations().entrySet()) {
      final int amount = data.getInventoryMaterials().getOrDefault(entry.getKey(), 0);

      if(amount > 0) {
        holdings = holdings.add(entry.getKey().multiply(new BigDecimal(amount)));
      }
    }
    return holdings;
  }

  public void setItems(final CalculationData<I> data, final BigDecimal amount) {

    trySetItems(data, amount);
  }

  /**
   * Applies a denomination plan only when its debit, credit and change can be represented exactly.
   */
  public boolean trySetItems(final CalculationData<I> data, final BigDecimal amount) {

    final BigDecimal holdings = calculateHoldings(data);

    if(amount.signum() < 0) return false;
    if(holdings.compareTo(amount) == 0) return true;
    if(holdings.compareTo(amount) > 0) {
      return calculation(data, holdings.subtract(amount)).signum() == 0;
    }
    final Optional<Map<BigDecimal, Integer>> payout = exactPayout(data, amount.subtract(holdings));
    payout.ifPresent(counts -> executePayout(data, counts));
    return payout.isPresent();
  }

  /**
   * Used to calculate the materials that need to be removed when a player in an item-based economy
   * has money taken from their account.
   *
   * This is a rewritten version that fixes the change calculation bug where players were not
   * receiving the correct amount of change when paying with larger denominations.
   *
   * @param amountToRemove The amount to remove from the player's account.
   *
   * @return The {@link BigDecimal} representation of the leftover amount that couldn't be removed
   * because there's no more materials left to remove.
   */
  public BigDecimal calculation(final CalculationData<I> data, final BigDecimal amountToRemove) {

    PluginCore.log().debug("=== Starting Calculation ===", DebugLevel.DEVELOPER);
    PluginCore.log().debug("Amount to remove: " + amountToRemove.toPlainString(), DebugLevel.DEVELOPER);

    // Create a copy of inventory materials
    final TreeMap<BigDecimal, Integer> availableMaterials = new TreeMap<>(data.getInventoryMaterials());
    final DirectPayment payment = calculateDirectPayment(availableMaterials, amountToRemove);

    PluginCore.log().debug("After direct payment - paid: " + payment.paidAmount().toPlainString()
                           + ", Remaining: " + payment.remaining().toPlainString(), DebugLevel.DEVELOPER);

    // If more is still needed to pay, break down a larger denomination
    Map<BigDecimal, Integer> changeToGiveBack = Map.of();

    if(payment.remaining().compareTo(BigDecimal.ZERO) > 0) {
      final Optional<Map<BigDecimal, Integer>> breakdown = findBreakdown(data, availableMaterials, payment);
      if(breakdown.isEmpty()) {
        PluginCore.log().debug("Cannot pay the requested amount with exact change", DebugLevel.DEVELOPER);
        return payment.remaining();
      }
      changeToGiveBack = breakdown.get();
    }

    executeRemovals(data, payment.toRemove());
    executePayout(data, changeToGiveBack);

    PluginCore.log().debug("=== Calculation Completed ===", DebugLevel.DEVELOPER);
    return BigDecimal.ZERO;
  }

  private DirectPayment calculateDirectPayment(final TreeMap<BigDecimal, Integer> availableMaterials,
                                               final BigDecimal amountToRemove) {

    BigDecimal paidAmount = BigDecimal.ZERO;
    BigDecimal remaining = amountToRemove;
    final TreeMap<BigDecimal, Integer> toRemove = new TreeMap<>();

    for(final Map.Entry<BigDecimal, Integer> entry : availableMaterials.descendingMap().entrySet()) {
      final BigDecimal denomination = entry.getKey();
      if(denomination.compareTo(remaining) > 0) {
        continue;
      }

      final int toUse = remaining.divide(denomination, 0, RoundingMode.DOWN)
                           .min(BigDecimal.valueOf(entry.getValue())).intValueExact();
      if(toUse > 0) {
        toRemove.put(denomination, toUse);
        final BigDecimal value = denomination.multiply(new BigDecimal(toUse));
        paidAmount = paidAmount.add(value);
        remaining = remaining.subtract(value);
        PluginCore.log().debug("Using " + toUse + "x " + denomination.toPlainString()
                               + " = " + value.toPlainString(), DebugLevel.DEVELOPER);
      }
      if(remaining.compareTo(BigDecimal.ZERO) == 0) {
        break;
      }
    }
    return new DirectPayment(paidAmount, remaining, toRemove);
  }

  private Optional<Map<BigDecimal, Integer>> findBreakdown(final CalculationData<I> data,
                                                           final TreeMap<BigDecimal, Integer> availableMaterials,
                                                           final DirectPayment payment) {

    for(final Map.Entry<BigDecimal, Integer> entry : availableMaterials.tailMap(payment.remaining(), false).entrySet()) {
      final BigDecimal denomination = entry.getKey();
      final int alreadyUsed = payment.toRemove().getOrDefault(denomination, 0);
      if(entry.getValue() > alreadyUsed) {
        final BigDecimal change = denomination.subtract(payment.remaining());
        final Optional<Map<BigDecimal, Integer>> payout = exactPayout(data, change);
        if(payout.isPresent()) {
          PluginCore.log().debug("Breaking down 1x " + denomination.toPlainString()
                                 + ", change: " + change.toPlainString(), DebugLevel.DEVELOPER);
          payment.toRemove().put(denomination, alreadyUsed + 1);
          return payout;
        }
      }
    }
    return Optional.empty();
  }

  private void executeRemovals(final CalculationData<I> data,
                               final TreeMap<BigDecimal, Integer> toRemove) {

    for(final Map.Entry<BigDecimal, Integer> entry : toRemove.entrySet()) {
      final Denomination denomination = data.getDenominations().get(entry.getKey());
      PluginCore.log().debug("Removing " + entry.getValue() + "x " + entry.getKey().toPlainString(),
                             DebugLevel.DEVELOPER);
      data.removeMaterials(denomination, entry.getValue());
    }
  }

  private record DirectPayment(BigDecimal paidAmount, BigDecimal remaining,
                               TreeMap<BigDecimal, Integer> toRemove) { }

  /**
   * Used to exchange an amount to inventory items. This is mostly used for when a larger
   * denomination needs to be broken into smaller denominations for calculation purposes.
   *
   * @param amount The amount that the items should add up to.
   */
  public void provideMaterials(final CalculationData<I> data, final BigDecimal amount) {

    exactPayout(data, amount).ifPresent(counts -> executePayout(data, counts));
  }

  private Optional<Map<BigDecimal, Integer>> exactPayout(final CalculationData<I> data, final BigDecimal amount) {

    data.getCalculator().initialize(data.getCurrency(), data.getInventoryMaterials());
    final Map<BigDecimal, Integer> counts = data.getCalculator().breakdown(amount);
    BigDecimal value = BigDecimal.ZERO;
    for(final Map.Entry<BigDecimal, Integer> entry : counts.entrySet()) {
      if(entry.getValue() <= 0 || !data.getDenominations().containsKey(entry.getKey())) {
        return Optional.empty();
      }
      value = value.add(entry.getKey().multiply(BigDecimal.valueOf(entry.getValue())));
    }
    return value.compareTo(amount) == 0 ? Optional.of(counts) : Optional.empty();
  }

  private void executePayout(final CalculationData<I> data, final Map<BigDecimal, Integer> counts) {

    for(final Map.Entry<BigDecimal, Integer> entry : counts.entrySet()) {
      final int holding = data.getInventoryMaterials().getOrDefault(entry.getKey(), 0);
      data.getInventoryMaterials().put(entry.getKey(), holding + entry.getValue());
      data.provideMaterials(data.getDenominations().get(entry.getKey()), entry.getValue());
    }
  }

  /**
   * Used to remove tiers from the {@link CalculationData} being worked with.
   *
   * @param denom  The denom name in String form.
   * @param amount The amount of the material to remove from working materials.
   */
  public void removeMaterials(final CalculationData<I> data, final Denomination denom, final Integer amount) {

    data.removeMaterials(denom, amount);
  }

  /**
   * Platform hook used to place item payouts into nested containers (such as shulkers).
   *
   * @param left The stacks still left after normal insertion attempts.
   * @param inventory The target inventory object.
   * @param currency The item currency being processed.
   * @return The stacks that still could not be inserted.
   */
  public Collection<AbstractItemStack<Object>> tryInsertIntoContainers(final Collection<AbstractItemStack<Object>> left,
                                                                        final I inventory,
                                                                        final ItemCurrency currency) {

    return left;
  }
}
