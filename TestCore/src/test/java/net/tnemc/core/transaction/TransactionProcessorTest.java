package net.tnemc.core.transaction;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.tnemc.core.EconomyManager;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.holdings.HoldingsEntry;
import net.tnemc.core.account.holdings.modify.HoldingsModifier;
import net.tnemc.core.channel.BalanceHandler;
import net.tnemc.core.channel.RedisSync;
import net.tnemc.core.manager.AccountManager;
import net.tnemc.core.manager.CurrencyManager;
import net.tnemc.core.manager.ReceiptManager;
import net.tnemc.core.manager.TransactionManager;
import net.tnemc.core.transaction.processor.BaseTransactionProcessor;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.api.CallbackManager;
import net.tnemc.plugincore.core.compatibility.LogProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class TransactionProcessorTest {

  @Rule public final TemporaryFolder dataFolder = new TemporaryFolder();

  private final UUID currency = UUID.randomUUID();
  private static final String REGION = "world";
  private MockedStatic<TNECore> core;
  private MockedStatic<EconomyManager> economyStatics;
  private MockedStatic<PluginCore> plugin;
  private MockedStatic<TransactionManager> transactionStatics;
  private AccountManager accounts;
  private ReceiptManager receipts;

  @Before public void setUp() {

    core = mockStatic(TNECore.class);
    economyStatics = mockStatic(EconomyManager.class);
    plugin = mockStatic(PluginCore.class);
    transactionStatics = mockStatic(TransactionManager.class);

    final EconomyManager economy = mock(EconomyManager.class);
    accounts = mock(AccountManager.class);
    receipts = mock(ReceiptManager.class);
    when(economy.account()).thenReturn(accounts);
    when(economy.currency()).thenReturn(mock(CurrencyManager.class));
    when(economy.transaction()).thenReturn(mock(TransactionManager.class));
    core.when(TNECore::eco).thenReturn(economy);
    economyStatics.when(EconomyManager::baseProcessor).thenReturn(new BaseTransactionProcessor(new LinkedList<>()));
    plugin.when(PluginCore::log).thenReturn(mock(LogProvider.class));
    plugin.when(PluginCore::callbacks).thenReturn(mock(CallbackManager.class));
    plugin.when(PluginCore::directory).thenReturn(dataFolder.getRoot());
    transactionStatics.when(TransactionManager::receipts).thenReturn(receipts);
  }

  @After public void tearDown() {

    transactionStatics.close();
    plugin.close();
    economyStatics.close();
    core.close();
  }

  @Test public void rejectedSecondLegRestoresBothBalancesWhenItPartlyApplied() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    recipient.accept = amount -> amount.compareTo(new BigDecimal("15")) != 0;

    assertFailed(transfer(sender, recipient).process());
    assertEquals(new BigDecimal("100"), sender.amount);
    assertEquals(new BigDecimal("5"), recipient.amount);
    assertFalse(Files.exists(dataFolder.getRoot().toPath().resolve("failed-transactions")));
  }

  @Test public void thrownSecondLegRestoresBothBalancesWhenItPartlyApplied() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    recipient.accept = amount -> {
      if(amount.compareTo(new BigDecimal("15")) == 0) {
        throw new IllegalStateException("Injected delivery failure");
      }
      return true;
    };

    assertFailed(transfer(sender, recipient).process());
    assertEquals(new BigDecimal("100"), sender.amount);
    assertEquals(new BigDecimal("5"), recipient.amount);
  }

  @Test public void failedCompensationWritesRecoveryEvidenceAndReturnsNoReceipt() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    sender.accept = amount -> {
      if(amount.compareTo(new BigDecimal("100")) == 0) {
        sender.amount = new BigDecimal("90");
        return false;
      }
      return true;
    };
    recipient.accept = amount -> amount.compareTo(new BigDecimal("15")) != 0;

    final TransactionResult result = transfer(sender, recipient).process();
    assertFailed(result);
    assertTrue(result.getMessage().contains("reconciliation"));
    assertEquals(new BigDecimal("90"), sender.amount);
    assertEquals(new BigDecimal("5"), recipient.amount);

    try(final Stream<Path> files = Files.list(dataFolder.getRoot().toPath().resolve("failed-transactions"))) {
      final List<Path> reports = files.toList();
      assertEquals(1, reports.size());
      assertTrue(result.getMessage().contains(reports.get(0).getFileName().toString().replace(".txt", "")));
      final String report = Files.readString(reports.get(0));
      assertTrue(report.contains("Account=" + sender.id + " Region=" + REGION + " Currency=" + currency
                                  + " Handler=" + EconomyManager.VIRTUAL.asID() + " Starting=100 Planned=90"));
      assertTrue(report.contains("Account=" + recipient.id + " Region=" + REGION + " Currency=" + currency
                                  + " Handler=" + EconomyManager.VIRTUAL.asID() + " Starting=5 Planned=15 Compensation=restored"));
    }
  }

  @Test public void missingRecipientBeforePlanningNeverDebitsSender() throws Exception {

    final Holding sender = new Holding("100");
    final UUID missing = UUID.randomUUID();
    assertFailed(new Transaction("pay").from(sender.account, modifier("-10")).to(missing, modifier("10")).process());
    assertEquals(new BigDecimal("100"), sender.amount);
    verify(sender.account, never()).setHoldings(any(), any());
  }

  @Test public void recipientRemovedAfterPlanningNeverDebitsSender() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    final Transaction transaction = transfer(sender, recipient);
    when(accounts.findAccount(recipient.id)).thenReturn(Optional.empty());
    assertFailed(transaction.process());
    verify(sender.account, never()).setHoldings(any(), any());
  }

  @Test public void recipientRemovedDuringSettlementRestoresSender() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    sender.accept = amount -> {
      if(amount.compareTo(new BigDecimal("90")) == 0) {
        when(accounts.findAccount(recipient.id)).thenReturn(Optional.empty());
      }
      return true;
    };
    assertFailed(transfer(sender, recipient).process());
    assertEquals(new BigDecimal("100"), sender.amount);
    verify(recipient.account, never()).setHoldings(any(), any());
  }

  @Test public void directProcessorRejectsAStalePlan() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    final Transaction first = transfer(sender, recipient), stale = transfer(sender, recipient);
    assertTrue(first.process().isSuccessful());
    final TransactionResult second = stale.getProcessor().process(stale);
    assertFalse(second.isSuccessful());
    assertTrue(second.getReceipt().isEmpty());
    verify(receipts, times(1)).log(any());
    assertEquals(new BigDecimal("90"), sender.amount);
    assertEquals(new BigDecimal("15"), recipient.amount);
  }

  @Test public void publicProcessingRefreshesBothPreviouslyPlannedBalances() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    final Transaction first = transfer(sender, recipient), second = transfer(sender, recipient);
    assertTrue(first.process().isSuccessful());
    assertTrue(second.process().isSuccessful());
    verify(receipts, times(2)).log(any());
    assertEquals(new BigDecimal("80"), sender.amount);
    assertEquals(new BigDecimal("25"), recipient.amount);
  }

  @Test public void redisDoesNotPublishFailedWritesOrTheirCompensation() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    sender.sync = true;
    recipient.sync = true;
    recipient.accept = amount -> amount.compareTo(new BigDecimal("15")) != 0;
    core.when(TNECore::syncEnabled).thenReturn(true);

    try(final MockedStatic<RedisSync> redis = mockStatic(RedisSync.class)) {
      assertFailed(transfer(sender, recipient).process());
      redis.verifyNoInteractions();
      assertFalse(BalanceHandler.deferringTransactionChanges());
      assertEquals(new BigDecimal("100"), sender.amount);
      assertEquals(new BigDecimal("5"), recipient.amount);
    }
  }

  @Test public void redisPublishesExactDeltasAfterBothLegsSucceed() throws Exception {

    final Holding sender = new Holding("100");
    final Holding recipient = new Holding("5");
    sender.sync = true;
    recipient.sync = true;
    core.when(TNECore::syncEnabled).thenReturn(true);
    final List<String> deltas = new ArrayList<>();
    try(final MockedStatic<RedisSync> redis = mockStatic(RedisSync.class)) {
      redis.when(() -> RedisSync.publish(eq(RedisSync.BALANCE), any())).thenAnswer(invocation -> {
        assertEquals(new BigDecimal("90"), sender.amount);
        assertEquals(new BigDecimal("15"), recipient.amount);
        final Consumer<ByteArrayDataOutput> encode = invocation.getArgument(1);
        deltas.add(readDelta(encode));
        return null;
      });
      assertTrue(transfer(sender, recipient).process().isSuccessful());
      assertEquals(List.of(sender.id + "=-10", recipient.id + "=10"), deltas);
    }
  }

  private String readDelta(final Consumer<ByteArrayDataOutput> encode) throws Exception {

    final ByteArrayDataOutput bytes = ByteStreams.newDataOutput();
    encode.accept(bytes);
    try(final DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      UUID.fromString(input.readUTF()); // Change ID.
      final String account = input.readUTF();
      assertEquals(account, input.readUTF()); // Mock account display name.
      assertEquals(REGION, input.readUTF());
      assertEquals(currency.toString(), input.readUTF());
      assertEquals(EconomyManager.VIRTUAL.asID(), input.readUTF());
      return account + "=" + input.readUTF();
    }
  }

  @Test public void oneSidedCreditStillSucceedsAndRecordsReceipt() throws Exception {

    final Holding recipient = new Holding("5");
    final TransactionResult result = new Transaction("give").to(recipient.account, modifier("10")).process();
    assertTrue(result.isSuccessful());
    verify(receipts).log(result.getReceipt().orElseThrow());
    assertEquals(new BigDecimal("15"), recipient.amount);
  }

  private void assertFailed(final TransactionResult result) {

    assertFalse(result.isSuccessful());
    assertTrue(result.getReceipt().isEmpty());
    verifyNoInteractions(receipts);
  }

  private Transaction transfer(final Holding sender, final Holding recipient) {

    return new Transaction("pay").from(sender.account, modifier("-10")).to(recipient.account, modifier("10"));
  }

  private HoldingsModifier modifier(final String delta) {

    return new HoldingsModifier(REGION, currency, new BigDecimal(delta));
  }

  private final class Holding {

    private final Account account = mock(Account.class);
    private final UUID id = UUID.randomUUID();
    private BigDecimal amount;
    private Predicate<BigDecimal> accept = ignored -> true;
    private boolean sync;

    private Holding(final String initial) {

      amount = new BigDecimal(initial);
      when(account.getIdentifier()).thenReturn(id);
      when(accounts.findAccount(id)).thenReturn(Optional.of(account));
      when(account.getHoldings(eq(REGION), eq(currency), any())).thenAnswer(ignored ->
              List.of(new HoldingsEntry(REGION, currency, amount, EconomyManager.VIRTUAL)));
      when(account.setHoldings(any(HoldingsEntry.class), eq(EconomyManager.VIRTUAL))).thenAnswer(call -> {
        assertTrue(Thread.holdsLock(Transaction.BALANCE_LOCK));
        final HoldingsEntry entry = call.getArgument(0);
        final BigDecimal before = amount;
        amount = entry.getAmount();
        final boolean accepted = accept.test(amount);
        if(accepted && sync) {
          BalanceHandler.send(id.toString(), id.toString(), REGION, currency, EconomyManager.VIRTUAL, amount.subtract(before));
        }
        return accepted;
      });
    }
  }
}
