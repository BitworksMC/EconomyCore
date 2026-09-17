package net.tnemc.paper;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.Repairable;
import io.papermc.paper.datacomponent.item.Tool;
import io.papermc.paper.datacomponent.item.JukeboxPlayable;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.util.TriState;
import net.tnemc.item.component.helper.EquipSlot;
import net.tnemc.item.component.helper.ToolRule;
import net.tnemc.item.component.SerialComponent;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.item.paper.platform.PaperItemPlatform;
import net.tnemc.item.paper.platform.impl.modern.PaperAttributeModifiersComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperEquipComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperRepairableComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperToolComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperJukeBoxComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperSwingAnimationComponent;
import net.tnemc.item.paper.platform.impl.modern.PaperInstrumentComponent;
import org.bukkit.MusicInstrument;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.json.simple.JSONObject;

import java.util.Objects;
import java.util.function.BiFunction;

final class PaperPreservedComponents {

  private PaperPreservedComponents() {
  }

  static void register() {

    final PaperItemPlatform platform = PaperItemPlatform.instance();
    platform.addMulti(new Attributes());
    platform.addMulti(new Equipment());
    platform.addMulti(new RepairItems());
    platform.addMulti(new ToolRules());
    platform.addMulti(new Jukebox());
    platform.addMulti(new Swing());
    platform.addMulti(new Instrument());
  }

  @SuppressWarnings("unchecked")
  private static DataComponentType.Valued<Object> optionalType(final String field) {

    try {
      return (DataComponentType.Valued<Object>)DataComponentTypes.class.getField(field).get(null);
    } catch(final NoSuchFieldException ignored) {
      return null;
    } catch(final IllegalAccessException exception) {
      throw new IllegalStateException("Cannot access Paper component " + field, exception);
    }
  }

  private interface Preserved extends SerialComponent<PaperItemStack, ItemStack> {

    Saved<?> saved();

    @Override
    default boolean check(final AbstractItemStack<ItemStack> original, final AbstractItemStack<ItemStack> compare) {

      final SerialComponent<?, ?> first = original.components().get(identifier());
      final SerialComponent<?, ?> second = compare.components().get(identifier());
      return first != null && second != null ? same(first, second) : SerialComponent.super.check(original, compare);
    }
  }

  private static Object optionalValue(final Class<?> type, final Object target, final String method, final Object fallback) {

    try {
      return type.getMethod(method).invoke(target);
    } catch(final NoSuchMethodException ignored) {
      return fallback;
    } catch(final ReflectiveOperationException exception) {
      throw new IllegalStateException("Cannot read Paper component property " + method, exception);
    }
  }

  private static void optionalBoolean(final Class<?> type, final Object target, final String method,
                                       final boolean value, final boolean fallback) {

    try {
      type.getMethod(method, boolean.class).invoke(target, value);
    } catch(final NoSuchMethodException exception) {
      if(value != fallback) {
        throw new IllegalArgumentException("This Paper version does not support " + method, exception);
      }
    } catch(final ReflectiveOperationException exception) {
      throw new IllegalStateException("Cannot write Paper component property " + method, exception);
    }
  }

  private static String keyString(final Key key) {

    return key == null ? null : key.asString();
  }

  private static <Component extends SerialComponent<PaperItemStack, ItemStack> & Preserved>
  PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized, final Component component,
                           final BiFunction<ItemStack, PaperItemStack, PaperItemStack> serializer) {

    if(component.saved().type == null || item.getData(component.saved().type) == null) {
      return serialized;
    }
    serialized.applyComponent(component);
    final PaperItemStack result = serializer.apply(item, serialized);
    component.saved().capture(item, component);
    return result;
  }

  private static boolean restore(final PaperItemStack serialized, final String identifier, final ItemStack item) {

    final SerialComponent<?, ?> component = serialized.paperComponent(identifier);
    return component instanceof Preserved preserved && preserved.saved().restore(item, component);
  }

  private static boolean same(final SerialComponent<?, ?> original, final SerialComponent<?, ?> compare) {

    if(!original.identifier().equals(compare.identifier())) {
      return false;
    }
    if(original instanceof Preserved first && compare instanceof Preserved second
       && first.saved().unchanged(original) && second.saved().unchanged(compare)) {
      return Objects.equals(first.saved().value, second.saved().value);
    }
    return Objects.equals(signature(original), signature(compare));
  }

  private static JSONObject signature(final SerialComponent<?, ?> component) {

    final JSONObject json = component.toJSON();
    if(component instanceof PaperToolComponent tool) {
      json.put("canDestroyBlocksCreative", tool.canDestroyBlocksCreative());
    }
    if(component instanceof PaperJukeBoxComponent jukebox) {
      json.put("song", jukebox.song());
    }
    return json;
  }

  private static final class Saved<Value> {

    private final DataComponentType.Valued<Value> type;
    private Value value;
    private JSONObject state;
    private Material material;
    private boolean overridden;

    private Saved(final DataComponentType.Valued<Value> type) {

      this.type = type;
    }

    private void capture(final ItemStack item, final SerialComponent<?, ?> component) {

      value = item.getData(type);
      state = signature(component);
      material = item.getType();
      overridden = item.isDataOverridden(type);
    }

    private boolean unchanged(final SerialComponent<?, ?> component) {

      return value != null && Objects.equals(state, signature(component));
    }

    private boolean restore(final ItemStack item, final SerialComponent<?, ?> component) {

      if(!unchanged(component)) {
        return false;
      }
      if(!overridden && item.getType() == material) {
        item.resetData(type);
      } else {
        item.setData(type, value);
      }
      return true;
    }
  }

  private static final class Attributes extends PaperAttributeModifiersComponent implements Preserved {

    private final Saved<ItemAttributeModifiers> saved = new Saved<>(DataComponentTypes.ATTRIBUTE_MODIFIERS);

    @Override
    public Saved<?> saved() {

      return saved;
    }

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      if(restore(serialized, identifier(), item)) {
        return item;
      }
      serialized.<PaperAttributeModifiersComponent>component(identifier()).ifPresent(component -> {
        final ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        final var converter = PaperItemPlatform.instance().converter();
        for(final var modifier : component.modifiers()) {
          final AttributeModifier attribute = new AttributeModifier(Objects.requireNonNull(NamespacedKey.fromString(modifier.getId())),
                  modifier.getAmount(), converter.convert(modifier.getOperation(), AttributeModifier.Operation.class),
                  converter.convert(modifier.getSlot(), EquipmentSlotGroup.class));
          builder.addModifier(converter.convert(modifier.getType(), Attribute.class), attribute);
        }
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder);
      });
      return item;
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      return PaperPreservedComponents.serialize(item, serialized, new Attributes(), super::serialize);
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      return same(this, component);
    }
  }

  private static final class Equipment extends PaperEquipComponent implements Preserved {

    private final Saved<Equippable> saved = new Saved<>(DataComponentTypes.EQUIPPABLE);

    @Override
    public Saved<?> saved() {

      return saved;
    }

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      if(restore(serialized, identifier(), item)) {
        return item;
      }
      serialized.<PaperEquipComponent>component(identifier()).ifPresent(component -> item.setData(DataComponentTypes.EQUIPPABLE, build(component)));
      return item;
    }

    private Equippable.Builder build(final PaperEquipComponent component) {

      final Equippable.Builder builder = Equippable.equippable(PaperItemPlatform.instance().converter().convert(component.slot(), EquipmentSlot.class))
              .damageOnHurt(component.damageOnHurt()).dispensable(component.dispensable()).swappable(component.swappable());
      optionalBoolean(Equippable.Builder.class, builder, "equipOnInteract", component.equipOnInteract(), false);
      optionalBoolean(Equippable.Builder.class, builder, "canBeSheared", component.canBeSheared(), false);
      if(component.equipSound() != null && !component.equipSound().isEmpty()) {
        builder.equipSound(Key.key(component.equipSound()));
      }
      if(component.modelKey() != null && !component.modelKey().isEmpty()) {
        builder.assetId(Key.key(component.modelKey()));
      }
      if(component.cameraKey() != null && !component.cameraKey().isEmpty()) {
        builder.cameraOverlay(Key.key(component.cameraKey()));
      }
      if(!component.entities().isEmpty()) {
        builder.allowedEntities(RegistrySet.keySet(RegistryKey.ENTITY_TYPE,
                component.entities().stream().map(entity -> TypedKey.create(RegistryKey.ENTITY_TYPE, Key.key(entity))).toList()));
      }
      shearSound(builder, component.shearSound());
      return builder;
    }

    private void shearSound(final Equippable.Builder builder, final String sound) {

      if(sound != null && !sound.isEmpty()) {
        try {
          Equippable.Builder.class.getMethod("shearSound", Key.class).invoke(builder, Key.key(sound));
        } catch(final ReflectiveOperationException exception) {
          throw new IllegalArgumentException("This Paper version cannot apply the requested shear sound", exception);
        }
      }
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      final Equippable value = item.getData(DataComponentTypes.EQUIPPABLE);
      if(value == null) {
        return serialized;
      }
      final Equipment component = new Equipment();
      component.slot(PaperItemPlatform.instance().converter().convert(value.slot(), EquipSlot.class));
      component.damageOnHurt(value.damageOnHurt());
      component.dispensable(value.dispensable());
      component.swappable(value.swappable());
      component.equipOnInteract((boolean)optionalValue(Equippable.class, value, "equipOnInteract", false));
      component.canBeSheared((boolean)optionalValue(Equippable.class, value, "canBeSheared", false));
      component.equipSound(keyString(value.equipSound()));
      component.modelKey(keyString(value.assetId()));
      component.cameraKey(keyString(value.cameraOverlay()));
      component.shearSound(keyString((Key)optionalValue(Equippable.class, value, "shearSound", null)));
      if(value.allowedEntities() != null) {
        value.allowedEntities().values().forEach(entity -> component.entities().add(entity.key().asString()));
      }
      component.saved.capture(item, component);
      serialized.applyComponent(component);
      return serialized;
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      return same(this, component);
    }
  }

  private static final class RepairItems extends PaperRepairableComponent implements Preserved {

    private final Saved<Repairable> saved = new Saved<>(DataComponentTypes.REPAIRABLE);

    @Override
    public Saved<?> saved() {

      return saved;
    }

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      return restore(serialized, identifier(), item) ? item : super.apply(serialized, item);
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      return PaperPreservedComponents.serialize(item, serialized, new RepairItems(), super::serialize);
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      return same(this, component);
    }
  }

  private static final class ToolRules extends PaperToolComponent implements Preserved {

    private final Saved<Tool> saved = new Saved<>(DataComponentTypes.TOOL);

    @Override
    public Saved<?> saved() {

      return saved;
    }

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      if(restore(serialized, identifier(), item)) {
        return item;
      }
      serialized.<PaperToolComponent>component(identifier()).ifPresent(component -> {
        final Tool.Builder builder = Tool.tool().defaultMiningSpeed(component.defaultSpeed()).damagePerBlock(component.blockDamage());
        optionalBoolean(Tool.Builder.class, builder, "canDestroyBlocksInCreative", component.canDestroyBlocksCreative(), true);
        for(final ToolRule rule : component.rules()) {
          builder.addRule(Tool.rule(RegistrySet.keySet(RegistryKey.BLOCK,
                  rule.getMaterials().stream().map(material -> TypedKey.create(RegistryKey.BLOCK, Key.key(material))).toList()),
                  rule.getSpeed(), rule.isDrops() ? TriState.TRUE : TriState.FALSE));
        }
        item.setData(DataComponentTypes.TOOL, builder);
      });
      return item;
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      final Tool value = item.getData(DataComponentTypes.TOOL);
      if(value == null) {
        return serialized;
      }
      final ToolRules component = new ToolRules();
      component.defaultSpeed(value.defaultMiningSpeed());
      component.blockDamage(value.damagePerBlock());
      component.canDestroyBlocksCreative((boolean)optionalValue(Tool.class, value, "canDestroyBlocksInCreative", true));
      for(final Tool.Rule rule : value.rules()) {
        final ToolRule converted = new ToolRule(rule.speed() == null ? component.defaultSpeed() : rule.speed(),
                                                rule.correctForDrops() == TriState.TRUE);
        rule.blocks().values().forEach(block -> converted.getMaterials().add(block.key().asString()));
        component.rules().add(converted);
      }
      component.saved.capture(item, component);
      serialized.applyComponent(component);
      return serialized;
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      return same(this, component);
    }

    @Override
    public JSONObject toJSON() {

      final JSONObject json = super.toJSON();
      json.put("canDestroyBlocksCreative", canDestroyBlocksCreative());
      return json;
    }
  }

  private static final class Jukebox extends PaperJukeBoxComponent implements Preserved {

    private final Saved<JukeboxPlayable> saved = new Saved<>(DataComponentTypes.JUKEBOX_PLAYABLE);

    @Override
    public Saved<?> saved() {

      return saved;
    }

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      return restore(serialized, identifier(), item) ? item : super.apply(serialized, item);
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      return PaperPreservedComponents.serialize(item, serialized, new Jukebox(), super::serialize);
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      return same(this, component);
    }

    @Override
    public JSONObject toJSON() {

      final JSONObject json = super.toJSON();
      json.put("song", song());
      return json;
    }
  }

  private static final class Swing extends PaperSwingAnimationComponent implements Preserved {

    private final Saved<Object> saved = new Saved<>(optionalType("SWING_ANIMATION"));

    @Override
    public Saved<?> saved() {

      return saved;
    }

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      return restore(serialized, identifier(), item) ? item : super.apply(serialized, item);
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      return PaperPreservedComponents.serialize(item, serialized, new Swing(), super::serialize);
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      return same(this, component);
    }
  }

  private static final class Instrument extends PaperInstrumentComponent implements Preserved {

    private final Saved<MusicInstrument> saved = new Saved<>(DataComponentTypes.INSTRUMENT);

    @Override
    public Saved<?> saved() {

      return saved;
    }

    @Override
    public ItemStack apply(final PaperItemStack serialized, final ItemStack item) {

      return restore(serialized, identifier(), item) ? item : super.apply(serialized, item);
    }

    @Override
    public PaperItemStack serialize(final ItemStack item, final PaperItemStack serialized) {

      final MusicInstrument value = item.getData(DataComponentTypes.INSTRUMENT);
      if(value == null) {
        return serialized;
      }
      final Instrument component = new Instrument();
      component.soundEvent(PaperItemPlatform.instance().converter().convert(value, String.class));
      component.useDuration(((Number)optionalValue(MusicInstrument.class, value, "getDuration", 0)).intValue());
      component.range(((Number)optionalValue(MusicInstrument.class, value, "getRange", 0)).intValue());
      component.saved.capture(item, component);
      serialized.applyComponent(component);
      return serialized;
    }

    @Override
    public boolean similar(final SerialComponent<?, ?> component) {

      return same(this, component);
    }
  }
}
