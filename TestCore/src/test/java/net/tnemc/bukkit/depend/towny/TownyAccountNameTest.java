package net.tnemc.bukkit.depend.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.SharedAccount;
import net.tnemc.core.actions.response.AccountResponse;
import net.tnemc.core.manager.AccountManager;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.LogProvider;
import net.tnemc.plugincore.core.id.UUIDPair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TownyAccountNameTest {

  private MockedStatic<PluginCore> plugin;
  private MockedStatic<TNECore> core;
  private MockedStatic<TownyAPI> towny;
  private MockedStatic<TownyEconomyHandler> townyEconomy;
  private MockedStatic<TownySettings> settings;
  private AccountManager accounts;
  private TownyAPI api;

  @Before
  public void setUp() {
    plugin = mockStatic(PluginCore.class);
    core = mockStatic(TNECore.class);
    towny = mockStatic(TownyAPI.class);
    townyEconomy = mockStatic(TownyEconomyHandler.class);
    townyEconomy.when(()->TownyEconomyHandler.modifyNPCUUID(any(UUID.class)))
                .thenAnswer(call->call.getArgument(0));
    settings = mockStatic(TownySettings.class);
    final LogProvider log = mock(LogProvider.class);
    plugin.when(PluginCore::log).thenReturn(log);
    accounts = spy(new AccountManager());
    doReturn(AccountResponse.DELETED).when(accounts).deleteAccount(any(UUID.class));
    final EconomyManager economy = mock(EconomyManager.class);
    when(economy.account()).thenReturn(accounts);
    core.when(TNECore::eco).thenReturn(economy);
    api = mock(TownyAPI.class);
    towny.when(TownyAPI::getInstance).thenReturn(api);
    settings.when(TownySettings::getTownAccountPrefix).thenReturn("town-");
    settings.when(TownySettings::getNationAccountPrefix).thenReturn("nation-");
  }

  @After
  public void tearDown() {
    settings.close();
    townyEconomy.close();
    towny.close();
    core.close();
    plugin.close();
  }

  @Test
  public void townKeepsSuppliedUuidWhenNameLimitIncreases() {
    for(final int length : new int[]{ 15, 32, 64 }) {
      final String name = "T".repeat(length);
      final UUID id = UUID.randomUUID();
      final Town town = mock(Town.class);
      when(town.getUUID()).thenReturn(id);
      when(api.getTown(name)).thenReturn(town);
      settings.when(TownySettings::getMaxNameLength).thenReturn(length);

      final String bankName = bankName("town-", name);
      final TownAccount account = new TownAccount(id, bankName);
      assertEquals(id, account.getIdentifier());
      assertEquals(bankName, account.getName());
    }
  }

  @Test
  public void nationKeepsSuppliedUuidWhenNameLimitIncreases() {
    for(final int length : new int[]{ 15, 32, 64 }) {
      final String name = "N".repeat(length);
      final UUID id = UUID.randomUUID();
      final Nation nation = mock(Nation.class);
      when(nation.getUUID()).thenReturn(id);
      when(api.getNation(name)).thenReturn(nation);
      settings.when(TownySettings::getMaxNameLength).thenReturn(length);

      final String bankName = bankName("nation-", name);
      final NationAccount account = new NationAccount(id, bankName);
      assertEquals(id, account.getIdentifier());
      assertEquals(bankName, account.getName());
    }
  }

  @Test
  public void truncatedNamesCannotResolveToAnotherTownOrNation() {
    final Town shorterTown = mock(Town.class);
    final Nation shorterNation = mock(Nation.class);
    when(shorterTown.getUUID()).thenReturn(UUID.randomUUID());
    when(shorterNation.getUUID()).thenReturn(UUID.randomUUID());
    when(api.getTown("T".repeat(27))).thenReturn(shorterTown);
    when(api.getNation("N".repeat(25))).thenReturn(shorterNation);
    final UUID townId = UUID.randomUUID();
    final UUID nationId = UUID.randomUUID();

    assertEquals(townId, new TownAccount(townId, bankName("town-", "T".repeat(32))).getIdentifier());
    assertEquals(nationId, new NationAccount(nationId, bankName("nation-", "N".repeat(32))).getIdentifier());
  }

  @Test
  public void synchronizationKeepsTruncatedTownAndNationAccountsByUuid() {
    final UUID townId = UUID.randomUUID();
    final UUID nationId = UUID.randomUUID();
    final Town townObject = mock(Town.class);
    final Nation nationObject = mock(Nation.class);
    when(api.getTown(townId)).thenReturn(townObject);
    when(api.getNation(nationId)).thenReturn(nationObject);
    final Account town = store(townId, bankName("town-", "T".repeat(32)));
    final Account nation = store(nationId, bankName("nation-", "N".repeat(32)));

    TownyHandler.synchronizeAccounts();

    verify(accounts, never()).deleteAccount(any(UUID.class));
    assertSame(town, accounts.findAccount(townId).orElseThrow());
    assertSame(nation, accounts.findAccount(nationId).orElseThrow());
  }

  @Test
  public void synchronizationKeepsLegacyTruncatedAccountsWithCustomPrefixes() {
    settings.when(TownySettings::getTownAccountPrefix).thenReturn("city-");
    settings.when(TownySettings::getNationAccountPrefix).thenReturn("country-");
    final Town town = mock(Town.class);
    when(town.getName()).thenReturn("T".repeat(32));
    when(api.getTowns()).thenReturn(List.of(town));
    final Nation nation = mock(Nation.class);
    when(nation.getName()).thenReturn("N".repeat(32));
    when(api.getNations()).thenReturn(List.of(nation));
    store(UUID.randomUUID(), bankName("city-", town.getName()));
    store(UUID.randomUUID(), bankName("country-", nation.getName()));

    TownyHandler.synchronizeAccounts();

    verify(accounts, never()).deleteAccount(any(UUID.class));
    assertEquals(2, accounts.uuidProvider().pairs().size());
  }

  @Test
  public void synchronizationDoesNotGuessBetweenCollidingTruncatedNames() {
    final Town first = mock(Town.class);
    final Town second = mock(Town.class);
    when(first.getName()).thenReturn("T".repeat(31) + "A");
    when(second.getName()).thenReturn("T".repeat(31) + "B");
    when(api.getTowns()).thenReturn(List.of(first, second));
    final UUID id = UUID.randomUUID();
    final Account account = store(id, bankName("town-", first.getName()));

    TownyHandler.synchronizeAccounts();

    verify(accounts, never()).deleteAccount(any(UUID.class));
    assertSame(account, accounts.findAccount(id).orElseThrow());
  }

  @Test
  public void synchronizationStillRemovesGenuinelyStaleAccounts() {
    final UUID id = UUID.randomUUID();
    store(id, "town-Deleted");

    TownyHandler.synchronizeAccounts();

    verify(accounts).deleteAccount(id);
  }

  private Account store(final UUID id, final String name) {
    final Account account = new SharedAccount(id, name, null);
    accounts.getAccounts().put(id.toString(), account);
    accounts.uuidProvider().store(new UUIDPair(id, name));
    return account;
  }

  private String bankName(final String prefix, final String name) {
    final String fullName = prefix + name;
    return fullName.substring(0, Math.min(32, fullName.length()));
  }
}
