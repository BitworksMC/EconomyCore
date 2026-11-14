import net.tnemc.core.io.storage.datables.yaml.YAMLReceipt;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.Receipt;
import net.tnemc.core.transaction.TransactionParticipant;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.utils.IOUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Compares the former sequential load with bounded parsing on real receipts. Stops its server. */
public final class ReceiptLoadProbe extends JavaPlugin {

  private static final UUID FIXTURE = UUID.fromString("f17e0000-0000-4000-8000-00000000beef");
  private int failures;

  @Override
  public void onEnable() {

    ProbeScheduler.run(this, this::run);
  }

  private void run() {

    final File directory = new File(PluginCore.directory(), "transactions");
    final File fixture = new File(directory, FIXTURE + ".yml");
    boolean created = false;
    try {
      final File[] existing = IOUtil.getYAMLs(directory);
      require(existing.length >= 8000, "Benchmark requires at least 8000 real receipt files");
      require(!fixture.exists(), "Benchmark fixture already exists");
      final String template = Files.readString(existing[0].toPath())
          .replaceFirst("(?m)^id:.*$", "id: " + FIXTURE)
          .replaceFirst("(?m)^type:.*$", "type: ReceiptBenchmarkOne");
      Files.writeString(fixture.toPath(), template);
      created = true;
      final YAMLReceipt loader = new YAMLReceipt();
      for(int round = 0; round < 2; round++) {
        final List<Receipt> sequential;
        final List<Receipt> parallel;
        if(round == 0) {
          sequential = timed(loader, false, round);
          parallel = timed(loader, true, round);
        } else {
          parallel = timed(loader, true, round);
          sequential = timed(loader, false, round);
        }
        require(signatures(sequential).equals(signatures(parallel)), "Parsed receipt content differs");
        require(sequential.stream().map(Receipt::getId).toList().equals(parallel.stream().map(Receipt::getId).toList()),
                "Receipt publication order differs");
        require(TransactionManager.receipts().getReceipts().size() == existing.length + 1, "Live receipts lost or duplicated");
      }
      final Receipt mutable = loader.load(fixture, FIXTURE.toString()).orElseThrow();
      final boolean originalVoided = mutable.isVoided();
      mutable.setVoided(!originalVoided);
      require(loader.load(fixture, FIXTURE.toString()).orElseThrow().isVoided() == originalVoided,
          "A mutated live receipt leaked into a subsequent load");
      final Method bytes = Class.forName("net.tnemc.core.io.storage.datables.yaml.ReceiptBytes")
          .getDeclaredMethod("read", File.class);
      bytes.setAccessible(true);
      final byte[] exposed = (byte[])bytes.invoke(null, fixture);
      exposed[0] = 0;
      require(loader.load(fixture, FIXTURE.toString()).isPresent(), "Cache exposed a mutable byte array");
      final FileTime originalTime = Files.getLastModifiedTime(fixture.toPath());
      final long originalSize = Files.size(fixture.toPath());
      Files.writeString(fixture.toPath(), template.replace("ReceiptBenchmarkOne", "ReceiptBenchmarkTwo"));
      require(Files.size(fixture.toPath()) == originalSize, "External edit must retain file size");
      final long nanos = originalTime.to(TimeUnit.NANOSECONDS);
      Files.setLastModifiedTime(fixture.toPath(), FileTime.from(nanos + (nanos % 1_000_000 == 0? 100 : -100), TimeUnit.NANOSECONDS));
      final FileTime edited = Files.getLastModifiedTime(fixture.toPath());
      require(edited.toMillis() == originalTime.toMillis() && !edited.equals(originalTime),
          "External edit must change high precision mtime within the same millisecond");
      require(loader.load(fixture, FIXTURE.toString()).orElseThrow().getType().equals("ReceiptBenchmarkTwo"),
          "Same-size external edit was hidden by the byte cache");
      final Collection<Receipt> reread = loader.loadAll(null, null);
      require(reread.stream().anyMatch(receipt->receipt.getId().equals(FIXTURE)
          && receipt.getType().equals("ReceiptBenchmarkTwo")), "External disk edit was not reloaded");
      require(TransactionManager.receipts().getReceipts().get(FIXTURE).getType().equals("ReceiptBenchmarkTwo"),
          "External disk edit was not published");
      Files.delete(fixture.toPath());
      try {
        bytes.invoke(null, fixture);
        throw new AssertionError("Deleted receipt was returned from the byte cache");
      } catch(final InvocationTargetException expected) {
        require(expected.getCause() instanceof java.io.IOException, "Unexpected deletion error");
      }
      getLogger().info("RECEIPT_LOAD_RESULT files=" + existing.length + " failures=" + failures);
    } catch(final Throwable failure) {
      failures++;
      getLogger().log(java.util.logging.Level.SEVERE, "RECEIPT_LOAD_RESULT failures=" + failures, failure);
    } finally {
      try {
        if(created) {
          Files.deleteIfExists(fixture.toPath());
          TransactionManager.receipts().getReceipts().remove(FIXTURE);
        }
      } catch(final Exception failure) {
        getLogger().severe("Benchmark fixture cleanup failed: " + failure);
      }
      getServer().shutdown();
    }
  }

  private List<Receipt> timed(final YAMLReceipt loader, final boolean parallel, final int round) {

    TransactionManager.receipts().getReceipts().clear();
    final long start = System.nanoTime();
    final List<Receipt> result;
    if(parallel) {
      result = new ArrayList<>(loader.loadAll(null, null));
    } else {
      result = new ArrayList<>();
      for(final File file : IOUtil.getYAMLs(new File(PluginCore.directory(), "transactions"))) {
        loader.load(file, file.getName().replace(".yml", "")).ifPresent(receipt->{
          result.add(receipt);
          TransactionManager.receipts().log(receipt);
        });
      }
    }
    getLogger().info("RECEIPT_LOAD_TIMING round=" + round + " mode=" + (parallel? "parallel" : "sequential")
        + " files=" + result.size() + " millis=" + (System.nanoTime() - start) / 1_000_000);
    return result;
  }

  private Map<UUID, String> signatures(final List<Receipt> receipts) {

    final Map<UUID, String> result = new LinkedHashMap<>();
    for(final Receipt receipt : receipts) {
      final String signature = receipt.getTime() + "|" + receipt.getType() + "|" + receipt.getSource().asString()
          + "|" + receipt.isArchive() + "|" + receipt.isVoided() + "|" + participant(receipt.getFrom())
          + "|" + participant(receipt.getTo()) + "|" + modifier(receipt.getModifierFrom())
          + "|" + modifier(receipt.getModifierTo());
      require(result.put(receipt.getId(), signature) == null, "Duplicate receipt ID");
    }
    return result;
  }

  private String modifier(final HoldingsModifier modifier) {

    return modifier == null? "none" : modifier.getRegion() + "|" + modifier.getCurrency() + "|" + modifier.getModifier()
        + "|" + modifier.getOperation() + "|" + modifier.getType().asID() + "|" + modifier.isPercent();
  }

  private String participant(final TransactionParticipant participant) {

    if(participant == null) {
      return "none";
    }
    final StringBuilder signature = new StringBuilder(participant.getId().toString()).append('|').append(participant.getTax());
    for(final var entries : List.of(participant.getStartingBalances(), participant.getEndingBalances())) {
      signature.append("[");
      entries.forEach(entry->signature.append(entry.getRegion()).append('|').append(entry.getCurrency()).append('|')
          .append(entry.getHandler().asID()).append('|').append(entry.getAmount()).append(';'));
      signature.append("]");
    }
    return signature.toString();
  }

  private void require(final boolean condition, final String message) {

    if(!condition) {
      throw new AssertionError(message);
    }
  }
}
