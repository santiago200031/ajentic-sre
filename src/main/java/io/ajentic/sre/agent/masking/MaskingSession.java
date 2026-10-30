package io.ajentic.sre.agent.masking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;

public final class MaskingSession {

    private final List<SensitiveDataCategory> categories;
    private final Map<String, String> originalsByPlaceholder = new LinkedHashMap<>();
    private final Map<String, String> placeholdersByOriginal = new LinkedHashMap<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    MaskingSession(List<SensitiveDataCategory> categories) {
        this.categories = categories;
    }

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (SensitiveDataCategory category : categories) {
            masked = apply(masked, category);
        }
        return masked;
    }

    public String unmask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String unmasked = text;
        for (Map.Entry<String, String> entry : originalsByPlaceholder.entrySet()) {
            unmasked = unmasked.replace(entry.getKey(), entry.getValue());
        }
        return unmasked;
    }

    public Map<String, String> mappings() {
        return Map.copyOf(originalsByPlaceholder);
    }

    /**
     * Masks every string value reachable from the arguments map, preserving the map/list shape and leaving
     * non-string scalars untouched so tool schemas keep their expected types.
     */
    public Map<String, Object> maskArguments(Map<String, Object> arguments) {
        return transformArguments(arguments, this::mask);
    }

    public Map<String, Object> unmaskArguments(Map<String, Object> arguments) {
        return transformArguments(arguments, this::unmask);
    }

    private static Map<String, Object> transformArguments(Map<String, Object> arguments,
            UnaryOperator<String> transform) {
        Map<String, Object> transformed = new LinkedHashMap<>();
        arguments.forEach((key, value) -> transformed.put(key, transformValue(value, transform, 0)));
        return Map.copyOf(transformed);
    }

    private static Object transformValue(Object value, UnaryOperator<String> transform, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("Tool-call arguments are nested too deeply to mask");
        }
        if (value instanceof String text) {
            return transform.apply(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) ->
                    nested.put(String.valueOf(key), transformValue(nestedValue, transform, depth + 1)));
            return Map.copyOf(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            list.forEach(element -> nested.add(transformValue(element, transform, depth + 1)));
            return List.copyOf(nested);
        }
        return value;
    }

    private String apply(String text, SensitiveDataCategory category) {
        Matcher matcher = category.pattern().matcher(text);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String placeholder = placeholderFor(category.placeholderPrefix(), matcher.group());
            matcher.appendReplacement(builder, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String placeholderFor(String type, String original) {
        return placeholdersByOriginal.computeIfAbsent(original, value -> {
            int index = counters.merge(type, 1, Integer::sum);
            String placeholder = "[[%s_%d]]".formatted(type.toUpperCase(Locale.ROOT), index);
            originalsByPlaceholder.put(placeholder, value);
            return placeholder;
        });
    }
}
