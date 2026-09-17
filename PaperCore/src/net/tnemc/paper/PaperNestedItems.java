package net.tnemc.paper;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.UseRemainder;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.component.SerialComponent;
import net.tnemc.item.component.impl.ContainerComponent;
import net.tnemc.item.component.impl.UseRemainderComponent;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import net.tnemc.item.paper.platform.impl.modern.PaperBundleComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperChargedProjectilesComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperContainerComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperUseRemainderComponent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class PaperNestedItems {

  private PaperNestedItems() {
  }

  static void register() {

    final PaperItemPlatform platform = PaperItemPlatform.instance();
    platform.addMulti(new PaperContainerComponent() {
      @Override
      public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

        serialized.component(identifier()).ifPresent(component -> item.setData(DataComponentTypes.CONTAINER,
                ItemContainerContents.containerContents(contents((ContainerComponent<?, ItemStack>)component, true))));
        return item;
      }

      @Override
      public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

        final ItemContainerContents contents = item.getData(DataComponentTypes.CONTAINER);
        if(contents != null) {
          serialized.applyComponent(new PaperContainerComponent(children(contents.contents())));
        }
        return serialized;
      }
    });
    platform.addMulti(new PaperBundleComponent() {
      @Override
      public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

        serialized.component(identifier()).ifPresent(component -> item.setData(DataComponentTypes.BUNDLE_CONTENTS,
                BundleContents.bundleContents(contents((ContainerComponent<?, ItemStack>)component, false))));
        return item;
      }

      @Override
      public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

        final BundleContents contents = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if(contents != null) {
          serialized.applyComponent(new PaperBundleComponent(children(contents.contents())));
        }
        return serialized;
      }
    });
    platform.addMulti(new PaperChargedProjectilesComponent() {
      @Override
      public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

        serialized.component(identifier()).ifPresent(component -> item.setData(DataComponentTypes.CHARGED_PROJECTILES,
                ChargedProjectiles.chargedProjectiles(contents((ContainerComponent<?, ItemStack>)component, false))));
        return item;
      }

      @Override
      public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

        final ChargedProjectiles contents = item.getData(DataComponentTypes.CHARGED_PROJECTILES);
        if(contents != null) {
          serialized.applyComponent(new PaperChargedProjectilesComponent(children(contents.projectiles())));
        }
        return serialized;
      }
    });
    platform.addMulti(new RemainderComponent());
  }

  private static Map<Integer, AbstractItemStack<ItemStack>> children(final List<ItemStack> contents) {

    final Map<Integer, AbstractItemStack<ItemStack>> children = new TreeMap<>();
    for(int slot = 0; slot < contents.size(); slot++) {
      final ItemStack child = contents.get(slot);
      if(child != null && !child.getType().isAir()) {
        final PaperItemStack serialized = new PaperItemStack().of(child);
        PaperItemPlatform.instance().providerApplies(serialized, child);
        children.put(slot, serialized);
      }
    }
    return children;
  }

  private static List<ItemStack> contents(final ContainerComponent<?, ItemStack> component, final boolean keepSlots) {

    final List<ItemStack> result = new ArrayList<>();
    for(final Map.Entry<Integer, AbstractItemStack<ItemStack>> entry : new TreeMap<>(component.items()).entrySet()) {
      final int slot = entry.getKey();
      if(slot < 0 || slot >= 256) {
        throw new IllegalArgumentException("Invalid container slot: " + slot);
      }
      if(keepSlots) {
        result.addAll(Collections.nCopies(slot - result.size(), new ItemStack(Material.AIR)));
      }
      final AbstractItemStack<ItemStack> child = entry.getValue();
      result.add(child.provider().locale(child, child.amount()));
    }
    return result;
  }

  private static final class RemainderComponent extends PaperUseRemainderComponent {

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      serialized.component(identifier()).ifPresent(component -> {
        final PaperItemStack child = ((PaperUseRemainderComponent)component).item();
        if(child != null) {
          item.setData(DataComponentTypes.USE_REMAINDER, UseRemainder.useRemainder(child.provider().locale(child)));
        }
      });
      return item;
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      final UseRemainder remainder = item.getData(DataComponentTypes.USE_REMAINDER);
      if(remainder != null) {
        final RemainderComponent component = new RemainderComponent();
        component.item(new PaperItemStack().of(remainder.transformInto()));
        serialized.applyComponent(component);
      }
      return serialized;
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      if(!(component instanceof UseRemainderComponent<?, ?> other)) {
        return false;
      }
      if(item() == null || other.item() == null) {
        return item() == other.item();
      }
      return other.item() instanceof PaperItemStack child && item().amount() == child.amount()
             && item().provider().similar(item(), child);
    }
  }
}
