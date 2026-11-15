package net.tnemc.core;

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

import net.tnemc.core.account.Account;
import net.tnemc.core.account.GeyserAccount;
import net.tnemc.core.account.NonPlayerAccount;
import net.tnemc.core.account.PlayerAccount;
import net.tnemc.core.account.SharedAccount;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.config.DataConfig;
import net.tnemc.core.io.storage.datables.sql.standard.SQLAccount;
import net.tnemc.core.io.storage.datables.sql.standard.SQLHoldings;
import net.tnemc.core.io.storage.datables.sql.standard.SQLReceipt;
import net.tnemc.core.io.storage.datables.yaml.YAMLAccount;
import net.tnemc.core.io.storage.datables.yaml.YAMLHoldings;
import net.tnemc.core.io.storage.datables.yaml.YAMLReceipt;
import net.tnemc.core.io.storage.dialect.TNEDialect;
import net.tnemc.core.io.storage.dialect.impl.MariaDialect;
import net.tnemc.core.io.storage.dialect.impl.MariaOutdatedDialect;
import net.tnemc.core.io.storage.dialect.impl.MySQLDialect;
import net.tnemc.core.io.storage.dialect.impl.MySQLRevampDialect;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import net.tnemc.plugincore.core.io.storage.Datable;
import net.tnemc.plugincore.core.io.storage.StorageConnector;
import net.tnemc.plugincore.core.io.storage.StorageEngine;
import net.tnemc.plugincore.core.io.storage.StorageProvider;
import net.tnemc.plugincore.core.io.storage.connect.SQLConnector;
import net.tnemc.plugincore.core.io.storage.connect.YAMLConnector;
import net.tnemc.plugincore.core.io.storage.engine.StandardSQL;
import net.tnemc.plugincore.core.io.storage.engine.flat.YAML;
import net.tnemc.plugincore.core.io.storage.engine.sql.MariaDB;
import net.tnemc.plugincore.core.io.storage.engine.sql.MySQL;
import net.tnemc.plugincore.core.io.storage.engine.sql.PostgreSQL;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TNEStorageProvider
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class TNEStorageProvider implements StorageProvider {

  private StorageEngine engine;
  private StorageConnector<?> connector;

  @Override
  public void initialize(final String engine) {

    final String prefix = DataConfig.yaml().getString("Data.Database.Prefix");
    configureEngine(engine, prefix, mariaDriverAvailable());
    registerDatables();
  }

  private boolean mariaDriverAvailable() {

    boolean maria = false;
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      maria = true;
    } catch(final Exception ignore) { }

    try {
      Class.forName("org.mariadb.jdbc.MariaDbDataSource");
      maria = true;
    } catch(final Exception ignore) { }
    return maria;
  }

  private void configureEngine(final String engine, final String prefix, final boolean maria) {

    switch(engine.toLowerCase()) {
      case "mysql" -> configureMySql(prefix, maria);
      case "maria", "mariadb" -> configureMaria(prefix, maria);
      case "maria-outdated" -> configureOutdatedMaria(prefix, maria);
      case "postgre" -> {
        this.engine = new PostgreSQL(new MySQLDialect(prefix));
        this.connector = new SQLConnector();
      }
      case "mysql-revamp" -> configureRevampedMySql(prefix, maria);
      default -> {
        this.engine = new YAML();
        this.connector = new YAMLConnector();
      }
    }
  }

  private void configureMySql(final String prefix, final boolean maria) {

    this.engine = maria? new MariaDB(prefix, new MariaDialect(prefix))
                       : new MySQL(prefix, new MySQLDialect(prefix));
    this.connector = new SQLConnector();
  }

  private void configureMaria(final String prefix, final boolean maria) {

    this.engine = maria? new MariaDB(prefix, new MariaDialect(prefix))
                       : new MySQL(prefix, new MariaDialect(prefix));
    this.connector = new SQLConnector();
  }

  private void configureOutdatedMaria(final String prefix, final boolean maria) {

    PluginCore.log().warning("Using outdated database! Please note: Official Support for this version of TNE is limited.", DebugLevel.OFF);
    this.engine = maria? new MariaDB(prefix, new MariaOutdatedDialect(prefix))
                       : new MySQL(prefix, new MariaOutdatedDialect(prefix));
    this.connector = new SQLConnector();
  }

  private void configureRevampedMySql(final String prefix, final boolean maria) {

    this.engine = maria? new MariaDB(prefix, new MySQLRevampDialect(prefix))
                       : new MySQL(prefix, new MySQLRevampDialect(prefix));
    this.connector = new SQLConnector();
  }

  private void registerDatables() {

    final Datable<Account> account = (this.engine instanceof StandardSQL)? new SQLAccount() : new YAMLAccount();
    final Datable<HoldingsEntry> entry = (this.engine instanceof StandardSQL)? new SQLHoldings() : new YAMLHoldings();
    final Datable<Receipt> receipt = (this.engine instanceof StandardSQL)? new SQLReceipt() : new YAMLReceipt();

    this.engine.datables().put(Account.class, account);
    this.engine.datables().put(NonPlayerAccount.class, account);
    this.engine.datables().put(SharedAccount.class, account);
    this.engine.datables().put(GeyserAccount.class, account);
    this.engine.datables().put(PlayerAccount.class, account);

    this.engine.datables().put(HoldingsEntry.class, entry);
    this.engine.datables().put(Receipt.class, receipt);
  }

  @Override
  public StorageConnector<?> connector() {

    return this.connector;
  }

  @Override
  public StorageEngine engine() {

    return this.engine;
  }

  @Override
  public void initialize() {

    if(connector instanceof final SQLConnector sql
       && this.engine instanceof final StandardSQL sqlEngine
       && sqlEngine.dialect() instanceof final TNEDialect tneDialect) {

      sql.executeUpdate(tneDialect.accountsTable(), new Object[]{});
      sql.executeUpdate(tneDialect.accountsNonPlayerTable(), new Object[]{});
      sql.executeUpdate(tneDialect.accountsPlayerTable(), new Object[]{});
      sql.executeUpdate(tneDialect.accountMembersTable(), new Object[]{});
      sql.executeUpdate(tneDialect.holdingsTable(), new Object[]{});
      sql.executeUpdate(tneDialect.receiptsTable(), new Object[]{});
      sql.executeUpdate(tneDialect.receiptsHoldingsTable(), new Object[]{});
      sql.executeUpdate(tneDialect.receiptsParticipantsTable(), new Object[]{});
      sql.executeUpdate(tneDialect.receiptsModifiersTable(), new Object[]{});

      if(!(this.engine instanceof PostgreSQL) && !(tneDialect instanceof MySQLRevampDialect)) {
        migrateReceiptKeys(sql, DataConfig.yaml().getString("Data.Database.Prefix"));
      }
    }
  }

  /**
   * Receipt tables used to be keyed on the receipt id alone, so only the first participant,
   * modifier and holdings row of each receipt was stored. This widens those keys on existing
   * databases; it does nothing once they are widened.
   */
  private void migrateReceiptKeys(final SQLConnector sql, final String prefix) {

    try(final Connection connection = sql.connection();
        final Statement statement = connection.createStatement()) {

      for(final String table : List.of(prefix + "receipts_participants", prefix + "receipts_modifiers")) {
        if(primaryKeyColumns(connection, table) == 1) {
          statement.executeUpdate("ALTER TABLE " + table + " DROP PRIMARY KEY, ADD PRIMARY KEY(uid, participant_type)");
          PluginCore.log().inform("Widened primary key of " + table + ".");
        }
      }

      final String holdings = prefix + "receipts_holdings";
      for(final String index : uidOnlyUniqueIndexes(connection, holdings)) {
        statement.executeUpdate("ALTER TABLE " + holdings + " DROP INDEX `" + index + "`, ADD UNIQUE KEY "
                                + "receipt_holding (uid, participant, ending, region, currency, holdings_type)");
        PluginCore.log().inform("Widened unique key of " + holdings + ".");
      }
    } catch(final SQLException e) {
      PluginCore.log().error("Failed to update receipt table keys; transaction history stays incomplete.",
                             e, DebugLevel.OFF);
    }
  }

  private int primaryKeyColumns(final Connection connection, final String table) throws SQLException {

    int columns = 0;
    try(final ResultSet keys = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), null, table)) {
      while(keys.next()) {
        columns++;
      }
    }
    return columns;
  }

  private List<String> uidOnlyUniqueIndexes(final Connection connection, final String table) throws SQLException {

    final Map<String, List<String>> indexes = new HashMap<>();
    try(final ResultSet info = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, true, false)) {
      while(info.next()) {
        final String name = info.getString("INDEX_NAME");
        if(name != null && !"PRIMARY".equalsIgnoreCase(name)) {
          indexes.computeIfAbsent(name, ignored->new ArrayList<>()).add(info.getString("COLUMN_NAME"));
        }
      }
    }
    return indexes.entrySet().stream()
            .filter(entry->entry.getValue().equals(List.of("uid")))
            .map(Map.Entry::getKey)
            .toList();
  }

  @Override
  public void storeAll(@NotNull final String identifier) {

    final Optional<Datable<?>> data = Optional.ofNullable(engine.datables().get(HoldingsEntry.class));

    //Our account storeAll requires no identifier, so we set it to null
    data.ifPresent(datable->datable.storeAll(connector, identifier));
  }

  @Override
  public void storeAll() {

    TNECore.instance().saver().saveAll();
  }

  @Override
  public void delete(@NotNull final String identifier) {


    final Optional<Datable<?>> data = Optional.ofNullable(engine.datables().get(Account.class));

    //Our account storeAll requires no identifier, so we set it to null
    data.ifPresent(datable->TNECore.instance().saver().run(()->datable.delete(connector, identifier)));

  }

  @Override
  public void purge() {

  }

  @Override
  public void reset() {

    TNECore.eco().clearCache();
  }

  @Override
  public void backup() {

  }
}
