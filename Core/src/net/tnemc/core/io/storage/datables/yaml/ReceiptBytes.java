package net.tnemc.core.io.storage.datables.yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable serialized YAML, never live documents or receipts. File metadata is checked on every read. */
final class ReceiptBytes {

  private static final int MAX_ENTRIES = 8192;
  private static final long MAX_BYTES = 16L * 1024 * 1024;
  private static final int MAX_ENTRY_BYTES = 256 * 1024;
  private static final Map<Path, Entry> CACHE = new LinkedHashMap<>(128, 0.75F, true);
  private static long cachedBytes;

  private ReceiptBytes() { }

  static byte[] read(final File file) throws IOException {

    final Path path = file.toPath().toAbsolutePath().normalize();
    try {
      final BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
      final byte[] cached = cached(path, before);
      if(cached != null) {
        return cached;
      }
      final byte[] bytes = before.size() == 0? new byte[0] : Files.readAllBytes(path);
      final BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
      if(same(before, after)) {
        remember(path, bytes, after);
      } else {
        invalidate(path);
      }
      return bytes;
    } catch(final IOException failure) {
      invalidate(path);
      throw failure;
    }
  }

  static void saved(final File file, final byte[] bytes) throws IOException {

    final Path path = file.toPath().toAbsolutePath().normalize();
    remember(path, bytes, Files.readAttributes(path, BasicFileAttributes.class));
  }

  private static synchronized byte[] cached(final Path path, final BasicFileAttributes attributes) {

    final Entry entry = CACHE.get(path);
    return entry != null && entry.matches(attributes)? entry.bytes().clone() : null;
  }

  private static synchronized void remember(final Path path, final byte[] bytes, final BasicFileAttributes attributes) {

    invalidate(path);
    if(bytes.length > MAX_ENTRY_BYTES || bytes.length != attributes.size()) {
      return;
    }
    CACHE.put(path, new Entry(bytes.clone(), attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()));
    cachedBytes += bytes.length;
    final Iterator<Map.Entry<Path, Entry>> oldest = CACHE.entrySet().iterator();
    while(CACHE.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
      cachedBytes -= oldest.next().getValue().bytes().length;
      oldest.remove();
    }
  }

  private static synchronized void invalidate(final Path path) {

    final Entry removed = CACHE.remove(path);
    if(removed != null) {
      cachedBytes -= removed.bytes().length;
    }
  }

  private static boolean same(final BasicFileAttributes before, final BasicFileAttributes after) {

    return before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
           && Objects.equals(before.fileKey(), after.fileKey());
  }

  private record Entry(byte[] bytes, long size, FileTime modified, Object fileKey) {

    private boolean matches(final BasicFileAttributes attributes) {

      return size == attributes.size() && modified.equals(attributes.lastModifiedTime())
             && Objects.equals(fileKey, attributes.fileKey());
    }
  }
}
