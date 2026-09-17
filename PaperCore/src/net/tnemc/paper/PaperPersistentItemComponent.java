package net.tnemc.paper;

import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.JSONHelper;
import net.tnemc.item.component.SerialComponent;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.platform.ItemPlatform;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;

final class PaperPersistentItemComponent implements SerialComponent<PaperItemStack, ItemStack> {

  private PersistentDataContainer data;

  @Override
  public String identifier() {

    return "tne_persistent_data";
  }

  @Override
  public boolean enabled(final String version) {

    return true;
  }

  @Override
  public boolean appliesTo(final ItemStack item) {

    return !item.getType().isAir();
  }

  @Override
  public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

    final ItemMeta meta = item.getItemMeta();
    if(meta != null) {
      final PaperPersistentItemComponent component = new PaperPersistentItemComponent();
      component.data = meta.getPersistentDataContainer();
      serialized.applyComponent(component);
    }
    return serialized;
  }

  @Override
  public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

    final ItemMeta meta = item.getItemMeta();
    if(meta == null) {
      return item;
    }
    serialized.<PaperPersistentItemComponent>component(identifier()).ifPresent(component -> {
      if(component.data != null) {
        component.data.copyTo(meta.getPersistentDataContainer(), true);
      }
    });
    meta.addItemFlags(serialized.flags().stream().map(ItemFlag::valueOf).toArray(ItemFlag[]::new));
    item.setItemMeta(meta);
    return item;
  }

  @Override
  public boolean check(final AbstractItemStack<ItemStack> original, final AbstractItemStack<ItemStack> compare) {

    return new HashSet<>(original.flags()).equals(new HashSet<>(compare.flags()))
           && SerialComponent.super.check(original, compare);
  }

  @Override
  public boolean similar(final SerialComponent<?, ?> component) {

    return component instanceof PaperPersistentItemComponent other && Objects.equals(data, other.data);
  }

  @Override
  public boolean empty() {

    return data == null || data.isEmpty();
  }

  @Override
  public JSONObject toJSON() {

    final JSONObject json = new JSONObject();
    try {
      if(data != null) {
        json.put("data", Base64.getEncoder().encodeToString(data.serializeToBytes()));
      }
    } catch(final IOException exception) {
      throw new IllegalStateException("Cannot serialize persistent item data", exception);
    }
    return json;
  }

  @Override
  public void readJSON(final JSONHelper json, final ItemPlatform<PaperItemStack, ItemStack, ?> platform) {

    data = Objects.requireNonNull(Bukkit.getItemFactory().getItemMeta(Material.STONE)).getPersistentDataContainer();
    if(json.has("data")) {
      try {
        data.readFromBytes(Base64.getDecoder().decode(json.getString("data")), true);
      } catch(final IOException exception) {
        throw new IllegalArgumentException("Cannot deserialize persistent item data", exception);
      }
    }
  }
}
