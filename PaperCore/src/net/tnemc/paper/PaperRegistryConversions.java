package net.tnemc.paper;

import net.kyori.adventure.key.Key;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import net.tnemc.item.platform.conversion.PlatformConverter;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

final class PaperRegistryConversions {

  private static final String UNREGISTERED = "tne:unregistered";

  private PaperRegistryConversions() {
  }

  static void register() {

    final PlatformConverter converter = PaperItemPlatform.instance().converter();
    converter.registerConversion(Keyed.class, String.class, PaperRegistryConversions::key);
    converter.registerConversion(Key.class, String.class, Key::asString);
    // TNIL decodes pot decoration sherds through this, but never registers it.
    converter.registerConversion(String.class, Material.class, PaperRegistryConversions::material);
    register("org.bukkit.damage.DamageType");
    register("org.bukkit.JukeboxSong");
    register("org.bukkit.MusicInstrument");
  }

  private static void register(final String className) {

    try {
      register(Class.forName(className).asSubclass(Keyed.class));
    } catch(final ClassNotFoundException ignored) {
      return;
    }
  }

  private static <T extends Keyed> void register(final Class<T> registryType) {

    final PlatformConverter converter = PaperItemPlatform.instance().converter();
    converter.registerConversion(registryType, String.class, PaperRegistryConversions::key);
    converter.registerConversion(String.class, registryType, value -> resolve(registryType, value));
  }

  /**
   * Item components can define values such as goat horn instruments inline on the item. Those are
   * not in a registry and have no key; the component keeps the native value for restoring them.
   */
  private static String key(final Keyed value) {

    try {
      return value.getKey().toString();
    } catch(final IllegalStateException unregistered) {
      return UNREGISTERED;
    }
  }

  private static Material material(final String value) {

    final Material result = Material.matchMaterial(value);
    if(result == null) {
      throw new IllegalArgumentException("Unknown Material: " + value);
    }
    return result;
  }

  private static <T extends Keyed> T resolve(final Class<T> registryType, final String value) {

    final NamespacedKey key = NamespacedKey.fromString(value);
    final Registry<T> registry = Bukkit.getRegistry(registryType);
    final T result = key == null || registry == null ? null : registry.get(key);
    if(result == null) {
      throw new IllegalArgumentException("Unknown " + registryType.getSimpleName() + ": " + value);
    }
    return result;
  }
}
