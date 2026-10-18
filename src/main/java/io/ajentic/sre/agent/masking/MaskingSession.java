package io.ajentic.sre.agent.masking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
