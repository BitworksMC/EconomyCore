package net.tnemc.item.platform.conversion;

import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class PlatformConverter {

  private final Map<Class<?>, Map<Class<?>, Function<Object, Object>>> registry = new HashMap<>();

  public <Input, Output> void registerConversion(final Class<Input> inputClass, final Class<Output> outputClass,
                                                final Function<Input, Output> converter) {

    Objects.requireNonNull(inputClass, "inputClass");
    Objects.requireNonNull(outputClass, "outputClass");
    Objects.requireNonNull(converter, "converter");
    registry.computeIfAbsent(inputClass, ignored -> new HashMap<>())
            .put(outputClass, input -> converter.apply(inputClass.cast(input)));
  }

  public <Input, Output> Output convert(final Input input, final Class<Output> outputClass) {

    if(input == null) {
      throw new IllegalArgumentException("Input cannot be null");
    }
    Objects.requireNonNull(outputClass, "outputClass");
    final Function<Object, Object> converter = conversion(input.getClass(), outputClass);
    if(converter != null) {
      return outputClass.cast(converter.apply(input));
    }
    if(outputClass.isInstance(input)) {
      return outputClass.cast(input);
    }
    throw new IllegalArgumentException("No conversion registered from " + input.getClass().getName()
                                       + " to " + outputClass.getName());
  }

  private Function<Object, Object> conversion(final Class<?> inputClass, final Class<?> outputClass) {

    final Function<Object, Object> exact = registry.getOrDefault(inputClass, Map.of()).get(outputClass);
    if(exact != null) {
      return exact;
    }
    final List<Class<?>> candidates = registry.keySet().stream()
            .filter(candidate -> candidate.isAssignableFrom(inputClass) && registry.get(candidate).containsKey(outputClass))
            .toList();
    return candidates.stream()
            .filter(candidate -> candidates.stream().noneMatch(other -> candidate != other && candidate.isAssignableFrom(other)))
            .min(Comparator.comparing(Class::getName))
            .map(candidate -> registry.get(candidate).get(outputClass))
            .orElse(null);
  }
}
