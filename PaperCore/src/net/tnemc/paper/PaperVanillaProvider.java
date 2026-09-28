package net.tnemc.paper;

import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.paper.VanillaProvider;
import org.bukkit.inventory.ItemStack;

final class PaperVanillaProvider extends VanillaProvider {

  @Override
  public boolean similar(final AbstractItemStack<? extends ItemStack> original, final ItemStack compare) {

    if(compare == null || compare.getType().isAir()) {
      return false;
    }
    final String material = original.material().contains(":") ? original.material() : "minecraft:" + original.material();
    // Compare the complete native item, including effective flags and data TNIL cannot represent.
    return material.equalsIgnoreCase(compare.getType().getKey().toString()) && locale(original, 1).isSimilar(compare);
  }

  @Override
  public boolean similar(final AbstractItemStack<? extends ItemStack> original,
                         final AbstractItemStack<? extends ItemStack> compare) {

    return original instanceof PaperItemStack && compare instanceof PaperItemStack
           && locale(original, 1).isSimilar(locale(compare, 1));
  }

  @Override
  public ItemStack locale(final AbstractItemStack<? extends ItemStack> original, final int amount) {

    if(original instanceof PaperItemSnapshot snapshot) {
      return snapshot.copy(amount);
    }
    final ItemStack item = super.locale(original, amount);
    if(item == null) {
      throw new IllegalArgumentException("Unknown item material: " + original.material());
    }
    final ItemStack result = item.clone();
    result.setAmount(amount);
    return result;
  }
}
