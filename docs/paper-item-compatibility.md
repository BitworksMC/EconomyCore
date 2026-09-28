# Paper item compatibility

## Why these adapters exist

TNE currently embeds TNIL `0.2.0.0-SNAPSHOT-3`. Its Paper serializers contain
missing registry conversions, API-version assumptions, and lossy component
reconstruction. These adapters are registered during `PaperPlugin.enable`, not
during load: initializing the item platform too early prevents enabled custom-item
plugins from registering their providers.

- `PaperItemSnapshot` captures an existing native `ItemStack` by cloning it. It
  does not enumerate or deserialize its components. Copies change only the amount;
  equality uses native item similarity, and persistence uses Paper's versioned
  native item bytes. Unknown tags, component removals, and nested item data travel
  with the item without requiring another TNE component adapter.
- `PaperInventoryCalculations` gets the provider's item once per insertion and
  clones it before handing it to the inventory. Leftovers become native snapshots,
  so inventory retries, ender-chest overflow, and ground drops never reconstruct
  those items through TNIL. This also isolates provider-owned item instances from
  mutations made by `Inventory.addItem`.
- `PaperRegistryConversions` converts Bukkit keyed values and Adventure keys to
  namespaced strings, and resolves damage types, jukebox songs, and instruments
  back through their Bukkit registries. Unknown registry entries remain errors.
- The local `PlatformConverter` replacement checks the requested output mapping
  before invoking an inherited conversion. It prefers exact matches, then the
  most-specific registered input types, with deterministic ordering for unrelated
  interfaces. The shading exclusions keep embedded older copies from replacing it.
- `PaperVanillaProvider` rejects different materials before rebuilding the currency
  and uses native item similarity, including effective flags and custom data,
  without serializing the candidate inventory item. It returns independently
  mutable native stacks with the requested amount. Other item providers retain
  their own comparison rules.
- `PaperComponentCompatibility` checks actual API capabilities rather than relying
  solely on Minecraft version numbers. `PaperPreservedComponents` retains native
  component values and whether they came from material defaults. Unchanged values
  keep registry tags, nullable values, IDs, and fields TNIL cannot represent;
  edited components are rebuilt instead of silently restoring an old snapshot.
- `PaperNestedItems` captures children and use-remainder items as native snapshots,
  retaining their slots, amounts and complete metadata. It does not ask a custom
  provider to regenerate an existing nested item from its item ID.
- `PaperPersistentItemComponent` retains raw custom data when using the legacy
  component builder, including both Bukkit PDC and other plugins' tags. It accepts
  both `PLACED_ON` and `HIDE_PLACED_ON`-style flags, including `POTION_EFFECTS`.
  Its component JSON reader accepts the older PDC-only format as well as the new
  native data format. This adapter is not used to reconstruct native snapshots.
- `PaperInventoryCalculations` performs each inventory mutation on detached storage
  before committing it. Exceptions propagate without committing partial changes.
  Inventory and ender-chest handlers manage their own wallet snapshots after item
  calculations, avoiding the earlier pre-emptive wallet write.

Do not remove these adapters or the shading exclusions merely because TNIL's
snapshot version number changes. Verify the actual dependency contents and repeat
the runtime checks first.

## Native snapshot contract

A snapshot is an opaque transport value, not a partial description of an item.
It accepts amount and slot changes. An attempted material, component, flag, PDC
holder, or provider change is rejected when rendering, before the current staged
inventory operation commits. To intentionally edit metadata, obtain a native copy,
edit it with the server API, then capture a new `PaperItemSnapshot`. Native copies
and `cacheLocale()` never expose the saved instance.

The preservation guarantee concerns data in the native item supplied by the
provider. Configured template construction, provider availability, corrupted
inputs, Minecraft data-version compatibility, and whole multi-account transaction
atomicity remain separate concerns. Unsupported operations must fail visibly;
they must not silently drop fields and continue issuing altered currency.

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
6. Complete native snapshot persistence for every item material, opaque entity and
   bucket data, removed defaults, repeated overflow, and native nested contents.
   A deliberately failing component serializer proves that native transport does
   not call the component serialization pipeline.

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

### Follow-up item regression validation (2026-09-23)

The previous probes covered Bukkit PDC, but not raw custom data outside its
`PublicBukkitValues` compound or short configured flag names. On the released
0.1.5.2 artifact, all 17 initial targeted cases failed on both Paper 1.21.10 and
Leaf 1.21.11. Paper 1.21.10 still passed all 7,528 older checks. This exposed the
coverage gap: serialized overflow lost ItemsAdder's identity tag, while
`ItemFlag.valueOf("PLACED_ON")` rejected a supported flag alias.

The expanded probes also found that flags with no applicable data can disappear
on older Paper versions. Native similarity compares the effective item instead
of rejecting it because its configured flags differ from the stored flags.
Leaf 1.21.11 exposed registry-holder/default differences for `DAMAGE_TYPE` and
`PROVIDES_TRIM_MATERIAL`; both now use the existing native preservation adapter.

Preserving only raw custom data was still insufficient. Additional regressions
showed that component reconstruction changed opaque entity data, bucket data and
removed default components; an injected component serializer failure aborted an
overflow operation. Native snapshots now bypass that pipeline entirely for
existing item transport and nested children. The compatibility adapters remain
for paths that explicitly construct or inspect component-based item descriptions.

The probes and runner are in [`tests/paper-items`](../tests/paper-items/README.md).
They include the reported coin's model, name, lore, tooltip and raw identity tag,
mixed raw NBT/PDC, component JSON, all runtime flag aliases, nested containers,
independent snapshots, and inventory/ender-chest/ground overflow followed by
counting and removal. Items without the correct raw tag must not match the coin.
Additional checks cover native persistence, opaque metadata and component removals,
serializer independence, mutable-reference isolation and rejected snapshot edits.

| Server | Inventory audit | Targeted regressions | Failures |
| --- | ---: | ---: | ---: |
| Paper 1.21.4 build 232 | 8,395 | 26 | 0 |
| Paper 1.21.10 build 130 | 9,015 | 26 | 0 |
| Leaf 1.21.11 build 179 | 9,118 | 26 | 0 |
| Paper 26.2 build 112 | 9,312 | 26 | 0 |

All servers ran the final shaded Paper artifact on Java 26.0.2. The full reactor
clean build and PMD checks passed on Java 25. The user reported Leaf build 175;
the available upstream build tested here was 179. ItemsAdder-shaped data and a
custom-provider fixture were tested, not the proprietary ItemsAdder plugin.
Folia was built but was not tested on a running Folia server. No production
deployment or release publication was performed.

This prevents new tag loss. An already stripped item does not contain enough
information to safely reconstruct its original provider identity automatically.
