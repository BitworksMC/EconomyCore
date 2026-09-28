package net.tnemc.paper;

import io.papermc.paper.datacomponent.DataComponentType;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.JSONHelper;
import net.tnemc.item.component.SerialComponent;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.platform.ItemPlatform;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

final class PaperPersistentItemComponent implements SerialComponent<PaperItemStack, ItemStack> {

  // A fixed carrier material lets native custom_data be compared independently of item type/amount.
  // PDC is only the PublicBukkitValues child of custom_data; ItemsAdder uses a separate child.
  private final ItemStack data = new ItemStack(Material.STONE);

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

    final PaperPersistentItemComponent component = new PaperPersistentItemComponent();
    component.data.copyDataFrom(item, PaperPersistentItemComponent::customData);
    serialized.applyComponent(component);
    return serialized;
  }

  private static boolean customData(final DataComponentType type) {

    return type.getKey().toString().equals("minecraft:custom_data");
  }

  @Override
  public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

    serialized.<PaperPersistentItemComponent>component(identifier()).ifPresent(component ->
            item.copyDataFrom(component.data, PaperPersistentItemComponent::customData));
    final ItemMeta meta = item.getItemMeta();
    if(meta == null) {
      return item;
    }
    meta.addItemFlags(flags(serialized).toArray(ItemFlag[]::new));
    item.setItemMeta(meta);
    return item;
  }

  @Override
  public boolean check(final AbstractItemStack<ItemStack> original, final AbstractItemStack<ItemStack> compare) {

    return flags(original).equals(flags(compare))
           && SerialComponent.super.check(original, compare);
  }

  private static Set<ItemFlag> flags(final AbstractItemStack<ItemStack> item) {

    final Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
    for(final String flag : item.flags()) {
      final String normalized = flag.trim().toUpperCase(Locale.ROOT);
      final String name = normalized.startsWith("HIDE_") ? normalized : "HIDE_" + normalized;
      flags.add(ItemFlag.valueOf(name.equals("HIDE_POTION_EFFECTS") ? "HIDE_ADDITIONAL_TOOLTIP" : name));
    }
    return flags;
  }

  @Override
  public boolean similar(final SerialComponent<?, ?> component) {

    return component instanceof PaperPersistentItemComponent other && data.isSimilar(other.data);
  }

  @Override
  public boolean empty() {

    return !data.hasItemMeta();
  }

  @Override
  public JSONObject toJSON() {

    final JSONObject json = new JSONObject();
    if(!empty()) {
      json.put("custom_data", Base64.getEncoder().encodeToString(data.serializeAsBytes()));
    }
    return json;
  }

  @Override
  public void readJSON(final JSONHelper json, final ItemPlatform<PaperItemStack, ItemStack, ?> platform) {

    data.setItemMeta(null);
    if(json.has("custom_data")) {
      data.copyDataFrom(ItemStack.deserializeBytes(Base64.getDecoder().decode(json.getString("custom_data"))),
                        PaperPersistentItemComponent::customData);
    } else if(json.has("data")) {
      // Read the PDC-only representation written by 0.1.5.2.
      final ItemMeta meta = data.getItemMeta();
      try {
        meta.getPersistentDataContainer().readFromBytes(Base64.getDecoder().decode(json.getString("data")), true);
        data.setItemMeta(meta);
      } catch(final IOException exception) {
        throw new IllegalArgumentException("Cannot deserialize persistent item data", exception);
      }
    }
  }
}
