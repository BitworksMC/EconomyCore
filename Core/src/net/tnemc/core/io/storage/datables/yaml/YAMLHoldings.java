package net.tnemc.core.io.storage.datables.yaml;

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

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.CurrencyHoldings;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.RegionHoldings;
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.utils.Identifier;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import net.tnemc.plugincore.core.io.storage.Datable;
import net.tnemc.plugincore.core.io.storage.StorageConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * YAMLHoldings
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class YAMLHoldings implements Datable<HoldingsEntry> {

  /**
   * The class that is represented by the O parameter.
   *
   * @return The class that represents the parameter.
   */
  @Override
  public Class<? extends HoldingsEntry> clazz() {

    return HoldingsEntry.class;
  }

  /**
   * USed to purge the objects of this datable.
   *
   * @param connector The storage connector to use for this transaction.
   */
  @Override
  public void purge(final StorageConnector<?> connector) {
    //This isn't required, it'll be deleted with the account.
  }

  /**
   * Used to store this object.
   *
   * @param connector The storage connector to use for this transaction.
   * @param object    The object to be stored.
   */
  @Override
  public void store(final StorageConnector<?> connector, @NotNull final HoldingsEntry object, @Nullable final String identifier) {

    //check if our file is in use.
    final String file = "accounts/" + identifier + ".yml";
    while(TNECore.yaml().inUse(file)) {
      try {
        Thread.sleep(1000);
      } catch(final InterruptedException ignore) {
      }
    }

    TNECore.yaml().add(file);

    final File accFile = new File(PluginCore.directory(), file);
    if(!accFile.exists()) {
      try {
        accFile.createNewFile();
      } catch(final IOException ignore) {

        PluginCore.log().error("Issue creating account file. Account: " + identifier, DebugLevel.OFF);
        return;
      }
    }

    PluginCore.log().inform("Saving holdings for: " + identifier, DebugLevel.STANDARD);


    YamlDocument yaml = null;
    try {
      yaml = YamlDocument.create(accFile);
    } catch(final IOException ignore) {

      PluginCore.log().error("Issue loading account file. Account: " + identifier, DebugLevel.OFF);
      return;
    }

    yaml.set("Holdings." + MainConfig.yaml().getString("Core.Server.Name")
             + "." + object.getRegion() + "." + object.getCurrency().toString() + "."
             + object.getHandler().asID(), object.getAmount().toPlainString());

    PluginCore.log().debug("YAMLHoldings-store-Entry ID:" + identifier, DebugLevel.DEVELOPER);
    PluginCore.log().debug("YAMLHoldings-store-Entry Currency:" + object.getCurrency().toString(), DebugLevel.DEVELOPER);
    PluginCore.log().debug("YAMLHoldings-store-Entry AMT:" + object.getAmount().toPlainString(), DebugLevel.DEVELOPER);
    try {
      yaml.save();
      yaml = null;
    } catch(final IOException ignore) {
      PluginCore.log().error("Issue saving account holdings to file. Account: " + identifier, DebugLevel.OFF);
    }
    TNECore.yaml().remove(file);
  }

  /**
   * Used to store all objects of this type.
   *
   * @param connector The storage connector to use for this transaction.
   */
  @Override
  public void storeAll(final StorageConnector<?> connector, @Nullable final String identifier) {

    final Optional<Account> account = TNECore.eco().account().findAccount(identifier);
    if(account.isEmpty()) {
      return;
    }

    PluginCore.log().inform("Saving holdings for: " + identifier, DebugLevel.STANDARD);
    final String file = "accounts/" + identifier + ".yml";
    waitForFile(file);
    TNECore.yaml().add(file);

    final File accFile = new File(PluginCore.directory(), file);
    if(!ensureFile(accFile, identifier)) {
      return;
    }
    final YamlDocument yaml = loadDocument(accFile, identifier);
    if(yaml == null) {
      return;
    }
    writeHoldings(yaml, account.get());
    saveDocument(yaml, identifier);
    TNECore.yaml().remove(file);
  }

  private void waitForFile(final String file) {

    while(TNECore.yaml().inUse(file)) {
      try {
        Thread.sleep(1000);
      } catch(final InterruptedException ignore) { }
    }
  }

  private boolean ensureFile(final File accFile, final String identifier) {

    if(accFile.exists()) {
      return true;
    }
    try {
      accFile.createNewFile();
      return true;
    } catch(final IOException ignore) {
      PluginCore.log().error("Issue creating account file. Account: " + identifier);
      return false;
    }
  }

  private YamlDocument loadDocument(final File accFile, final String identifier) {

    try {
      return YamlDocument.create(accFile);
    } catch(final IOException ignore) {
      PluginCore.log().error("Issue loading account file. Account: " + identifier, DebugLevel.OFF);
      return null;
    }
  }

  private void writeHoldings(final YamlDocument yaml, final Account account) {

    for(final Map.Entry<String, RegionHoldings> region : account.getWallet().getHoldings().entrySet()) {
      for(final Map.Entry<UUID, CurrencyHoldings> currency : region.getValue().getHoldings().entrySet()) {
        for(final HoldingsEntry entry : account.getHoldings(region.getKey(), currency.getKey())) {
          yaml.set("Holdings." + MainConfig.yaml().getString("Core.Server.Name") + "."
                   + entry.getRegion() + "." + entry.getCurrency() + "." + entry.getHandler().asID(),
                   entry.getAmount().toPlainString());
        }
      }
    }
  }

  private void saveDocument(final YamlDocument yaml, final String identifier) {

    try {
      yaml.save();
    } catch(final IOException ignore) {
      PluginCore.log().error("Issue saving account holdings to file. Account: " + identifier, DebugLevel.OFF);
    }
  }

  @Override
  public void delete(final StorageConnector<?> connector, @NotNull final String identifier) {
    //nothing to see here
  }

  /**
   * Used to load this object.
   *
   * @param connector  The storage connector to use for this transaction.
   * @param identifier The identifier used to identify the object to load.
   *
   * @return The object to load.
   *
   * @throws UnsupportedOperationException as this method is not valid for holdings.
   */
  @Override
  public Optional<HoldingsEntry> load(final StorageConnector<?> connector, @NotNull final String identifier) {

    throw new UnsupportedOperationException("load for HoldingsEntry is not a supported operation.");
  }

  /**
   * Used to load all objects of this type.
   *
   * @param connector The storage connector to use for this transaction.
   *
   * @return A collection containing the objects loaded.
   */
  @Override
  public Collection<HoldingsEntry> loadAll(final StorageConnector<?> connector, @Nullable final String identifier) {

    final Collection<HoldingsEntry> holdings = new ArrayList<>();
    if(identifier == null) {
      return holdings;
    }
    final File accFile = new File(PluginCore.directory(), "accounts/" + identifier + ".yml");
    if(!accFile.exists()) {
      PluginCore.log().error("Null account file passed to YAMLAccount.load. Account: " + identifier, DebugLevel.OFF);
      return holdings;
    }

    try(final FileInputStream fis = new FileInputStream(accFile)) {
      final YamlDocument yaml = YamlDocument.create(fis);
      if(yaml != null && yaml.contains("Holdings")) {
        readServers(yaml, yaml.getSection("Holdings"), holdings);
      }
    } catch(final IOException ignore) {
      PluginCore.log().error("Issue loading account file. Account: " + identifier, DebugLevel.OFF);
    }
    return holdings;
  }

  private void readServers(final YamlDocument yaml, final Section main,
                           final Collection<HoldingsEntry> holdings) {

    for(final Object serverObj : main.getKeys()) {
      final String server = (String)serverObj;
      if(!main.contains(server) || !main.isSection(server)) {
        continue;
      }
      readRegions(yaml, main, holdings, server);
    }
  }

  private void readRegions(final YamlDocument yaml, final Section main,
                           final Collection<HoldingsEntry> holdings, final String server) {

    for(final Object regionObj : main.getSection(server).getKeys()) {
      final String region = (String)regionObj;
      final String path = server + "." + region;
      if(!main.contains(path) || !main.isSection(path)) {
        continue;
      }
      readCurrencies(yaml, main, holdings, server, region);
    }
  }

  private void readCurrencies(final YamlDocument yaml, final Section main,
                              final Collection<HoldingsEntry> holdings, final String server,
                              final String region) {

    final String regionPath = server + "." + region;
    for(final Object currencyObj : main.getSection(regionPath).getKeys()) {
      final String currency = (String)currencyObj;
      if(TNECore.eco().currency().find(currency).isEmpty()) {
        EconomyManager.invalidCurrencies().add(currency);
      }
      final String currencyPath = regionPath + "." + currency;
      if(!main.contains(currencyPath) || !main.isSection(currencyPath)) {
        continue;
      }
      readHandlers(yaml, main, holdings, server, region, currency);
    }
  }

  private void readHandlers(final YamlDocument yaml, final Section main,
                            final Collection<HoldingsEntry> holdings, final String server,
                            final String region, final String currency) {

    final String path = server + "." + region + "." + currency;
    for(final Object handlerObj : main.getSection(path).getKeys()) {
      final String handler = (String)handlerObj;
      final String amount = yaml.getString("Holdings." + path + "." + handler, "0.0");
      final HoldingsEntry entry = new HoldingsEntry(region, UUID.fromString(currency),
                                                    new BigDecimal(amount), Identifier.fromID(handler));
      PluginCore.log().debug("YAMLHoldings-loadAll-Entry ID:" + entry.getHandler(), DebugLevel.DEVELOPER);
      PluginCore.log().debug("YAMLHoldings-loadAll-Entry AMT:"
                             + entry.getAmount().toPlainString(), DebugLevel.DEVELOPER);
      holdings.add(entry);
    }
  }
}
