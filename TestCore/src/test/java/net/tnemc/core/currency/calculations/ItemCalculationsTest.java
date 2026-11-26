package net.tnemc.core.currency.calculations;

import net.tnemc.core.currency.Denomination;
import net.tnemc.core.currency.item.ItemCurrency;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.LogProvider;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ItemCalculationsTest {

  private final ItemCalculations<Object> calculations = new ItemCalculations<>();

  @Test public void anInexactCreditDoesNotDeliverPartialDenominations() {

    final CalculationData<Object> data = data(Map.of(), 4, 6);

    assertFalse(calculations.trySetItems(data, money(5)));
    assertTrue(data.getInventoryMaterials().isEmpty());
    verify(data, never()).provideMaterials(any(), any());
  }

  @Test public void aDebitThatCannotMakeChangeLeavesBothCoinsInTheInventory() {

    try(final MockedStatic<PluginCore> plugin = mockStatic(PluginCore.class)) {
      plugin.when(PluginCore::log).thenReturn(mock(LogProvider.class));
      final Map<BigDecimal, Integer> inventory = Map.of(money(4), 2);
      final CalculationData<Object> data = data(inventory, 4);

      assertFalse(calculations.trySetItems(data, money(1)));
      assertEquals(inventory, data.getInventoryMaterials());
      verify(data, never()).removeMaterials(any(), any());
      verify(data, never()).provideMaterials(any(), any());
    }
  }

  @Test public void anExactCreditUsesTheExistingPayoutPath() {

    final CalculationData<Object> data = data(Map.of(), 4, 6);
    final Map<BigDecimal, Denomination> weights = data.getDenominations();

    assertTrue(calculations.trySetItems(data, money(10)));
    verify(data).provideMaterials(weights.get(money(4)), 1);
    verify(data).provideMaterials(weights.get(money(6)), 1);
  }

  @Test public void debitSkipsAnInexactBreakAndUsesTheNextExactDenomination() {

    try(final MockedStatic<PluginCore> plugin = mockStatic(PluginCore.class)) {
      plugin.when(PluginCore::log).thenReturn(mock(LogProvider.class));
      final CalculationData<Object> data = data(Map.of(money(5), 1, money(7), 1), 3, 5, 7);
      final Map<BigDecimal, Denomination> weights = data.getDenominations();

      assertTrue(calculations.trySetItems(data, money(8)));
      verify(data).removeMaterials(weights.get(money(7)), 1);
      verify(data, never()).removeMaterials(eq(weights.get(money(5))), any());
      verify(data).provideMaterials(weights.get(money(3)), 1);
    }
  }

  private static BigDecimal money(final int amount) {

    return BigDecimal.valueOf(amount);
  }

  @SuppressWarnings("unchecked")
  private static CalculationData<Object> data(final Map<BigDecimal, Integer> inventory, final int... denominations) {

    final CalculationData<Object> data = mock(CalculationData.class);
    final ItemCurrency currency = mock(ItemCurrency.class);
    final TreeMap<BigDecimal, Denomination> weights = new TreeMap<>();
    for(final int amount : denominations) {
      weights.put(money(amount), mock(Denomination.class));
    }
    when(currency.getDenominations()).thenReturn(weights);
    when(data.getDenominations()).thenReturn(weights);
    when(data.getCurrency()).thenReturn(currency);
    when(data.getCalculator()).thenReturn(new MonetaryCalculation());
    when(data.getInventoryMaterials()).thenReturn(new HashMap<>(inventory));
    return data;
  }
}
