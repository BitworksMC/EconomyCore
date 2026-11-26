package net.tnemc.core.io.storage;

import net.tnemc.core.TNEStorageProvider;
import net.tnemc.core.io.yaml.YamlStorageManager;
import net.tnemc.plugincore.core.io.storage.StorageConnector;
import net.tnemc.plugincore.core.io.storage.StorageEngine;
import net.tnemc.plugincore.core.io.storage.StorageManager;
import net.tnemc.plugincore.core.io.storage.connect.SQLConnector;
import net.tnemc.plugincore.core.io.storage.engine.sql.MySQL;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StorageBackupTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void sqlReadSurvivesConnectionCloseAndDatabaseReopen() throws Exception {
    final String jdbcUrl = "jdbc:h2:file:" + temporary.getRoot().toPath().resolve("economy").toAbsolutePath();
    try(final var connection = DriverManager.getConnection(jdbcUrl);
        final var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE accounts (stored_id VARCHAR(36), balance DECIMAL(20, 4), created TIMESTAMP, active BOOLEAN)");
      statement.execute("INSERT INTO accounts VALUES ('test', 12.2500, TIMESTAMP '2026-01-02 03:04:05', TRUE)");
    }

    final var connector = new SQLConnector() {
      private Connection reopened;

      @Override
      public Connection connection() throws SQLException {
        reopened = DriverManager.getConnection(jdbcUrl);
        return reopened;
      }
    };
    try(final ResultSet rows = connector.executeQuery(
            "SELECT CONCAT(stored_id, '') AS uid, balance, created, active FROM accounts WHERE stored_id = ?",
            new Object[]{ "test" })) {
      assertTrue(connector.reopened.isClosed());
      assertNotNull(rows);
      assertTrue(rows.next());
      assertEquals("test", rows.getString("uid"));
      assertEquals(new BigDecimal("12.2500"), rows.getBigDecimal("balance"));
      assertEquals(Timestamp.valueOf("2026-01-02 03:04:05"), rows.getTimestamp("created"));
      assertTrue(rows.getBoolean("active"));
      assertFalse(rows.next());
    }
  }

  @Test
  public void yamlAccountsAndReceiptsCanBeRestoredFromArchive() throws IOException {
    final Path data = temporary.newFolder("data").toPath();
    final Path account = Files.createDirectory(data.resolve("accounts")).resolve("account.yml");
    final Path receipt = Files.createDirectory(data.resolve("transactions")).resolve("receipt.yaml");
    final String savedAccount = "Info:\n  Name: town\nHoldings:\n  main: '13.50'\n";
    final String savedReceipt = "type: transfer\nfrom:\n  id: test\n";
    Files.writeString(account, savedAccount);
    Files.writeString(receipt, savedReceipt);

    final Path archive = new YamlStorageManager().backup(data);
    Files.writeString(account, "Info:\n  Name: changed\n");
    final Path restored = temporary.newFolder("restored").toPath();
    try(final ZipFile zip = new ZipFile(archive.toFile())) {
      Files.createDirectories(restored.resolve("accounts"));
      Files.createDirectories(restored.resolve("transactions"));
      try(final var accountBytes = zip.getInputStream(zip.getEntry("accounts/account.yml"));
          final var receiptBytes = zip.getInputStream(zip.getEntry("transactions/receipt.yaml"))) {
        Files.copy(accountBytes, restored.resolve("accounts/account.yml"));
        Files.copy(receiptBytes, restored.resolve("transactions/receipt.yaml"));
      }
    }
    assertEquals(savedAccount, Files.readString(restored.resolve("accounts/account.yml")));
    assertEquals(savedReceipt, Files.readString(restored.resolve("transactions/receipt.yaml")));
  }

  @Test
  public void yamlFailureLeavesNoPublishedArchive() throws IOException {
    final Path data = temporary.newFolder("bad-data").toPath();
    Files.createDirectory(data.resolve("accounts"));
    Files.writeString(data.resolve("transactions"), "unexpected file");
    assertThrows(IOException.class, ()->new YamlStorageManager().backup(data));
    try(final var backups = Files.list(data.resolve("backups"))) {
      assertEquals(0, backups.count());
    }
  }

  @Test
  public void unsupportedSqlEngineDoesNotReportSuccess() {
    final MySQL engine = new MySQL("tne_", null) {
      @Override
      public void backup(final StorageConnector<?> connector) {
        fail("An unsupported engine must not attempt a backup");
      }
    };
    final SQLConnector connector = new SQLConnector() {
      @Override
      public void initialize() { }
    };
    final TNEStorageProvider provider = new TNEStorageProvider() {
      @Override
      public void initialize(final String name) { }

      @Override
      public void initialize() { }

      @Override
      public StorageEngine engine() {
        return engine;
      }

      @Override
      public StorageConnector<?> connector() {
        return connector;
      }
    };
    final StorageManager storage = new StorageManager("mysql", provider, null, null);
    assertFalse(storage.backup());
  }
}
