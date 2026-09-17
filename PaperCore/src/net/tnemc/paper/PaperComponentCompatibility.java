package net.tnemc.paper;

import net.tnemc.item.component.SerialComponent;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import net.tnemc.item.paper.platform.impl.modern.PaperAttackRangeComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperMinimumAttackChargeComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperBreakSoundComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperPotionDurationScaleComponent;
import net.tnemc.item.paper.platform.impl.old.PaperOldUnbreakableComponent;
import org.bukkit.inventory.ItemStack;

final class PaperComponentCompatibility {

  private PaperComponentCompatibility() {
  }

  static void register() {

    if(available("UNBREAKABLE")) {
      register(new PaperOldUnbreakableComponent());
    }
    final boolean attackRange = available("ATTACK_RANGE");
    register(new PaperAttackRangeComponent() {
      @Override
      public boolean enabled(final String version) {

        return attackRange && super.enabled(version);
      }
    });

    final boolean minimumAttackCharge = available("MINIMUM_ATTACK_CHARGE");
    register(new PaperMinimumAttackChargeComponent() {
      @Override
      public boolean enabled(final String version) {

        return minimumAttackCharge && super.enabled(version);
      }
    });
    final boolean breakSound = available("BREAK_SOUND");
    register(new PaperBreakSoundComponent() {
      @Override
      public boolean enabled(final String version) {

        return breakSound && super.enabled(version);
      }
    });
    final boolean potionDurationScale = available("POTION_DURATION_SCALE");
    register(new PaperPotionDurationScaleComponent() {
      @Override
      public boolean enabled(final String version) {

        return potionDurationScale && super.enabled(version);
      }
    });
  }

  private static void register(final SerialComponent<PaperItemStack, ItemStack> component) {

    final PaperItemPlatform platform = PaperItemPlatform.instance();
    platform.addCheck(component);
    platform.addApplicator(component);
    platform.addSerializer(component);
  }

  private static boolean available(final String field) {

    try {
      return Class.forName("io.papermc.paper.datacomponent.DataComponentTypes")
              .getField(field).getType().getName()
              .equals("io.papermc.paper.datacomponent.DataComponentType$Valued");
    } catch(final ClassNotFoundException | NoSuchFieldException ignored) {
      return false;
    }
  }
}
