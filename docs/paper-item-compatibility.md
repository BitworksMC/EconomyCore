# Paper item compatibility

## Why these adapters exist

TNE currently embeds TNIL `0.2.0.0-SNAPSHOT-3`. Its Paper serializers contain
missing registry conversions, API-version assumptions, and lossy component
reconstruction. These adapters are registered during `PaperPlugin.enable`, not
during load: initializing the item platform too early prevents enabled custom-item
plugins from registering their providers.

- `PaperRegistryConversions` converts Bukkit keyed values and Adventure keys to
  namespaced strings, and resolves damage types, jukebox songs, and instruments
  back through their Bukkit registries. Unknown registry entries remain errors.
- The local `PlatformConverter` replacement checks the requested output mapping
  before invoking an inherited conversion. It prefers exact matches, then the
  most-specific registered input types, with deterministic ordering for unrelated
  interfaces. The shading exclusions keep embedded older copies from replacing it.
- `PaperVanillaProvider` rejects different materials before serialization, compares
  nested vanilla items by their components, and returns independently mutable
  native stacks with the requested amount. Other item providers retain their own
  comparison rules.
- `PaperComponentCompatibility` checks actual API capabilities rather than relying
  solely on Minecraft version numbers. `PaperPreservedComponents` retains native
  component values and whether they came from material defaults. Unchanged values
  keep registry tags, nullable values, IDs, and fields TNIL cannot represent;
  edited components are rebuilt instead of silently restoring an old snapshot.
- `PaperNestedItems` rebuilds children rather than the containing item, retaining
  slot positions and amounts. `PaperPersistentItemComponent` preserves and compares
  persistent data and item flags so marked currency is not treated as plain items.
- `PaperInventoryCalculations` performs each inventory mutation on detached storage
  before committing it. Exceptions propagate without committing partial changes.
  Inventory and ender-chest handlers manage their own wallet snapshots after item
  calculations, avoiding the earlier pre-emptive wallet write.

Do not remove these adapters or the shading exclusions merely because TNIL's
snapshot version number changes. Verify the actual dependency contents and repeat
the runtime checks first.

## Validation

The local regression probe uses real Paper servers and checks:

1. Every non-air item material: serialize, rebuild, compare metadata, count itself
   as currency, count gold beside it, and remove gold without altering the item.
2. Every available damage type, jukebox song, and instrument; typed keys and an
   otherwise unregistered implementation of Bukkit's `Keyed` interface.
3. Names, lore, enchantments, potion/stew effects, persistent data, flags, mutable
   component edits, cached amounts, charged crossbows, populated shulkers/bundles,
   container slots, nested removal counts, and partial container-currency removal.
4. Injected comparison and item-creation failures after an earlier staged debit or
   credit, verifying that the live inventory is unchanged and the error is not hidden.
5. Converter output selection, specificity, identity, null input, and unsupported
   conversions against both the shaded Paper and Folia artifacts.

Run `mvn clean verify -Dmaven.javadoc.skip=true` for the full reactor build and PMD
checks. Runtime validation is separate from Maven's build checks.

### 0.1.5.2 local validation (2026-09-17)

| Server | Runtime checks | Failures |
| --- | ---: | ---: |
| Paper 1.21.4 build 232 | 7,011 | 0 |
| Paper 1.21.10 build 130 | 7,528 | 0 |
| Paper 26.2 build 112 | 7,776 | 0 |

The full reactor clean build and PMD checks passed. The six standalone converter
checks also passed against each of the shaded Paper and Folia JARs.

Coverage is finite: these checks do not prove every third-party integration,
custom component combination, future Paper API, or whole multi-account transaction.
Folia shares the adapters and receives artifact-level checks, but was not exercised
on a running Folia server. Production servers should be backed up before updating.
