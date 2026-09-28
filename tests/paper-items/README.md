# Paper item runtime regressions

These probes run against the built, shaded TNE plugin on a real server. They are
separate from Maven/PMD and **stop the test server when finished**. Never install
them on a production server.

`ItemRegression` covers the reported raw ItemsAdder-shaped `custom_data` (including
non-PDC nested NBT), model/name/lore/tooltip preservation, PDC coexistence, component
JSON and legacy PDC reads, flag aliases, nested containers, cache isolation, and
currency overflowing from inventory to ender chest to a dropped item. It verifies
that the resulting currency can still be counted and spent, and rejects an
otherwise identical item whose identity tag is missing or different. The custom
provider is a fixture, not the proprietary ItemsAdder plugin. Native transport
also exercises opaque entity/bucket data, removed default components, repeated
overflow, native persistence, reference isolation and failed metadata edits.
A deliberately failing TNIL serializer must never be invoked during these
transfers, including transfers of populated containers.

`InventoryAudit` covers every item material, registry values, metadata edits,
nested inventories, count/removal, amounts, and failures during staged mutations.
Every item material also goes through a native snapshot persistence round trip.

Build TNE with `mvn clean verify -Dmaven.javadoc.skip=true`. Prepare isolated servers
under `.testserver`, bound to `server-ip=127.0.0.1`, with different ports, an accepted
EULA, local TNE configuration, and exactly one TNE plugin JAR each. Start each server
once to download its libraries. The first server supplies the compile classpath;
use Paper 1.21.10 or newer. Use a JDK supported by every selected server (Java 26
when testing Paper 26.2).

From the repository root, in PowerShell:

```powershell
./tests/paper-items/run.ps1 `
  -JavaHome 'C:/path/to/jdk-26' `
  -ServerDirectory '.testserver/item-audit12110', '.testserver/item-audit1214', '.testserver/item-audit12111', '.testserver/item-audit26'
```

The runner compiles both probes, installs the selected TNE artifact in the test
servers, runs each server, and requires both result markers with zero failures.
Output is saved as `regression-validation.log` in each server directory. The runner
does not accept paths outside `.testserver` or servers bound to public interfaces.
