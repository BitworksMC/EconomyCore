package net.tnemc.core.io.storage.datables.sql.standard;

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

import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.account.holdings.modify.HoldingsOperation;
import net.tnemc.core.actions.ActionSource;
import net.tnemc.core.actions.source.PluginSource;
import net.tnemc.core.config.DataConfig;
import net.tnemc.core.config.MainConfig;
import net.tnemc.core.io.storage.dialect.TNEDialect;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.TransactionParticipant;
import net.tnemc.core.utils.Identifier;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.log.DebugLevel;
import net.tnemc.plugincore.core.io.storage.Datable;
import net.tnemc.plugincore.core.io.storage.StorageConnector;
import net.tnemc.plugincore.core.io.storage.connect.SQLConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 *
 */
public class SQLReceipt implements Datable<Receipt> {

  /**
   * The class that is represented by the O parameter.
   *
   * @return The class that represents the parameter.
   */
  @Override
  public Class<? extends Receipt> clazz() {

    return Receipt.class;
  }

  /**
   * USed to purge the objects of this datable.
   *
   * @param connector The storage connector to use for this transaction.
   */
  @Override
  public void purge(final StorageConnector<?> connector) {

    if(connector instanceof final SQLConnector sql && sql.dialect() instanceof final TNEDialect tne) {
      sql.executeUpdate(tne.receiptPurge(DataConfig.yaml().getInt("Data.Purge.Transaction.Days")),
                        new Object[]{});
    }
  }

  /**
   * Used to store this object.
   *
   * @param connector The storage connector to use for this transaction.
   * @param receipt   The object to be stored.
   */
  @Override
  public void store(final StorageConnector<?> connector, @NotNull final Receipt receipt, @Nullable final String identifier) {

    if(connector instanceof final SQLConnector sql && sql.dialect() instanceof final TNEDialect tne) {
      store(sql, tne, List.of(receipt));
    }
  }

  /**
   * Stores receipts in one database transaction. Dirty flags are cleared before writing, so a
   * receipt changed meanwhile is saved again, and restored if the write fails.
   */
  private void store(final SQLConnector sql, final TNEDialect tne, final List<Receipt> receipts) {

    receipts.forEach(Receipt::clearDirty);
    try(final Connection connection = sql.connection();
        final PreparedStatement receiptStatement = connection.prepareStatement(tne.saveReceipt());
        final PreparedStatement participants = connection.prepareStatement(tne.saveParticipant());
        final PreparedStatement holdings = connection.prepareStatement(tne.saveReceiptHolding());
        final PreparedStatement modifiers = connection.prepareStatement(tne.saveModifier())) {

      connection.setAutoCommit(false);
      try {
        final String server = MainConfig.yaml().getString("Core.Server.Name");
        for(final Receipt receipt : receipts) {
          final String id = receipt.getId().toString();
          //API transactions may have no source.
          final ActionSource source = receipt.getSource() == null? new PluginSource("Unknown") : receipt.getSource();
          batch(receiptStatement, id, new java.sql.Timestamp(receipt.getTime()), receipt.getType(),
                source.name(), source.type(), receipt.isArchive(), receipt.isVoided(),
                receipt.isArchive(), receipt.isVoided());
          final Statements statements = new Statements(participants, holdings, modifiers, id, server);
          batchParticipant(statements, receipt.getFrom(), receipt.getModifierFrom(), "from");
          batchParticipant(statements, receipt.getTo(), receipt.getModifierTo(), "to");
        }
        //Receipts first: the other tables reference them.
        receiptStatement.executeBatch();
        participants.executeBatch();
        holdings.executeBatch();
        modifiers.executeBatch();
        connection.commit();
      } catch(final SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch(final SQLException | RuntimeException e) {
      if(receipts.size() > 1) {
        //One receipt the database rejects must not keep every other receipt unsaved.
        receipts.forEach(receipt->store(sql, tne, List.of(receipt)));
        return;
      }
      receipts.forEach(Receipt::markDirty);
      PluginCore.log().error("Failed to save transaction receipt " + receipts.get(0).getId()
                             + "; retrying on the next save.", e, DebugLevel.OFF);
    }
  }

  private record Statements(PreparedStatement participants, PreparedStatement holdings,
                            PreparedStatement modifiers, String receipt, String server) { }

  private void batchParticipant(final Statements statements, @Nullable final TransactionParticipant participant,
                                @Nullable final HoldingsModifier modifier, final String type) throws SQLException {

    if(participant == null || modifier == null) {
      return;
    }

    final String id = participant.getId().toString();
    batch(statements.participants(), statements.receipt(), id, type, participant.getTax());
    for(final HoldingsEntry entry : participant.getStartingBalances()) {
      batchHolding(statements, id, entry, false);
    }
    for(final HoldingsEntry entry : participant.getEndingBalances()) {
      batchHolding(statements, id, entry, true);
    }
    batch(statements.modifiers(), statements.receipt(), id, type, modifier.getOperation().name(), modifier.getRegion(),
          modifier.getCurrency().toString(), modifier.getModifier());
  }

  private void batchHolding(final Statements statements, final String participant, final HoldingsEntry entry,
                            final boolean ending) throws SQLException {

    batch(statements.holdings(), statements.receipt(), participant, ending, statements.server(), entry.getRegion(),
          entry.getCurrency().toString(), entry.getHandler().asID(), entry.getAmount());
  }

  private static void batch(final PreparedStatement statement, final Object... values) throws SQLException {

    for(int i = 0; i < values.length; i++) {
      statement.setObject(i + 1, values[i]);
    }
    statement.addBatch();
  }

  /**
   * Used to store all objects of this type.
   *
   * @param connector The storage connector to use for this transaction.
   */
  @Override
  public void storeAll(final StorageConnector<?> connector, @Nullable final String identifier) {

    if(connector instanceof final SQLConnector sql && sql.dialect() instanceof final TNEDialect tne) {
      final List<Receipt> dirty = TransactionManager.receipts().getReceipts().values().stream()
              .filter(Receipt::isDirty)
              .toList();
      if(!dirty.isEmpty()) {
        store(sql, tne, dirty);
      }
    }
  }

  @Override
  public void delete(final StorageConnector<?> connector, @NotNull final String identifier) {
    //nothing to do here.
  }

  /**
   * Used to load this object.
   *
   * @param connector  The storage connector to use for this transaction.
   * @param identifier The identifier used to identify the object to load.
   *
   * @return The object to load.
   */
  @Override
  public Optional<Receipt> load(final StorageConnector<?> connector, @NotNull final String identifier) {

    //We shouldn't load individual Receipts, it doesn't make sense to me/no use case with caching. - creatorfromhell
    return Optional.empty();
  }

  /**
   * Loads a Receipt object from a ResultSet.
   *
   * @param result The ResultSet containing the data to load into a Receipt object.
   *
   * @return An Optional containing the loaded Receipt object. Returns an empty Optional if the
   * ResultSet is empty.
   *
   * @throws SQLException if an error occurs while accessing the ResultSet data.
   */
  public Receipt load(final ResultSet result, final SQLConnector sql, final TNEDialect dialect) throws SQLException {

    //SQL Columns: uid, performed, receipt_type, receipt_source, receipt_source_type, archive, voided
    final Receipt receipt = new Receipt(
            UUID.fromString(result.getString("uid")),
            result.getTimestamp("performed").getTime(),
            result.getString("receipt_type")
    );
    receipt.setSource(ActionSource.create(result.getString("receipt_source"), result.getString("receipt_source_type")));
    receipt.setArchive(result.getBoolean("archive"));
    receipt.setVoided(result.getBoolean("voided"));
    receipt.clearDirty();

    return receipt;
  }

  /**
   * Used to load all objects of this type.
   *
   * <p>Participants, modifiers and holdings are read with one query per table and joined in
   * memory, instead of several queries per receipt.</p>
   *
   * @param connector The storage connector to use for this transaction.
   *
   * @return A collection containing the objects loaded.
   */
  @Override
  public Collection<Receipt> loadAll(final StorageConnector<?> connector, @Nullable final String identifier) {

    final Map<UUID, Receipt> receipts = new HashMap<>();
    if(connector instanceof final SQLConnector sql && sql.dialect() instanceof final TNEDialect tne) {

      try(final ResultSet result = sql.executeQuery(tne.loadReceipts(), new Object[0])) {
        while(result != null && result.next()) {
          final Receipt receipt = load(result, sql, tne);
          receipts.put(receipt.getId(), receipt);
        }

        loadParticipants(receipts, sql, tne);
      } catch(final SQLException | RuntimeException e) {
        PluginCore.log().error("Failed to load transaction receipts.", e, DebugLevel.OFF);
      }

      for(final Receipt receipt : receipts.values()) {
        receipt.clearDirty();
        TransactionManager.receipts().log(receipt);
      }
    }
    return receipts.values();
  }

  private void loadParticipants(final Map<UUID, Receipt> receipts, final SQLConnector sql,
                                final TNEDialect dialect) throws SQLException {

    final Map<String, List<HoldingsEntry>> holdings = loadHoldings(sql, dialect);
    final Map<String, HoldingsModifier> modifiers = loadModifiers(sql, dialect);

    try(final ResultSet result = sql.executeQuery(dialect.loadParticipants(), new Object[0])) {
      while(result != null && result.next()) {

        final Receipt receipt = receipts.get(UUID.fromString(result.getString("uid")));
        if(receipt == null) {
          continue;
        }

        final String type = result.getString("participant_type").toLowerCase(Locale.ROOT);
        final String participantId = result.getString("participant");
        final String key = receipt.getId() + "|" + participantId;

        final TransactionParticipant participant = new TransactionParticipant(UUID.fromString(participantId),
                holdings.getOrDefault(key + "|false", new ArrayList<>()));
        participant.getEndingBalances().addAll(holdings.getOrDefault(key + "|true", List.of()));
        participant.setTax(result.getBigDecimal("tax"));

        final HoldingsModifier modifier = modifiers.get(receipt.getId() + "|" + type);
        if(type.equals("from")) {
          receipt.setFrom(participant);
          if(modifier != null) receipt.setModifierFrom(modifier);
        } else {
          receipt.setTo(participant);
          if(modifier != null) receipt.setModifierTo(modifier);
        }
      }
    }
  }

  private Map<String, HoldingsModifier> loadModifiers(final SQLConnector sql, final TNEDialect dialect) throws SQLException {

    final Map<String, HoldingsModifier> modifiers = new HashMap<>();
    try(final ResultSet result = sql.executeQuery(dialect.loadModifiers(), new Object[0])) {
      while(result != null && result.next()) {
        modifiers.put(result.getString("uid") + "|" + result.getString("participant_type").toLowerCase(Locale.ROOT),
                      new HoldingsModifier(result.getString("region"),
                                           UUID.fromString(result.getString("currency")),
                                           result.getBigDecimal("modifier"),
                                           HoldingsOperation.valueOf(result.getString("operation"))));
      }
    }
    return modifiers;
  }

  private Map<String, List<HoldingsEntry>> loadHoldings(final SQLConnector sql, final TNEDialect dialect) throws SQLException {

    final Map<String, List<HoldingsEntry>> holdings = new HashMap<>();
    try(final ResultSet result = sql.executeQuery(dialect.loadReceiptHolding(), new Object[0])) {
      while(result != null && result.next()) {
        final String key = result.getString("uid") + "|" + result.getString("participant") + "|"
                           + result.getBoolean("ending");
        holdings.computeIfAbsent(key, ignored->new ArrayList<>())
                .add(new HoldingsEntry(result.getString("region"),
                                       UUID.fromString(result.getString("currency")),
                                       result.getBigDecimal("holdings"),
                                       Identifier.fromID(result.getString("holdings_type"))));
      }
    }
    return holdings;
  }
}
