import java.util.*;
import java.util.logging.Level;
import net.tnemc.item.paper.*;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.JukeboxPlayable;
import net.tnemc.paper.PaperInventoryCalculations;
import org.bukkit.plugin.java.JavaPlugin;

public final class InventoryAudit extends JavaPlugin {
  private final Map<String, List<String>> failures = new TreeMap<>();
  private int attempts;

  private void check(String label, Runnable action) {
    attempts++;
    try {
      action.run();
    } catch(Throwable failure) {
      final String key = failure.getClass().getSimpleName() + ": " + failure.getMessage()
          + " at " + failure.getStackTrace()[0];
      failures.computeIfAbsent(key, ignored -> new ArrayList<>()).add(label);
    }
  }

  @Override
  public void onEnable() {
    Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 200L);
    Bukkit.getScheduler().runTask(this, () -> {
      final var gold = new PaperItemStack().of("GOLD_INGOT", 1);
      final var calculations = new PaperInventoryCalculations();
      final Inventory inventory = Bukkit.createInventory(null, 9);
      for(final Material material : Material.values()) {
        if(material.isLegacy() || !material.isItem() || material.isAir()) continue;
        final ItemStack item = new ItemStack(material);
        check("serialize:" + material, () -> new PaperItemStack().of(item));
        check("native-snapshot:" + material, () -> {
          final var snapshot = new net.tnemc.paper.PaperItemSnapshot(item);
          final var restored = net.tnemc.paper.PaperItemSnapshot.fromJSON(snapshot.toJSON());
          if(!item.equals(restored.copy(item.getAmount()))) throw new AssertionError("Native snapshot changed " + material);
        });
        check("roundtrip:" + material, () -> {
          final var stack = new PaperItemStack().of(item);
          stack.markDirty();
          final var rebuilt = stack.provider().locale(stack, 1);
          if(rebuilt == null || rebuilt.getType() != material) throw new AssertionError("Lost material");
          new PaperItemStack().of(rebuilt);
          if(!item.isSimilar(rebuilt)) {
            getLogger().info("DIFF " + material + " before=" + item.getItemMeta().getAsString() + " after=" + rebuilt.getItemMeta().getAsString());
            throw new AssertionError("Roundtrip changed " + material);
          }
        });
        check("gold-count:" + material, () -> {
          inventory.clear();
          inventory.setItem(0, new ItemStack(Material.GOLD_INGOT, 7));
          inventory.setItem(1, item);
          final int expected = material == Material.GOLD_INGOT ? 8 : 7;
          final int actual = calculations.count(gold, inventory, false, false);
          if(actual != expected) throw new AssertionError("Count expected=" + expected + " actual=" + actual);
        });
        check("self-count:" + material, () -> {
          inventory.clear();
          inventory.setItem(1, item);
          final var currency = new PaperItemStack().of(item.clone());
          if(calculations.count(currency, inventory, false, false) != 1) throw new AssertionError("Identical item not counted " + material);
        });
        check("gold-remove:" + material, () -> {
          inventory.clear();
          inventory.setItem(0, new ItemStack(Material.GOLD_INGOT, 7));
          inventory.setItem(1, item.clone());
          final int expected = material == Material.GOLD_INGOT ? 8 : 7;
          if(calculations.removeAll(gold, inventory, false, false) != expected) throw new AssertionError("Wrong removal count");
          if(material != Material.GOLD_INGOT && !item.equals(inventory.getItem(1))) throw new AssertionError("Unrelated item changed");
        });
      }
      check("populated-shulker", () -> {
        final ItemStack item = new ItemStack(Material.SHULKER_BOX);
        final BlockStateMeta meta = (BlockStateMeta)item.getItemMeta();
        final ShulkerBox box = (ShulkerBox)meta.getBlockState();
        box.getInventory().setItem(7, new ItemStack(Material.GOLD_INGOT, 19));
        meta.setBlockState(box);
        item.setItemMeta(meta);
        roundtrip(item);
      });
      check("populated-bundle", () -> {
        final ItemStack item = new ItemStack(Material.BUNDLE);
        final BundleMeta meta = (BundleMeta)item.getItemMeta();
        meta.setItems(List.of(new ItemStack(Material.GOLD_INGOT, 19)));
        item.setItemMeta(meta);
        roundtrip(item);
      });
      check("charged-crossbow", () -> {
        final ItemStack item = new ItemStack(Material.CROSSBOW);
        final CrossbowMeta meta = (CrossbowMeta)item.getItemMeta();
        meta.addChargedProjectile(new ItemStack(Material.ARROW));
        item.setItemMeta(meta);
        roundtrip(item);
      });
      variants();
      mutableComponents();
      failuresAreAtomic(calculations);
      nestedRemoval(calculations);
      for(final var failure : failures.entrySet()) {
        getLogger().severe("AUDIT_FAILURE " + failure.getKey() + " count=" + failure.getValue().size()
            + " examples=" + failure.getValue().subList(0, Math.min(5, failure.getValue().size())));
      }
      getLogger().info("AUDIT_RESULT attempts=" + attempts + " failingGroups=" + failures.size());
      Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 40L);
    });
  }

  private void roundtrip(ItemStack item) {
    final var serialized = new PaperItemStack().of(item);
    serialized.markDirty();
    final var rebuilt = serialized.provider().locale(serialized);
    if(!item.isSimilar(rebuilt)) throw new AssertionError("Roundtrip changed " + item.getType());
  }

  private void variants() {
    for(final var song : Registry.JUKEBOX_SONG) {
      check("song:" + song.getKey(), () -> {
        final var item = new ItemStack(Material.MUSIC_DISC_13);
        item.setData(DataComponentTypes.JUKEBOX_PLAYABLE, JukeboxPlayable.jukeboxPlayable(song));
        roundtrip(item);
        final var converter = PaperItemPlatform.instance().converter();
        if(converter.convert(converter.convert(song, String.class), org.bukkit.JukeboxSong.class) != song) throw new AssertionError("Song key roundtrip");
      });
    }
    for(final var instrument : Registry.INSTRUMENT) {
      check("instrument:" + instrument.getKey(), () -> {
        final var item = new ItemStack(Material.GOAT_HORN);
        item.setData(DataComponentTypes.INSTRUMENT, instrument);
        roundtrip(item);
      });
    }
    check("named-enchanted-coin", () -> {
      final var item = new ItemStack(Material.GOLD_INGOT, 17);
      final var meta = item.getItemMeta();
      meta.displayName(net.kyori.adventure.text.Component.text("Gold Coin"));
      meta.lore(List.of(net.kyori.adventure.text.Component.text("Test currency")));
      meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
      item.setItemMeta(meta);
      roundtrip(item);
    });
    check("persistent-data-and-flags", () -> {
      final var item = new ItemStack(Material.GOLD_INGOT, 17);
      final var meta = item.getItemMeta();
      meta.getPersistentDataContainer().set(new NamespacedKey(this, "coin"), PersistentDataType.STRING, "gold");
      meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
      item.setItemMeta(meta);
      roundtrip(item);
      final var currency = new PaperItemStack().of(item);
      if(currency.provider().similar(currency, new ItemStack(Material.GOLD_INGOT))) throw new AssertionError("Unmarked item counted as marked coin");
      if(!currency.provider().similar(currency, item.clone())) throw new AssertionError("Marked coin did not match itself");
    });
    check("potion-effects", () -> {
      final var item = new ItemStack(Material.POTION);
      final var meta = (PotionMeta)item.getItemMeta();
      meta.setBasePotionType(PotionType.STRONG_HEALING);
      meta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 42, 2, true, false, false), true);
      item.setItemMeta(meta);
      roundtrip(item);
    });
    check("suspicious-stew", () -> {
      final var item = new ItemStack(Material.SUSPICIOUS_STEW);
      final var meta = (SuspiciousStewMeta)item.getItemMeta();
      meta.addCustomEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0), true);
      item.setItemMeta(meta);
      roundtrip(item);
    });
  }

  private void mutableComponents() {
    check("configured-attribute", () -> {
      final var currency = new PaperItemStack().of("GOLD_INGOT", 1);
      final var attributes = new net.tnemc.item.paper.platform.impl.modern.PaperAttributeModifiersComponent();
      final var modifier = new net.tnemc.item.component.helper.AttributeModifier("minecraft:attack_damage", "audit:power",
          PaperItemPlatform.instance().converter().convert(org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, String.class));
      modifier.setAmount(2.5);
      modifier.setSlot(net.tnemc.item.component.helper.EquipSlot.MAIN_HAND);
      attributes.modifiers().add(modifier);
      currency.applyComponent(attributes);
      final var item = currency.provider().locale(currency);
      if(!item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().get(0).modifier().getKey().toString().equals("audit:power")) throw new AssertionError("Modifier ID lost");
      if(!currency.provider().similar(currency, item)) throw new AssertionError("Configured attribute mismatch");
    });
    check("modified-tool-component", () -> {
      final var currency = new PaperItemStack().of("DIAMOND_PICKAXE", 1);
      final var tool = (net.tnemc.item.paper.platform.impl.modern.PaperToolComponent)currency.component("tool").orElseThrow();
      tool.blockDamage(12);
      currency.markDirty();
      final var item = currency.provider().locale(currency);
      if(item.getData(DataComponentTypes.TOOL).damagePerBlock() != 12) throw new AssertionError("Snapshot ignored edits");
    });
    check("typed-key-and-keyed-conversions", () -> {
      final var converter = PaperItemPlatform.instance().converter();
      final var typed = io.papermc.paper.registry.TypedKey.create(io.papermc.paper.registry.RegistryKey.MOB_EFFECT, "minecraft:poison");
      if(!converter.convert(typed, String.class).equals("minecraft:poison")) throw new AssertionError("Typed key lost namespace");
      final org.bukkit.Keyed custom = () -> new NamespacedKey("custom", "value");
      if(!converter.convert(custom, String.class).equals("custom:value")) throw new AssertionError("Unknown keyed implementation rejected");
    });
    for(final var damage : Registry.DAMAGE_TYPE) {
      check("damage-type:" + damage.getKey(), () -> {
        final var converter = PaperItemPlatform.instance().converter();
        if(converter.convert(converter.convert(damage, String.class), org.bukkit.damage.DamageType.class) != damage) throw new AssertionError("Damage type roundtrip failed");
      });
    }
  }

  private void failuresAreAtomic(PaperInventoryCalculations calculations) {
    PaperItemPlatform.instance().addItemProvider(new VanillaProvider() {
      @Override public String identifier() { return "audit-failure"; }
      @Override public boolean similar(net.tnemc.item.AbstractItemStack<? extends ItemStack> original, ItemStack compare) {
        if(compare.getType() == Material.DIRT) throw new IllegalArgumentException("Injected comparison failure");
        return compare.getType() == Material.GOLD_INGOT;
      }
    });
    check("atomic-remove", () -> {
      final Inventory inventory = Bukkit.createInventory(null, 9);
      inventory.setItem(0, new ItemStack(Material.GOLD_INGOT, 7));
      inventory.setItem(1, new ItemStack(Material.DIRT));
      final var currency = new PaperItemStack().of("GOLD_INGOT", 8).setItemProvider("audit-failure");
      try {
        calculations.removeItem(currency, inventory, false, false);
        throw new AssertionError("Failure was swallowed");
      } catch(IllegalArgumentException expected) {
        if(inventory.getItem(0) == null || inventory.getItem(0).getAmount() != 7) throw new AssertionError("Partial debit");
      }
    });
    check("atomic-give", () -> {
      final Inventory inventory = Bukkit.createInventory(null, 9);
      final var invalid = new PaperItemStack().material("invalid:missing").amount(1);
      try {
        calculations.giveItems(List.of(new PaperItemStack().of("GOLD_INGOT", 7), invalid), inventory, false, false);
        throw new AssertionError("Failure was swallowed");
      } catch(IllegalArgumentException expected) {
        if(!inventory.isEmpty()) throw new AssertionError("Partial credit");
      }
    });
    check("cached-amount", () -> {
      final var currency = new PaperItemStack().of("GOLD_INGOT", 1);
      final var first = currency.provider().locale(currency, 4);
      final var second = currency.provider().locale(currency, 7);
      if(first == second || first.getAmount() != 4 || second.getAmount() != 7) throw new AssertionError("Shared or incorrect cached amount");
    });
  }

  private void nestedRemoval(PaperInventoryCalculations calculations) {
    check("container-currency-partial-removal", () -> {
      final Inventory inventory = Bukkit.createInventory(null, 9);
      inventory.setItem(0, new ItemStack(Material.SHULKER_BOX));
      inventory.setItem(1, new ItemStack(Material.SHULKER_BOX));
      inventory.setItem(2, new ItemStack(Material.SHULKER_BOX));
      final var currency = new PaperItemStack().of("SHULKER_BOX", 2);
      if(calculations.removeItem(currency, inventory, true, true) != 0) throw new AssertionError("Failed partial removal");
      if(calculations.count(currency, inventory, true, true) != 1) throw new AssertionError("Reinserted removed container");
    });
    check("nested-count-and-removal", () -> {
      final Inventory inventory = Bukkit.createInventory(null, 9);
      final var boxItem = new ItemStack(Material.SHULKER_BOX);
      final var boxMeta = (BlockStateMeta)boxItem.getItemMeta();
      final var box = (ShulkerBox)boxMeta.getBlockState();
      box.getInventory().setItem(7, new ItemStack(Material.GOLD_INGOT, 19));
      box.getInventory().setItem(8, new ItemStack(Material.MUSIC_DISC_13));
      boxMeta.setBlockState(box);
      boxItem.setItemMeta(boxMeta);
      final var bundleItem = new ItemStack(Material.BUNDLE);
      final var bundleMeta = (BundleMeta)bundleItem.getItemMeta();
      bundleMeta.setItems(List.of(new ItemStack(Material.GOLD_INGOT, 17), new ItemStack(Material.HONEY_BOTTLE)));
      bundleItem.setItemMeta(bundleMeta);
      inventory.setItem(0, boxItem);
      inventory.setItem(1, bundleItem);
      final var currency = new PaperItemStack().of("GOLD_INGOT", 1);
      if(calculations.count(currency, inventory, true, true) != 36) throw new AssertionError("Wrong nested balance");
      if(calculations.removeAll(currency, inventory, true, true) != 36) throw new AssertionError("Wrong nested removal count");
      if(calculations.count(currency, inventory, true, true) != 0) throw new AssertionError("Gold remains");
      final var after = (ShulkerBox)((BlockStateMeta)inventory.getItem(0).getItemMeta()).getBlockState();
      if(after.getInventory().getItem(8).getType() != Material.MUSIC_DISC_13) throw new AssertionError("Lost disc");
      if(((BundleMeta)inventory.getItem(1).getItemMeta()).getItems().get(0).getType() != Material.HONEY_BOTTLE) throw new AssertionError("Lost honey");
    });
  }
}
