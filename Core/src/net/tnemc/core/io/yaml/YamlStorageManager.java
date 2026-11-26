package net.tnemc.core.io.yaml;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * YamlStorageManager
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class YamlStorageManager {

  private final Map<String, Boolean> inUsage = new ConcurrentHashMap<>();

  public Map<String, Boolean> getInUsage() {

    return inUsage;
  }

  public void add(final String file) {

    inUsage.put(file, true);
  }

  public boolean inUse(final String file) {

    return inUsage.containsKey(file);
  }

  public void remove(final String file) {

    inUsage.remove(file);
  }

  /** Archives persisted accounts, holdings, and receipts on the storage thread. */
  public Path backup(final Path dataDirectory) throws IOException {

    final Path backups = Files.createDirectories(dataDirectory.resolve("backups"));
    final Path archive = backups.resolve("yaml-" + UUID.randomUUID() + ".zip");
    final Path temporary = Files.createTempFile(backups, ".yaml-", ".tmp");
    try {
      try(final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
        archiveDirectory(dataDirectory, "accounts", zip);
        archiveDirectory(dataDirectory, "transactions", zip);
      }
      return Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void archiveDirectory(final Path dataDirectory, final String name, final ZipOutputStream zip) throws IOException {

    final Path directory = dataDirectory.resolve(name);
    if(!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("YAML storage directory is missing or is not a directory: " + name);
    }
    zip.putNextEntry(new ZipEntry(name + "/"));
    zip.closeEntry();
    try(final var files = Files.newDirectoryStream(directory, "*.{yml,yaml}")) {
      for(final Path file : files) {
        if(!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Expected a regular storage file: " + file);
        }
        zip.putNextEntry(new ZipEntry(name + "/" + file.getFileName()));
        Files.copy(file, zip);
        zip.closeEntry();
      }
    }
  }
}
