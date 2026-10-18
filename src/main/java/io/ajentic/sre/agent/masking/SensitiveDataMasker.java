package io.ajentic.sre.agent.masking;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class SensitiveDataMasker {

    private static final List<SensitiveDataCategory> DEFAULT_CATEGORIES = List.of(SensitiveDataCategory.values());

    private final List<SensitiveDataCategory> categories;

    public SensitiveDataMasker() {
        this(DEFAULT_CATEGORIES);
    }

    public SensitiveDataMasker(List<SensitiveDataCategory> categories) {
        this.categories = List.copyOf(categories);
    }

    public MaskingSession newSession() {
        return new MaskingSession(categories);
    }
}
