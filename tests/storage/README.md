# Storage runtime regressions

These probes run against the built, shaded TNE plugin and stop the isolated test server.
Never install them on a production server. Prepare loopback servers under `.testserver`
with one TNE plugin, YAML or a dedicated test database, and no other server-stopping probes.
Start each server once so its `libraries` directory is available for compilation.

`SaveProbe` exercises concurrent transactions, saving before database reload, final shutdown
saves, and receipt participants after restart. `LifecycleProbe` holds the storage queue while
creating and deleting accounts, recreates the same ID under a new name, submits stale saves,
and checks the resulting deletion, name and balance after reload and restart. The runner
requires both phases to pass and rejects exceptions during TNE shutdown.

```powershell
./tests/storage/run.ps1 -JavaHome 'C:/path/to/jdk-26' `
    -ServerDirectory '.testserver/paper263' -Probe SaveProbe
./tests/storage/run.ps1 -JavaHome 'C:/path/to/jdk-26' `
    -ServerDirectory '.testserver/lifecycle263' -Probe LifecycleProbe
```

For Folia use `-PluginJar 'Folia/target/TNE-Folia-0.1.5.4.jar'` and a prepared Folia
server. Both descriptors declare Folia support; the shared scheduler starts the probes
on a loaded region thread. Each probe requires two server starts; keep its `restart-check`
marker between those starts. Successful second runs remove the marker.

Large YAML receipt histories still take time to reload because each receipt is a separate
file. Parsing uses up to eight readers, then publishes the receipts on the caller thread.
An LRU cache holds immutable serialized YAML bytes, bounded to 16 MiB of payload and
8,192 entries, with individual entries capped at 256 KiB. Reads validate file size,
high precision modification time and file identity. Documents and receipts are parsed
afresh, so caller mutations do not alter later loads. Writes serialize once and remember
the exact bytes successfully written. External edits and deleted files are rechecked.
Database reload still waits for the complete history; passing the balance assertions does
not establish that a large-history reload is safe for a live server's tick budget.

`ReceiptLoadProbe` requires at least 8,000 real receipts. It compares sequential and bounded
parallel parsing in both orders, checks complete receipt contents, ordering and live counts,
then tests same-size external edits within one millisecond, deletions and mutable-object
isolation. Like the other probes it stops its isolated server.

`OnlineItemProbe` uses a real loopback offline client with a gold-ingot currency. It checks
owner-thread transactions, rejects asynchronous item and mixed virtual/item transactions,
and dispatches an asynchronous request back to the owning player thread. It saves 22 gold
(17 inventory, 5 ender chest), changes the physical holdings to 30 (23 + 7), stops with the
player still online, then checks the stored balance before login and restored inventories
after the next login. Use a separate server with `online-mode=false`, `white-list=false`,
a unique port, and no other server-stopping probes:

```powershell
python tests/storage/run-online.py --java-home 'C:/path/to/jdk-26' `
    --server '.testserver/online-paper263'
python tests/storage/run-online.py --java-home 'C:/path/to/jdk-26' `
    --server '.testserver/online-folia263' --plugin-jar 'Folia/target/TNE-Folia-0.1.5.4.jar'
```

The raw test client supports the 26.3 login/configuration protocol only and never connects
outside `127.0.0.1`. Both client and server output are retained in each test server directory.
