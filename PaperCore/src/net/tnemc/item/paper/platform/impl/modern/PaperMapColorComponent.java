package net.tnemc.item.paper.platform.impl.modern;

/*
 * The New Economy
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.MapItemColor;
import net.tnemc.item.component.SerialComponent;
import net.tnemc.item.component.impl.MapColorComponent;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.providers.VersionUtil;
import org.bukkit.Color;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Replaces TNIL's map color component, which references {@code DataComponentTypes.MAP_COLOR}.
 *
 * <p>Paper 26.3 removed that data component, so the component disables itself when the field is
 * missing instead of throwing {@link NoSuchFieldError} on every item serialization.</p>
 */
public class PaperMapColorComponent extends MapColorComponent<PaperItemStack, ItemStack> {

  private static final boolean AVAILABLE = hasMapColor();

  public PaperMapColorComponent() {

    super();
  }

  public PaperMapColorComponent(final int mapColor) {

    super(mapColor);
  }

  @Override
  public boolean enabled(final String version) {

    return AVAILABLE && VersionUtil.isOneTwentyOneFour(version);
  }

  @Override
  public boolean appliesTo(final ItemStack item) {

    return true;
  }

  @Override
  public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

    final Optional<PaperMapColorComponent> component = serialized.component(identifier());
    if(component.isEmpty()) {
      return item;
    }

    item.setData(DataComponentTypes.MAP_COLOR, MapItemColor.mapItemColor()
            .color(Color.fromARGB(component.get().mapColor())));
    return item;
  }

  @Override
  public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

    final MapItemColor color = item.getData(DataComponentTypes.MAP_COLOR);
    if(color == null) {
      return serialized;
    }

    final SerialComponent<?, ?> existing = serialized.paperComponent(identifier());
    final PaperMapColorComponent component = (existing instanceof final MapColorComponent<?, ?> mapColor)
                                             ? (PaperMapColorComponent)mapColor
                                             : new PaperMapColorComponent();
    component.mapColor(color.color().asARGB());
    serialized.applyComponent(component);
    return serialized;
  }

  private static boolean hasMapColor() {

    try {
      DataComponentTypes.class.getField("MAP_COLOR");
      return true;
    } catch(final NoSuchFieldException ignored) {
      return false;
    }
  }
}
