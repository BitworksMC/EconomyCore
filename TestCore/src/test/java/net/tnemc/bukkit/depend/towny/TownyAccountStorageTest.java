package net.tnemc.bukkit.depend.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.TNEStorageProvider;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.NonPlayerAccount;
import net.tnemc.core.account.SharedAccount;
import net.tnemc.core.config.DataConfig;
import net.tnemc.core.currency.Currency;
import net.tnemc.core.io.storage.SaveService;
import net.tnemc.core.io.storage.datables.yaml.YAMLAccount;
import net.tnemc.core.io.yaml.YamlStorageManager;
import net.tnemc.core.manager.AccountManager;
import net.tnemc.core.manager.CurrencyManager;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.api.CallbackManager;
import net.tnemc.plugincore.core.compatibility.LogProvider;
import net.tnemc.plugincore.core.io.storage.StorageManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class TownyAccountStorageTest {

  private static final UUID CURRENCY = UUID.fromString("5ac12fb6-1521-44d8-9001-71c2c1de2af2");
  private static final UUID MEMBER = UUID.fromString("5ac12fb6-1521-44d8-9001-71c2c1de2af3");
  private static final SaveService SAVER = new SaveService();

  @Rule
  public final TemporaryFolder directory = new TemporaryFolder();

  private MockedStatic<PluginCore> plugin;
  private MockedStatic<TNECore> core;
  private MockedStatic<TownyAPI> towny;
  private MockedStatic<TownyEconomyHandler> townyEconomy;
  private MockedStatic<TownySettings> settings;
  private MockedStatic<DataConfig> config;
  private MockedStatic<EconomyManager> economySingleton;
  private Path accountFiles;
  private AccountManager accounts;
  private EconomyManager economy;
  private TNECore engine;
  private TownyAPI api;

  @Before
  public void setUp() throws Exception {

    plugin = mockStatic(PluginCore.class);
    core = mockStatic(TNECore.class);
    towny = mockStatic(TownyAPI.class);
    townyEconomy = mockStatic(TownyEconomyHandler.class);
    townyEconomy.when(()->TownyEconomyHandler.modifyNPCUUID(any(UUID.class)))
                .thenAnswer(call->call.getArgument(0));
    settings = mockStatic(TownySettings.class);
    config = mockStatic(DataConfig.class);
    economySingleton = mockStatic(EconomyManager.class);
    plugin.when(PluginCore::log).thenReturn(mock(LogProvider.class));
    plugin.when(PluginCore::callbacks).thenReturn(mock(CallbackManager.class));
    plugin.when(PluginCore::directory).thenReturn(directory.getRoot());
    accountFiles = directory.newFolder("accounts").toPath();
    // The Bukkit package build relocates Boosted YAML, whereas test-only builds do not.
    final var yaml = DataConfig.class.getMethod("yaml");
    config.when(()->yaml.invoke(null)).thenReturn(mock(yaml.getReturnType()));

    final CurrencyManager currencies = mock(CurrencyManager.class);
    when(currencies.find(CURRENCY.toString())).thenReturn(Optional.of(mock(Currency.class)));
    economy = mock(EconomyManager.class);
    when(economy.findID(anyString())).thenReturn(Optional.empty());
    economySingleton.when(EconomyManager::instance).thenReturn(economy);
    when(economy.currency()).thenReturn(currencies);
    engine = mock(TNECore.class);
    when(engine.saver()).thenReturn(SAVER);
    core.when(TNECore::instance).thenReturn(engine);
    core.when(TNECore::eco).thenReturn(economy);
    core.when(TNECore::yaml).thenReturn(new YamlStorageManager());

    api = mock(TownyAPI.class);
    towny.when(TownyAPI::getInstance).thenReturn(api);
    settings.when(TownySettings::getTownAccountPrefix).thenReturn("town-");
    settings.when(TownySettings::getNationAccountPrefix).thenReturn("nation-");
  }

  @After
  public void tearDown() {

    economySingleton.close();
    config.close();
    settings.close();
    townyEconomy.close();
    towny.close();
    core.close();
    plugin.close();
  }

  @Test
  public void fundedTownAndNationAccountsKeepTheirWalletsAcrossRepairAndRestart() throws IOException {

    final UUID oldTown = UUID.randomUUID();
    final UUID oldNation = UUID.randomUUID();
    final String townName = "H".repeat(27);
    final String bankName = "town-" + townName;
    final UUID town = registerTown(townName, UUID.randomUUID());
    final Town governmentTown = api.getTown(townName);
    when(api.getTowns()).thenReturn(List.of(governmentTown));
    final UUID nation = UUID.randomUUID();
    final UUID nationBank = UUID.randomUUID();
    townyEconomy.when(()->TownyEconomyHandler.modifyNPCUUID(nation)).thenReturn(nationBank);
    final Nation government = mock(Nation.class);
    when(government.getUUID()).thenReturn(nation);
    when(api.getNation("Islands")).thenReturn(government);
    when(api.getNation(nation)).thenReturn(government);
    seedAccount(oldTown, bankName, "town", "125.75");
    seedAccount(oldNation, "nation-Islands", "nation", "2473.50");
    restart();

    final Account loadedTown = accounts.findAccount(oldTown).orElseThrow();
    assertEquals(new BigDecimal("125.75"), balance(loadedTown));
    assertFalse(accounts.findAccount(town).isPresent());
    TownyHandler.synchronizeAccounts();

    assertSame(loadedTown, accounts.findAccount(town).orElseThrow());
    assertSame(loadedTown, accounts.findAccount(bankName).orElseThrow());
    assertFalse(Files.exists(accountFile(oldTown)));
    assertFalse(Files.exists(accountFile(oldNation)));
    assertStoredIdentity(town);
    assertStoredIdentity(nationBank);
    restart();

    assertFalse(accounts.findAccount(oldTown).isPresent());
    assertFalse(accounts.findAccount(oldNation).isPresent());
    final SharedAccount reloaded = (SharedAccount)accounts.findAccount(town).orElseThrow();
    assertEquals(new BigDecimal("125.75"), balance(reloaded));
    assertEquals(new BigDecimal("2473.50"), balance(accounts.findAccount(nationBank).orElseThrow()));
    assertEquals(123456789L, reloaded.getCreationDate());
    assertEquals("0421", reloaded.getPin());
    assertEquals(town, reloaded.getOwner());
    assertTrue(reloaded.getMembers().containsKey(MEMBER));
  }

  @Test
  public void restartRetainsWalletAndMarksInterruptedIdentityWriteForSave() throws IOException {

    final UUID oldTown = UUID.randomUUID();
    final UUID destination = registerTown("Harbor", UUID.randomUUID());
    seedAccount(oldTown, "town-Harbor", "town", "125.75");
    Files.move(accountFile(oldTown), accountFile(destination));
    restart();

    final Account loaded = accounts.findAccount(destination).orElseThrow();
    assertEquals(new BigDecimal("125.75"), balance(loaded));
    assertTrue(loaded.isDirty());
    assertFalse(accounts.findAccount(oldTown).isPresent());
  }

  @Test
  public void occupiedDestinationOnDiskLeavesBothWalletsIntact() throws IOException {

    final UUID oldTown = UUID.randomUUID();
    final UUID destination = registerTown("Harbor", UUID.randomUUID());
    seedAccount(oldTown, "town-Harbor", "town", "125.75");
    restart();
    seedAccount(destination, "Independent", "non-player", "11.50");

    TownyHandler.synchronizeAccounts();

    assertEquals(new BigDecimal("125.75"), balance(accounts.findAccount(oldTown).orElseThrow()));
    assertFalse(accounts.findAccount(destination).isPresent());
    restart();
    assertEquals(new BigDecimal("125.75"), balance(accounts.findAccount(oldTown).orElseThrow()));
    assertEquals(new BigDecimal("11.50"), balance(accounts.findAccount(destination).orElseThrow()));
  }

  @Test
  public void townyTypeSurvivesRekeyAndSaveWhenIntegrationIsUnavailableDuringLoad() throws IOException {

    final UUID oldTown = UUID.randomUUID();
    final UUID destination = registerTown("Harbor", UUID.randomUUID());
    seedAccount(oldTown, "town-Harbor", "town", "61.25");
    settings.when(TownySettings::getTownAccountPrefix).thenReturn(null);
    restart();
    final Account loaded = accounts.findAccount(oldTown).orElseThrow();
    assertEquals(NonPlayerAccount.class, loaded.getClass());
    assertEquals("town", loaded.type());

    settings.when(TownySettings::getTownAccountPrefix).thenReturn("town-");
    TownyHandler.synchronizeAccounts();
    final StorageManager storage = engine.storage();
    final YAMLAccount datable = (YAMLAccount)storage.getEngine().datables().get(Account.class);
    datable.store(storage.getConnector(), accounts.findAccount(destination).orElseThrow(), destination.toString());

    try(final var lines = Files.lines(accountFile(destination))) {
      assertTrue(lines.map(String::strip).anyMatch(line->line.equals("Type: town")));
    }
    restart();
    final Account reloaded = accounts.findAccount(destination).orElseThrow();
    assertEquals("town", reloaded.type());
    assertEquals(new BigDecimal("61.25"), balance(reloaded));
  }

  private UUID registerTown(final String name, final UUID identifier) {

    final Town town = mock(Town.class);
    when(town.getUUID()).thenReturn(identifier);
    when(town.getName()).thenReturn(name);
    when(api.getTown(name)).thenReturn(town);
    when(api.getTown(identifier)).thenReturn(town);
    return identifier;
  }

  private void restart() {

    accounts = new AccountManager();
    when(economy.account()).thenReturn(accounts);
    accounts.addDefaultStatuses();
    TownyHandler.addTypes();
    accounts.addDefaultTypes();
    final StorageManager storage = new StorageManager("yaml", new TNEStorageProvider(), null, null);
    when(engine.storage()).thenReturn(storage);
    storage.loadAll(Account.class, "");
  }

  private Path accountFile(final UUID identifier) {

    return accountFiles.resolve(identifier + ".yml");
  }

  private void assertStoredIdentity(final UUID identifier) throws IOException {

    try(final var lines = Files.lines(accountFile(identifier))) {
      assertTrue(lines.map(String::strip).anyMatch(line->line.startsWith("ID: ") && line.contains(identifier.toString())));
    }
  }

  private void seedAccount(final UUID identifier, final String name, final String type,
                           final String amount) throws IOException {

    Files.writeString(accountFile(identifier), """
            Info:
              ID: '%s'
              Name: '%s'
              Type: '%s'
              Status: normal
              CreationDate: 123456789
              Pin: '0421'
              Owner: '%s'
            Members:
              %s:
                deposit: true
            Holdings:
              fixture:
                world:
                  %s:
                    tne:VIRTUAL_HOLDINGS: '%s'
            """.formatted(identifier, name, type, identifier, MEMBER, CURRENCY, amount));
  }

  private BigDecimal balance(final Account account) {

    return account.getWallet().entryList().get(0).getAmount();
  }
}
