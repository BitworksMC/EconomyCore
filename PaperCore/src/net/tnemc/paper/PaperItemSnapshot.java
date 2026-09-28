package net.tnemc.paper;

import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.providers.ItemProvider;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.json.simple.JSONObject;

import java.util.Base64;
import java.util.Objects;

/**
 * An opaque native item used when transporting existing items, including overflow.
 * Only its amount and inventory slot are mutable. No TNIL serializer or component
 * applicator participates in capture, copying, comparison, or persistence.
 */
public final class PaperItemSnapshot extends PaperItemStack {

  private static final String PROVIDER_ID = "tne_native_snapshot";
  private static final ItemProvider<ItemStack> PROVIDER = new SnapshotProvider();

  private final ItemStack snapshot;

  public PaperItemSnapshot(final ItemStack item) {

    Objects.requireNonNull(item, "Cannot capture a null item");
    if(item.getType().isAir() || item.getAmount() <= 0) {
      throw new IllegalArgumentException("Cannot capture an empty item");
    }
    snapshot = item.clone();
    super.material(snapshot.getType().getKey().toString());
    super.amount(snapshot.getAmount());
    super.setItemProvider(PROVIDER_ID);
  }

  public ItemStack copy(final int amount) {

    requireUnmodified();
    if(amount <= 0) {
      throw new IllegalArgumentException("Native item amount must be positive");
    }
    final ItemStack copy = snapshot.clone();
    copy.setAmount(amount);
    return copy;
  }

  private void requireUnmodified() {

    if(Material.matchMaterial(material()) != snapshot.getType() || !components().isEmpty()
       || !flags().isEmpty() || !persistentHolder().getData().isEmpty()
       || !PROVIDER_ID.equals(itemProvider())) {
      throw new IllegalStateException("Native item snapshots support amount and slot changes only; edit a native copy and capture it again");
    }
  }

  @Override
  public ItemProvider<ItemStack> provider() {

    return PROVIDER;
  }

  @Override
  public ItemStack cacheLocale() {

    return copy(amount());
  }

  @Override
  public void updateCache(final ItemStack item) {

    throw new UnsupportedOperationException("Capture a new native item snapshot instead of replacing its cache");
  }

  @Override
  public PaperItemStack of(final ItemStack item) {

    return new PaperItemSnapshot(item);
  }

  @Override
  public PaperItemStack of(final String material, final int amount) {

    return new PaperItemSnapshot(new ItemStack(Objects.requireNonNull(Material.matchMaterial(material)), amount));
  }

  @Override
  public JSONObject toJSON() {

    final JSONObject json = new JSONObject();
    json.put("nativeItem", Base64.getEncoder().encodeToString(copy(amount()).serializeAsBytes()));
    json.put("slot", slot());
    return json;
  }

  @Override
  public PaperItemStack of(final JSONObject json) {

    return fromJSON(json);
  }

  public static PaperItemSnapshot fromJSON(final JSONObject json) {

    if(!(json.get("nativeItem") instanceof String encoded)) {
      throw new IllegalArgumentException("Missing native item snapshot");
    }
    final PaperItemSnapshot result = new PaperItemSnapshot(ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)));
    if(json.get("slot") instanceof Number slot) {
      result.slot(slot.intValue());
    }
    return result;
  }

  private static final class SnapshotProvider implements ItemProvider<ItemStack> {

    @Override
    public String identifier() {

      return PROVIDER_ID;
    }

    @Override
    public boolean appliesTo(final AbstractItemStack<? extends ItemStack> original, final ItemStack item) {

      return original instanceof PaperItemSnapshot && similar(original, item);
    }

    @Override
    public boolean similar(final AbstractItemStack<? extends ItemStack> original, final ItemStack compare) {

      return compare != null && !compare.getType().isAir() && locale(original, 1).isSimilar(compare);
    }

    @Override
    public boolean similar(final AbstractItemStack<? extends ItemStack> original,
                           final AbstractItemStack<? extends ItemStack> compare) {

      return compare instanceof PaperItemStack stack && similar(original, stack.provider().locale(stack, 1));
    }

    @Override
    public ItemStack locale(final AbstractItemStack<? extends ItemStack> original, final int amount) {

      return ((PaperItemSnapshot)original).copy(amount);
    }
  }
}
