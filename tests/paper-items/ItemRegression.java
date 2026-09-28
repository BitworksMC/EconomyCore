import io.papermc.paper.datacomponent.DataComponentTypes;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.JSONHelper;
import net.tnemc.item.component.SerialComponent;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import net.tnemc.item.providers.ItemProvider;
import net.tnemc.paper.PaperInventoryCalculations;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;

/** Run only on an isolated test server: this plugin stops it after the checks. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class ItemRegression extends JavaPlugin {
  private int checks;
  private int failures;

  @Override
  public void onEnable() {
    Bukkit.getScheduler().runTask(this, () -> {
      check("raw-custom-data", () -> roundtrip(coin()));
      check("raw-custom-data-with-pdc", () -> {
        final ItemStack item = coin();
        item.editMeta(meta -> meta.getPersistentDataContainer().set(
            new NamespacedKey("regression", "owner"), PersistentDataType.STRING, "test"));
        roundtrip(item);
      });
      check("custom-data-identity", () -> {
        final PaperItemStack currency = new PaperItemStack(coin());
        require(currency.provider().similar(currency, coin()), "Coin must match itself");
        final ItemStack plain = coin();
        plain.getDataTypes().stream().filter(type -> type.getKey().toString().equals("minecraft:custom_data"))
            .forEach(plain::resetData);
        require(!currency.provider().similar(currency, plain), "Coin with missing identity tag matched currency");
        final ItemStack other = coin("other");
        require(!currency.provider().similar(currency, other), "Different namespace matched coin");
      });
      check("custom-data-json-roundtrip", () -> {
        final PaperItemStack original = new PaperItemStack(coin());
        final SerialComponent saved = original.components().get("tne_persistent_data");
        final PaperItemStack restored = new PaperItemStack("CLOCK", 1);
        final SerialComponent reader = restored.components().get("tne_persistent_data");
        reader.readJSON(new JSONHelper(saved.toJSON()), PaperItemPlatform.instance());
        require(saved.similar(reader), "Saved custom_data changed after reading JSON");
        reader.readJSON(new JSONHelper(new net.tnemc.libs.json.JSONObject()), PaperItemPlatform.instance());
        require(reader.empty(), "Reading empty JSON retained old data");
      });
      check("legacy-pdc-json", () -> {
        final ItemStack item = new ItemStack(Material.CLOCK);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(
            new NamespacedKey("regression", "legacy"), PersistentDataType.STRING, "coin"));
        final var json = new net.tnemc.libs.json.JSONObject();
        try {
          json.put("data", Base64.getEncoder().encodeToString(item.getItemMeta().getPersistentDataContainer().serializeToBytes()));
        } catch (IOException error) { throw new AssertionError(error); }
        final PaperItemStack restored = new PaperItemStack("CLOCK", 1);
        final SerialComponent reader = restored.components().get("tne_persistent_data");
        reader.readJSON(new JSONHelper(json), PaperItemPlatform.instance());
        restored.markDirty();
        require(item.isSimilar(restored.provider().locale(restored)), "Cannot read 0.1.5.2 PDC data");
      });
      check("component-edit-keeps-custom-data", () -> {
        final PaperItemStack currency = new PaperItemStack(coin());
        currency.customName(net.kyori.adventure.text.Component.text("Edited coin"));
        final ItemStack result = currency.provider().locale(currency, 3);
        final ItemStack expected = coin();
        expected.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Edited coin")));
        require(result.isSimilar(expected) && result.getAmount() == 3, "Edit lost native data or amount");
      });
      for (ItemFlag flag : ItemFlag.values()) {
        check("flag-alias:" + flag.name(), () -> {
          final PaperItemStack shortName = new PaperItemStack("STONE", 1).flags(List.of(flag.name().substring(5)));
          final PaperItemStack fullName = new PaperItemStack("STONE", 1).flags(List.of(flag.name()));
          shortName.markDirty();
          fullName.markDirty();
          require(shortName.provider().locale(shortName).isSimilar(fullName.provider().locale(fullName)), "Alias changed flags");
          require(shortName.provider().similar(shortName, fullName), "Alias comparison differs");
        });
      }
      check("legacy-potion-flag", () -> {
        final PaperItemStack currency = new PaperItemStack("POTION", 1).flags(List.of("POTION_EFFECTS"));
        currency.markDirty();
        currency.provider().locale(currency);
      });
      check("configured-placed-on-count", () -> {
        final PaperItemStack currency = new PaperItemStack("GOLD_INGOT", 5).flags(List.of("PLACED_ON"));
        currency.markDirty();
        final var calculations = new PaperInventoryCalculations();
        final Inventory inventory = Bukkit.createInventory(null, 9);
        calculations.giveItems(List.of(currency), inventory, false, false);
        require(calculations.count(currency, inventory, false, false) == 5, "Configured flag prevents balance counting");
      });
      check("nested-shulker-custom-data", () -> {
        final ItemStack box = new ItemStack(Material.SHULKER_BOX);
        final BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        final ShulkerBox state = (ShulkerBox) meta.getBlockState();
        state.getInventory().setItem(7, coin());
        meta.setBlockState(state);
        box.setItemMeta(meta);
        roundtrip(box);
      });
      check("nested-bundle-custom-data", () -> {
        final ItemStack bundle = new ItemStack(Material.BUNDLE);
        final BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(List.of(coin()));
        bundle.setItemMeta(meta);
        roundtrip(bundle);
      });
      check("full-inventory-ender-chest-ground", this::overflow);
      check("snapshot-and-cache-isolation", () -> {
        final ItemStack original = coin();
        final PaperItemStack currency = new PaperItemStack(original);
        original.editMeta(meta -> meta.getPersistentDataContainer().set(
            new NamespacedKey("regression", "later"), PersistentDataType.INTEGER, 1));
        final ItemStack first = currency.provider().locale(currency, 7);
        first.setAmount(1);
        first.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("changed")));
        final ItemStack second = currency.provider().locale(currency, 9);
        require(second.getAmount() == 9 && second.isSimilar(coin()), "Input or output mutation changed cached currency");
      });
      check("opaque-entity-data-transport", () -> opaqueTransport(Bukkit.getItemFactory().createItemStack(
          "minecraft:pig_spawn_egg[minecraft:entity_data={id:\"minecraft:pig\",Silent:1b,NoAI:1b,Tags:[\"opaque\"]}]")));
      check("opaque-bucket-data-transport", () -> opaqueTransport(Bukkit.getItemFactory().createItemStack(
          "minecraft:tropical_fish_bucket[minecraft:bucket_entity_data={BucketVariantTag:12345,Health:5.0f,NoAI:1b}]")));
      check("removed-default-component-transport", () -> {
        final ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        item.unsetData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        opaqueTransport(item);
      });
      check("transport-does-not-call-component-serializers", this::serializerFailure);
      check("snapshot-edit-fails-before-inventory-commit", () -> {
        final PaperItemStack snapshot = overflowOnce(coin());
        snapshot.customName(net.kyori.adventure.text.Component.text("unsupported edit"));
        final Inventory inventory = Bukkit.createInventory(null, 9);
        try {
          new PaperInventoryCalculations().giveItems(List.of(new PaperItemStack("GOLD_INGOT", 3), snapshot), inventory, false, false);
          throw new AssertionError("Snapshot silently ignored a metadata edit");
        } catch (IllegalStateException expected) {
          require(inventory.isEmpty(), "A failed snapshot edit partially credited the inventory");
        }
      });
      getLogger().info("ITEM_REGRESSION_RESULT checks=" + checks + " failures=" + failures);
      Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 1L);
    });
  }

  private ItemStack coin() {
    return coin("myitems");
  }

  private PaperItemStack overflowOnce(ItemStack nativeItem) {
    final ItemStack original = nativeItem.clone();
    final String providerID = "regression-opaque";
    PaperItemPlatform.instance().addItemProvider(new ItemProvider<ItemStack>() {
      public String identifier() { return providerID; }
      public boolean appliesTo(AbstractItemStack<? extends ItemStack> original, ItemStack item) { return false; }
      public boolean similar(AbstractItemStack<? extends ItemStack> original, ItemStack item) { return nativeItem.isSimilar(item); }
      public ItemStack locale(AbstractItemStack<? extends ItemStack> original, int amount) { return nativeItem; }
    });
    // A plain descriptor avoids deserializing the supplied provider item before the transfer.
    final PaperItemStack descriptor = new PaperItemStack().material(nativeItem.getType().getKey().toString())
        .amount(nativeItem.getAmount()).setItemProvider(providerID);
    final Inventory full = Bukkit.createInventory(null, 9);
    fill(full);
    final var left = new PaperInventoryCalculations().giveItems(List.of(descriptor), full, false, false);
    require(left.size() == 1, "Expected one complete overflow stack");
    require(nativeItem.equals(original), "Payout mutated the provider's item");
    return left.iterator().next();
  }

  private void opaqueTransport(ItemStack nativeItem) {
    final ItemStack original = nativeItem.clone();
    PaperItemStack snapshot = overflowOnce(nativeItem);
    final PaperInventoryCalculations calculations = new PaperInventoryCalculations();
    // Repeated full inventories model retry, ender-chest and container overflow boundaries.
    for (int pass = 0; pass < 4; pass++) {
      final Inventory full = Bukkit.createInventory(null, 27);
      fill(full);
      snapshot = calculations.giveItems(List.of(snapshot), full, false, false).iterator().next();
      require(original.equals(snapshot.provider().locale(snapshot)), "Overflow changed an opaque component");
    }
    final var json = snapshot.toJSON();
    require(json != null, "Missing native snapshot persistence");
    final PaperItemStack restored;
    try {
      restored = snapshot.of(json);
    } catch (net.tnemc.libs.json.parser.ParseException error) { throw new AssertionError(error); }
    require(original.equals(restored.provider().locale(restored)), "Native persistence lost metadata");
    final ItemStack exposed = restored.cacheLocale();
    exposed.setAmount(19);
    exposed.setItemMeta(null);
    require(original.equals(restored.provider().locale(restored)), "Snapshot exposed a mutable native reference");
    final Inventory destination = Bukkit.createInventory(null, 27);
    require(calculations.giveItems(List.of(restored), destination, false, false).isEmpty(), "Cannot deposit restored item");
    require(original.equals(destination.getItem(0)), "Final deposit lost native item state");
    require(calculations.count(restored, destination, false, false) == original.getAmount(), "Cannot count restored item");
    require(calculations.removeAll(restored, destination, false, false) == original.getAmount(), "Cannot debit restored item");
  }

  private void serializerFailure() {
    final boolean[] armed = {true};
    PaperItemPlatform.instance().addSerializer(new net.tnemc.item.platform.serialize.ItemSerializer<PaperItemStack, ItemStack>() {
      public String identifier() { return "regression-unavailable-serializer"; }
      public boolean enabled(String version) { return armed[0]; }
      public PaperItemStack serialize(ItemStack item, PaperItemStack serialized) {
        throw new AssertionError("Native transport called a component serializer");
      }
    });
    try {
      final ItemStack box = new ItemStack(Material.SHULKER_BOX);
      final BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
      final ShulkerBox state = (ShulkerBox) meta.getBlockState();
      state.getInventory().setItem(7, coin());
      meta.setBlockState(state);
      box.setItemMeta(meta);
      opaqueTransport(box);
    } finally {
      armed[0] = false;
    }
  }

  private ItemStack coin(String namespace) {
    // This is raw custom_data, deliberately outside Bukkit's PublicBukkitValues/PDC.
    final ItemStack item = Bukkit.getItemFactory().createItemStack(
        "minecraft:clock[minecraft:custom_data={itemsadder:{namespace:\"" + namespace + "\",id:\"gold_coin\"},"
        + "other_plugin:{bytes:[B;1b,2b],longs:[L;3L,4L],nested:{value:42}}}]");
    item.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("myitems:coins"));
    item.setData(DataComponentTypes.TOOLTIP_STYLE, net.kyori.adventure.key.Key.key("myitems:currency/gold"));
    item.editMeta(meta -> {
      meta.itemName(net.kyori.adventure.text.Component.text("Gold Coin"));
      meta.lore(List.of(net.kyori.adventure.text.Component.text("CURRENCY"), net.kyori.adventure.text.Component.text("VALUE: 200")));
      meta.setCustomModelData(8006);
    });
    return item;
  }

  private void overflow() {
    final PaperInventoryCalculations calculations = new PaperInventoryCalculations();
    // Exercise a custom provider and the actual leftover serialization path, without requiring a paid plugin.
    PaperItemPlatform.instance().addItemProvider(new ItemProvider<ItemStack>() {
      public String identifier() { return "regression-custom"; }
      public boolean appliesTo(AbstractItemStack<? extends ItemStack> original, ItemStack item) { return coin().isSimilar(item); }
      public boolean similar(AbstractItemStack<? extends ItemStack> original, ItemStack item) { return coin().isSimilar(item); }
      public ItemStack locale(AbstractItemStack<? extends ItemStack> original, int amount) {
        final ItemStack item = coin();
        item.setAmount(amount);
        return item;
      }
    });
    final PaperItemStack currency = new PaperItemStack("CLOCK", 70).setItemProvider("regression-custom");
    final Inventory inventory = Bukkit.createInventory(null, 36);
    final Inventory enderChest = Bukkit.createInventory(null, 27);
    fill(inventory);
    fill(enderChest);
    inventory.setItem(0, coin()); // One partial stack can receive 63 of the 70 coins.
    enderChest.setItem(0, coin());
    enderChest.getItem(0).setAmount(60); // Four more fit here; three must drop.
    final var left = calculations.giveItems(List.of(currency), inventory, false, false);
    require(calculations.count(currency, inventory, false, false) == 64, "Inventory coin count changed");
    final var ground = calculations.giveItems(left, enderChest, false, false);
    require(calculations.count(currency, enderChest, false, false) == 64, "Ender chest lost currency identity");
    int dropped = 0;
    for (PaperItemStack remaining : ground) {
      final ItemStack item = remaining.provider().locale(remaining);
      require(coin().isSimilar(item), "Ground overflow lost currency identity");
      final var entity = Bukkit.getWorlds().getFirst().dropItem(Bukkit.getWorlds().getFirst().getSpawnLocation(), item);
      require(coin().isSimilar(entity.getItemStack()), "Dropped entity lost currency identity");
      dropped += entity.getItemStack().getAmount();
      entity.remove();
    }
    require(dropped == 3, "Wrong ground overflow amount: " + dropped);
    currency.amount(64);
    require(calculations.removeItem(currency, enderChest, false, false) == 0, "Cannot spend overflow currency");
    require(calculations.count(currency, enderChest, false, false) == 0, "Overflow debit did not remove coins");
  }

  private void fill(Inventory inventory) {
    for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, new ItemStack(Material.STONE, 64));
  }

  private void roundtrip(ItemStack item) {
    final PaperItemStack serialized = new PaperItemStack(item);
    serialized.markDirty();
    final ItemStack result = serialized.provider().locale(serialized);
    require(item.isSimilar(result), "Roundtrip lost item metadata: " + item.getType());
    require(item.getAmount() == result.getAmount(), "Roundtrip changed amount");
    require(serialized.provider().similar(serialized, result), "Rebuilt item no longer matches currency");
  }

  private void check(String label, Runnable test) {
    checks++;
    try { test.run(); }
    catch (Throwable error) {
      failures++;
      getLogger().log(Level.SEVERE, "ITEM_REGRESSION_FAILURE " + label, error);
    }
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
