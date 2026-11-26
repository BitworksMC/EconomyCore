package net.tnemc.core.io.storage;

import dev.dejvokep.boostedyaml.YamlDocument;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.NonPlayerAccount;
import net.tnemc.core.config.DataConfig;
import net.tnemc.core.io.storage.dialect.TNEDialect;
import net.tnemc.core.io.storage.dialect.impl.MySQLDialect;
import net.tnemc.core.manager.AccountManager;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.compatibility.LogProvider;
import net.tnemc.plugincore.core.id.UUIDPair;
import net.tnemc.plugincore.core.io.storage.StorageConnector;
import net.tnemc.plugincore.core.io.storage.connect.SQLConnector;
import net.tnemc.plugincore.core.io.storage.connect.YAMLConnector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Relocate a persisted account without replacing any destination or dropping its holdings.
 */
public final class AccountIdentifierMigration {

  private final StorageConnector<?> connector;
  private final LogProvider log;
  private final Path accountsDirectory;
  private final String sqlPrefix;

  private AccountIdentifierMigration(final StorageConnector<?> connector) {

    this.connector = connector;
    this.log = PluginCore.log();
    this.accountsDirectory = connector instanceof YAMLConnector? PluginCore.directory().toPath().resolve("accounts") : null;
    this.sqlPrefix = connector instanceof SQLConnector? DataConfig.yaml().getString("Data.Database.Prefix") : null;
  }

  public static boolean moveAccount(final AccountManager manager, final UUID source, final UUID target, final String name) {

    final TNECore core = TNECore.instance();
    final AccountIdentifierMigration migration = new AccountIdentifierMigration(core.storage().getConnector());
    return core.saver().rewriteAccount(()->migration.movePersisted(manager, source, target, name));
  }

  private boolean movePersisted(final AccountManager manager, final UUID source, final UUID target, final String name) {

    final var accounts = manager.getAccounts();
    final Account existing = accounts.get(source.toString());
    if(!(existing instanceof final NonPlayerAccount account) || !name.equals(account.getName())
       || accounts.containsKey(target.toString()) || !move(source, target)) {
      return false;
    }
    account.useStorageIdentifier(target);
    account.markDirty();
    accounts.put(target.toString(), account);
    accounts.remove(source.toString(), account);
    manager.uuidProvider().store(new UUIDPair(target, name));
    manager.uuidProvider().pairs().remove(source);
    return true;
  }

  private boolean move(final UUID source, final UUID target) {

    try {
      if(connector instanceof YAMLConnector) {
        return moveYaml(source, target);
      }
      if(connector instanceof final SQLConnector sql && sql.dialect() instanceof final TNEDialect dialect) {
        return moveSql(sql, dialect, source.toString(), target.toString());
      }
    } catch(final IOException | SQLException failure) {
      log.warning("Unable to move account " + source + " to " + target + ": " + failure.getMessage());
    }
    return false;
  }

  private boolean moveYaml(final UUID source, final UUID target) throws IOException {

    final Path oldFile = accountsDirectory.resolve(source + ".yml");
    final Path targetFile = accountsDirectory.resolve(target + ".yml");
    if(!Files.isRegularFile(oldFile, LinkOption.NOFOLLOW_LINKS) || Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    final YamlDocument yaml;
    try(final InputStream input = Files.newInputStream(oldFile)) {
      yaml = YamlDocument.create(input);
    }
    yaml.set("Info.ID", target.toString());
    if(source.toString().equals(yaml.getString("Info.Owner"))) {
      yaml.set("Info.Owner", target.toString());
    }
    final Path normalized = Files.createTempFile(accountsDirectory, ".account-move-", ".tmp");
    try {
      yaml.save(normalized.toFile());
      // Move the full original first so a crash leaves one discoverable account.
      Files.move(oldFile, targetFile);
      writeYamlIdentity(oldFile, targetFile, normalized);
      return true;
    } finally {
      try {
        Files.deleteIfExists(normalized);
      } catch(final IOException cleanupFailure) {
        log.warning("Unable to remove temporary account file " + normalized + ": " + cleanupFailure.getMessage());
      }
    }
  }

  private void writeYamlIdentity(final Path oldFile, final Path targetFile, final Path normalized) throws IOException {

    try {
      Files.move(normalized, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch(final IOException failure) {
      try {
        Files.move(targetFile, oldFile);
      } catch(final IOException rollbackFailure) {
        log.warning("Account file moved to " + targetFile
                    + " but its stored identity still needs normalization on save: " + rollbackFailure.getMessage());
        return;
      }
      throw failure;
    }
  }

  private boolean moveSql(final SQLConnector sql, final TNEDialect dialect,
                          final String source, final String target) throws SQLException {

    final String prefix = sqlPrefix;
    if(prefix == null || !prefix.matches("[A-Za-z0-9_]*")) {
      return false;
    }
    try(final Connection connection = sql.connection()) {
      if(!connection.getMetaData().supportsTransactions() || !transactionalTables(connection, prefix)) {
        return false;
      }
      connection.setAutoCommit(false);
      try {
        if(!moveSqlRows(connection, dialect, prefix, source, target)) {
          connection.rollback();
          return false;
        }
        connection.commit();
        return true;
      } catch(final SQLException failure) {
        try {
          connection.rollback();
        } catch(final SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  private static boolean moveSqlRows(final Connection connection, final TNEDialect dialect, final String prefix,
                                     final String source, final String target) throws SQLException {

    final String uid = dialect instanceof MySQLDialect? "UUID_TO_BIN(?)" : "?";
    final String accountTable = prefix + "accounts";
    final String username = lockUsername(connection, accountTable, uid, source);
    if(username == null || lockUsername(connection, accountTable, uid, target) != null) {
      return false;
    }

    // Username is unique. Free it, insert the replacement, and move all foreign
    // keys before deleting the old row, within the same database transaction.
    verifyAccountUpdate(update(connection, "UPDATE " + accountTable + " SET username = ? WHERE uid = " + uid,
                               "tne-" + UUID.randomUUID(), source));
    verifyAccountUpdate(update(connection, "INSERT INTO " + accountTable + " (uid, username, account_type, created, pin, status)"
                               + " SELECT " + uid + ", ?, account_type, created, pin, status FROM " + accountTable + " WHERE uid = " + uid,
                               target, username, source));
    moveAccountReferences(connection, prefix, uid, source, target);
    verifyAccountUpdate(update(connection, dialect.deleteAccount(), source));
    return true;
  }

  private static boolean transactionalTables(final Connection connection, final String prefix) throws SQLException {

    try(final PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND engine = 'InnoDB'"
            + " AND table_name IN (?, ?, ?, ?, ?)")) {
      statement.setString(1, prefix + "accounts");
      statement.setString(2, prefix + "non_players_accounts");
      statement.setString(3, prefix + "players_accounts");
      statement.setString(4, prefix + "account_members");
      statement.setString(5, prefix + "holdings");
      try(final ResultSet rows = statement.executeQuery()) {
        return rows.next() && rows.getInt(1) == 5;
      }
    }
  }

  private static String lockUsername(final Connection connection, final String table,
                                     final String uid, final String id) throws SQLException {

    try(final PreparedStatement statement = connection.prepareStatement(
            "SELECT username FROM " + table + " WHERE uid = " + uid + " FOR UPDATE")) {
      statement.setString(1, id);
      try(final ResultSet rows = statement.executeQuery()) {
        return rows.next()? rows.getString(1) : null;
      }
    }
  }

  private static void moveAccountReferences(final Connection connection, final String prefix,
                                            final String uid, final String source, final String target) throws SQLException {

    moveReference(connection, prefix + "non_players_accounts", "uid", uid, source, target);
    moveReference(connection, prefix + "non_players_accounts", "owner", uid, source, target);
    moveReference(connection, prefix + "players_accounts", "uid", uid, source, target);
    moveReference(connection, prefix + "account_members", "uid", uid, source, target);
    moveReference(connection, prefix + "account_members", "account", uid, source, target);
    moveReference(connection, prefix + "holdings", "uid", uid, source, target);
  }

  private static void moveReference(final Connection connection, final String table, final String column,
                                    final String uid, final String source, final String target) throws SQLException {

    update(connection, "UPDATE " + table + " SET " + column + " = " + uid + " WHERE " + column + " = " + uid,
           target, source);
  }

  private static void verifyAccountUpdate(final int updated) throws SQLException {

    if(updated != 1) {
      throw new SQLException("Expected to update one account row, updated " + updated);
    }
  }

  private static int update(final Connection connection, final String query,
                            final String... parameters) throws SQLException {

    try(final PreparedStatement statement = connection.prepareStatement(query)) {
      for(int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      return statement.executeUpdate();
    }
  }
}
