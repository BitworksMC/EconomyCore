package net.tnemc.core.utils;

/*
 * The New Economy
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import dev.dejvokep.boostedyaml.YamlDocument;
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.api.response.AccountAPIResponse;
import net.tnemc.core.currency.Currency;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Restores the pre-0.1.2 account extraction format for platform command adapters.
 */
public final class LegacyAccountRestorer {

  private LegacyAccountRestorer() { }

  public static boolean restore(@Nullable final Integer extraction,
                                final Function<String, UUID> identifierResolver) {

    final File file = new File(PluginCore.directory(), "extracted.yml");
    if(!file.exists()) {
      PluginCore.log().inform("The extraction file doesn't exist.", DebugLevel.OFF);
      return false;
    }

    final YamlDocument extracted = load(file);
    if(extracted == null) {
      PluginCore.log().inform("The extraction file doesn't exist.", DebugLevel.OFF);
      return false;
    }
    if(extracted.contains("Accounts")) {
      restoreAccounts(extracted, identifierResolver);
    }
    return true;
  }

  private static YamlDocument load(final File file) {

    try {
      return YamlDocument.create(file);
    } catch(final Exception e) {
      PluginCore.log().error("Failed load extraction file for writing.", e, DebugLevel.OFF);
      return null;
    }
  }

  private static void restoreAccounts(final YamlDocument extracted,
                                      final Function<String, UUID> identifierResolver) {

    final Set<Object> accounts = extracted.getSection("Accounts").getKeys();
    final int frequency = (int)(accounts.size() * 0.10);
    final boolean recode = extracted.contains("Version");
    final int[] number = { 1 };
    for(final Object nameObj : accounts) {
      restoreAccount(extracted, (String)nameObj, recode, identifierResolver, number, frequency,
                     accounts.size());
    }
    PluginCore.log().inform("Restoration has completed!", DebugLevel.OFF);
  }

  private static void restoreAccount(final YamlDocument extracted, final String name, final boolean recode,
                                     final Function<String, UUID> identifierResolver, final int[] number,
                                     final int frequency, final int total) {

    final String username = name.replaceAll("\\!", "\\.").replaceAll("\\@", "-").replaceAll("\\%", "_");
    UUID identifier = identifierResolver.apply(username);
    boolean nonPlayer = false;
    if(identifier == null) {
      nonPlayer = true;
      identifier = UUID.randomUUID();
    }

    final AccountAPIResponse response = TNECore.eco().account().createAccount(
            identifier.toString(), username, nonPlayer);
    if(response.getAccount().isEmpty()) {
      PluginCore.log().inform("Couldn't create account for " + username + ". Reason: "
                              + response.getResponse().response(), DebugLevel.OFF);
      return;
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
        restoreRecoded(extracted, response, name, region, currency);
      } else {
        restoreLegacy(extracted, response, name, region, currency);
      }
      number[0]++;
      logProgress(number[0], frequency, total);
    }
  }

  private static void restoreLegacy(final YamlDocument extracted, final AccountAPIResponse response,
                                    final String name, final String region, final String currency) {

    final String finalCurrency = currency.equalsIgnoreCase("default")
                                 ? TNECore.eco().currency().defaultCurrency().getIdentifier() : currency;
    final Optional<Currency> found = TNECore.eco().currency().find(finalCurrency);
    final Currency currencyObj = found.orElseGet(
            ()->TNECore.eco().currency().defaultCurrency(TNECore.eco().region().resolve(region)));
    final BigDecimal amount = new BigDecimal(
            extracted.getString("Accounts." + name + ".Balances." + region + "." + currency));
    response.getAccount().get().setHoldings(
            new HoldingsEntry(TNECore.eco().region().resolve(region), currencyObj.getUid(), amount,
                              EconomyManager.NORMAL),
            TNECore.eco().getFor(currencyObj, currencyObj.type()).get(0).identifier());
  }

  private static void restoreRecoded(final YamlDocument extracted, final AccountAPIResponse response,
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

  private static void logProgress(final int number, final int frequency, final int total) {

    try {
      if(number % frequency == 0) {
        PluginCore.log().inform("Restoration Progress: " + ((number * 100) / total), DebugLevel.OFF);
      }
    } catch(final Exception ignore) { }
  }
}
