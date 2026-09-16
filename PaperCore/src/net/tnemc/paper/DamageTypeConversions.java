package net.tnemc.paper;

import net.tnemc.item.paper.platform.PaperItemPlatform;
import net.tnemc.item.platform.conversion.PlatformConverter;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

final class DamageTypeConversions {

  private DamageTypeConversions() {
  }

  static void register() {

    try {
      register(Class.forName("org.bukkit.damage.DamageType").asSubclass(Keyed.class));
    } catch(final ClassNotFoundException ignored) {
      return;
    }
  }

  private static <T extends Keyed> void register(final Class<T> damageType) {

    final PlatformConverter converter = PaperItemPlatform.instance().converter();
    converter.registerConversion(damageType, String.class, value -> value.getKey().toString());
    converter.registerConversion(String.class, damageType, value -> resolve(damageType, value));
  }

  private static <T extends Keyed> T resolve(final Class<T> damageType, final String value) {

    final NamespacedKey key = NamespacedKey.fromString(value);
    final Registry<T> registry = Bukkit.getRegistry(damageType);
    final T result = key == null || registry == null ? null : registry.get(key);
    if(result == null) {
      throw new IllegalArgumentException("Unknown damage type: " + value);
    }
    return result;
  }
}
