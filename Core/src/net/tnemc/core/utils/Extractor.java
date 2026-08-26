package net.tnemc.core.utils;

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
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.api.response.AccountAPIResponse;
import net.tnemc.core.currency.Currency;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Extractor
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class Extractor {

  public static boolean extract() {

    final File file = new File(PluginCore.directory(), "extracted.yml");
    archiveExistingExtraction(file);
    if(!createExtractionFile(file)) {
      return false;
    }

    final YamlDocument yaml = loadExtraction(file);
    if(yaml == null) {
      return false;
    }
    final int total = TNECore.eco().account().getAccounts().size();
    PluginCore.log().inform("Extracting " + total + " accounts...");
    yaml.set("Version", "0.1.2.0");
    writeAccounts(yaml, total);
    return saveExtraction(yaml);
  }

  private static void archiveExistingExtraction(final File file) {

    if(file.exists()) {
      final File directory = new File(PluginCore.directory(), "extracted");
      if(!directory.exists()) {
        directory.mkdir();
      }
      final String fileName = "extracted-" + (directory.listFiles().length + 1) + ".yml";
      file.renameTo(new File(directory, fileName));
    }
  }

  private static boolean createExtractionFile(final File file) {

    try {
      file.createNewFile();
      return true;
    } catch(final IOException e) {
      PluginCore.log().error("Failed to create extraction file.", e, DebugLevel.STANDARD);
      return false;
    }
  }

  private static YamlDocument loadExtraction(final File file) {

    try {
      return YamlDocument.create(file);
    } catch(final IOException e) {
      PluginCore.log().error("Failed load extraction file for writing.", e, DebugLevel.STANDARD);
      return null;
    }
  }

  private static void writeAccounts(final YamlDocument yaml, final int total) {

    final int frequency = (int)(total * 0.10);
    int number = 1;
    for(final Account account : TNECore.eco().account().getAccounts().values()) {
      for(final HoldingsEntry entry : account.getWallet().entryList()) {
        String username = account.getName();
        username = username.replaceAll("\\.", "!").replaceAll("\\-", "@")
                .replaceAll("\\_", "%");
        yaml.set("Accounts." + username + ".Balances." + entry.getRegion() + "."
                 + entry.getCurrency() + "." + entry.getHandler().asID(), entry.getAmount().toPlainString());
        yaml.set("Accounts." + username + ".id", account.getIdentifier().toString());
      }
      number++;
      logProgress("Extraction", number, frequency, total);
    }
  }

  private static boolean saveExtraction(final YamlDocument yaml) {

    try {
      yaml.save();
      PluginCore.log().inform("Extraction has completed!");
    } catch(final IOException e) {
      PluginCore.log().error("Failed to save extraction file.", e, DebugLevel.STANDARD);
      return false;
    }
    return true;
  }

  private static void logProgress(final String operation, final int number, final int frequency,
                                  final int total) {

    try {
      if(number % frequency == 0) {
        PluginCore.log().inform(operation + " Progress: " + ((number * 100) / total));
      }
    } catch(final Exception ignore) { }
  }

  public static boolean restore(@Nullable final Integer extraction) {
    PluginCore.log().inform("Starting up Restoration Worker...");
    final File file = extractionFile(extraction);
    if(!file.exists()) {
      PluginCore.log().inform("The extraction file doesn't exist.");
      return false;
    }

    final YamlDocument extracted;
    try {
      extracted = YamlDocument.create(file);
    } catch(final IOException e) {
      PluginCore.log().error("Failed load extraction file for writing.", e, DebugLevel.STANDARD);
      return false;
    }

    if(extracted.contains("Accounts")) {
      restoreAccounts(extracted);
    }
    PluginCore.log().inform("Stopping restoration worker....");
    return true;
  }

  private static File extractionFile(@Nullable final Integer extraction) {

    if(extraction != null && extraction > 0) {
      return new File(PluginCore.directory(), "extracted/extracted-" + extraction + ".yml");
    }
    return new File(PluginCore.directory(), "extracted.yml");
  }

  private static void restoreAccounts(final YamlDocument extracted) {

    final Set<Object> accounts = extracted.getSection("Accounts").getKeys();
    final int frequency = (int)(accounts.size() * 0.10);
    final boolean recode = extracted.contains("Version");
    final int[] number = { 1 };

    for(final Object nameObj : accounts) {
      restoreAccount(extracted, (String)nameObj, recode, number, frequency, accounts.size());
    }
    PluginCore.log().inform("Restoration has completed!");
  }

  private static void restoreAccount(final YamlDocument extracted, final String name, final boolean recode,
                                     final int[] number, final int frequency, final int total) {

    final String username = name.replaceAll("\\!", ".").replaceAll("\\@", "-").replaceAll("\\%", "_");
    final String id = extracted.getString("Accounts." + name + ".id");
    PluginCore.log().inform("Attempting to restore account: id" + id + ", name" + name);
    final AccountAPIResponse response = TNECore.eco().account().createAccount(id, username);
    if(!response.getResponse().success() || response.getAccount().isEmpty()) {
      PluginCore.log().inform("Couldn't create account for " + username + ". Skipping.");
    }

    final Set<Object> regions = extracted.getSection("Accounts." + name + ".Balances").getKeys();
    for(final Object regionObj : regions) {
      restoreRegion(extracted, response, name, (String)regionObj, recode, number, frequency, total);
    }
  }

  private static void restoreRegion(final YamlDocument extracted, final AccountAPIResponse response,
                                    final String name, final String region, final boolean recode,
                                    final int[] number, final int frequency, final int total) {

    final Set<Object> currencies = extracted.getSection("Accounts." + name + ".Balances." + region).getKeys();
    for(final Object currencyObj : currencies) {
      final String currency = (String)currencyObj;
      if(recode) {
        restoreRecodedCurrency(extracted, response, name, region, currency);
      } else {
        restoreLegacyCurrency(extracted, response, name, region, currency);
      }
      number[0]++;
      logProgress("Restoration", number[0], frequency, total);
    }
  }

  private static void restoreLegacyCurrency(final YamlDocument extracted, final AccountAPIResponse response,
                                            final String name, final String region, final String currency) {

    final String finalCurrency = currency.equalsIgnoreCase("default")
                                 ? TNECore.eco().currency().defaultCurrency(region).getIdentifier() : currency;
    final Optional<Currency> found = TNECore.eco().currency().find(finalCurrency);
    final BigDecimal amount = new BigDecimal(
            extracted.getString("Accounts." + name + ".Balances." + region + "." + currency));
    PluginCore.log().inform("Currency avail: " + found.isPresent());
    if(found.isPresent()) {
      PluginCore.log().inform("Set Balance to: " + amount.toPlainString());
      response.getAccount().get().setHoldings(new HoldingsEntry(region, found.get().getUid(), amount,
                                                                 EconomyManager.NORMAL));
    } else {
      PluginCore.log().inform("Use default currency");
      PluginCore.log().inform("Set Balance to: " + amount.toPlainString());
      response.getAccount().get().setHoldings(new HoldingsEntry(
              region, TNECore.eco().currency().defaultCurrency(region).getUid(), amount, EconomyManager.NORMAL));
    }
  }

  private static void restoreRecodedCurrency(final YamlDocument extracted, final AccountAPIResponse response,
                                             final String name, final String region, final String currency) {

    final Set<Object> types = extracted.getSection(
            "Accounts." + name + ".Balances." + region + "." + currency).getKeys();
    for(final Object typeObj : types) {
      final String type = (String)typeObj;
      final BigDecimal amount = new BigDecimal(extracted.getString(
              "Accounts." + name + ".Balances." + region + "." + currency + "." + type));
      response.getAccount().get().setHoldings(new HoldingsEntry(region, UUID.fromString(currency), amount,
                                                                 Identifier.fromID(type)));
    }
  }
}
