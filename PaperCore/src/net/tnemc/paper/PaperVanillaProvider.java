package net.tnemc.paper;

import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.paper.VanillaProvider;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import org.bukkit.inventory.ItemStack;

final class PaperVanillaProvider extends VanillaProvider {

  @Override
  public boolean similar(final AbstractItemStack<? extends ItemStack> original, final ItemStack compare) {

    if(compare == null || compare.getType().isAir()) {
      return false;
    }
    final String material = original.material().contains(":") ? original.material() : "minecraft:" + original.material();
    return material.equalsIgnoreCase(compare.getType().getKey().toString()) && super.similar(original, compare);
  }

  @Override
  public boolean similar(final AbstractItemStack<? extends ItemStack> original,
                         final AbstractItemStack<? extends ItemStack> compare) {

    return original instanceof PaperItemStack originalStack && compare instanceof PaperItemStack compareStack
           && PaperItemPlatform.instance().check(originalStack, compareStack);
  }

  @Override
  public ItemStack locale(final AbstractItemStack<? extends ItemStack> original, final int amount) {

    final ItemStack item = super.locale(original, amount);
    if(item == null) {
      throw new IllegalArgumentException("Unknown item material: " + original.material());
    }
    final ItemStack result = item.clone();
    result.setAmount(amount);
    return result;
  }
}
