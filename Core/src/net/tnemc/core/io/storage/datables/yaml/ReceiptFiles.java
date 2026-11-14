package net.tnemc.core.io.storage.datables.yaml;

import net.tnemc.core.transaction.Receipt;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/** Bounded disk parsing, with no access to the live receipt manager from worker threads. */
final class ReceiptFiles {

  private static final int MAX_READERS = 8;

  private ReceiptFiles() { }

  static List<Receipt> load(final File[] files, final Function<File, Optional<Receipt>> loader) {

    if(files.length < MAX_READERS) {
      return Arrays.stream(files).map(loader).flatMap(Optional::stream).toList();
    }
    final Receipt[] loaded = new Receipt[files.length];
    final int readers = Math.min(MAX_READERS, Runtime.getRuntime().availableProcessors());
    final ExecutorService executor = Executors.newFixedThreadPool(readers, runnable->{
      final Thread thread = new Thread(runnable, "TNE-ReceiptLoader");
      thread.setDaemon(true);
      return thread;
    });
    try {
      final List<Future<?>> tasks = new ArrayList<>(readers);
      //Only one task per worker is queued, even when the history contains millions of files.
      for(int reader = 0; reader < readers; reader++) {
        final int start = reader;
        tasks.add(executor.submit(()->{
          for(int index = start; index < files.length && !Thread.currentThread().isInterrupted(); index += readers) {
            loaded[index] = loader.apply(files[index]).orElse(null);
          }
        }));
      }
      await(tasks);
    } finally {
      executor.shutdownNow();
    }
    return Arrays.stream(loaded).filter(Objects::nonNull).toList();
  }

  private static void await(final List<Future<?>> tasks) {

    try {
      for(final Future<?> task : tasks) {
        task.get();
      }
    } catch(final InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Receipt loading interrupted", failure);
    } catch(final ExecutionException failure) {
      throw new IllegalStateException("Receipt loading failed", failure.getCause());
    }
  }
}
