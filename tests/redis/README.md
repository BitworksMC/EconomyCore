# Redis transport regressions

Run against a dedicated Redis instance bound to loopback. The test appends to `tne:sync`
and creates publisher watermark keys; do not use a production Redis database.

```powershell
./tests/redis/run.ps1 -JavaHome 'C:/path/to/jdk-26' -RedisPort 16379
```

The runner compiles the current production `RedisSync.java` and uses the project's real
Jedis dependency. Small host fixtures replace Minecraft/plugin services. It verifies:

- all 100 accepted updates drain when shutdown starts during the first send;
- an outage has a bounded shutdown and retains all seven updates on disk;
- restart delivers those updates, refreshes their run ID and shared token, and avoids self replay;
- losing every Redis acknowledgement retains the update without duplicate stream entries,
  including after restarting the transport.
- an unwritable outbox reports the failure and still sends to healthy Redis without throwing
  halfway through an account transfer.

Logs and isolated plugin data directories are saved under `.testserver/redis-regression`.
The Redis outbox uses the plugin directory's inherited filesystem permissions. Keep its
`publisher.id` and `publisher.sequence` files with pending updates during recovery.

`run-plugin.py` tests the complete shaded plugins together on isolated Paper and Folia
26.3 servers. It expects built plugin JARs and bootstrapped `.testserver/paper263` and
`.testserver/folia263` templates. Ports 25725 and 25726 must be free, and the
`.testserver/redis-plugin-paper` and `redis-plugin-folia` directories must not exist.

```powershell
python ./tests/redis/run-plugin.py 'C:/path/to/jdk-26' 16379
```

It measures convergence after 200 concurrent deposits on each server, pauses the dedicated
test Redis briefly, stops Paper with 100 queued updates, and verifies both balances and
all 300 Paper transaction receipts after Paper restarts. It uses Redis database 9 with separate YAML account storage. Shared SQL
persistence is outside this test. The runner stops its Minecraft processes; it leaves
the Redis process under the caller's control. Results are saved in
`.testserver/redis-plugin-validation.json` and each server's `integration-*.log`.
