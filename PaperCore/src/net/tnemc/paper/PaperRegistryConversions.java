package net.tnemc.paper;

import net.kyori.adventure.key.Key;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import net.tnemc.item.platform.conversion.PlatformConverter;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

final class PaperRegistryConversions {

  private PaperRegistryConversions() {
  }

  static void register() {

    final PlatformConverter converter = PaperItemPlatform.instance().converter();
    converter.registerConversion(Keyed.class, String.class, value -> value.getKey().toString());
    converter.registerConversion(Key.class, String.class, Key::asString);
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
    converter.registerConversion(registryType, String.class, value -> value.getKey().toString());
    converter.registerConversion(String.class, registryType, value -> resolve(registryType, value));
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
