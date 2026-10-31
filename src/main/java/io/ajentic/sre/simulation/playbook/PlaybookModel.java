package io.ajentic.sre.simulation.playbook;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects the {@link Playbook} implementation for a {@link PlaybookKind}. Policies are stateful, so they are
 * {@code @Dependent} and a fresh instance is resolved for every episode.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface PlaybookModel {

    PlaybookKind value();

    final class Literal extends AnnotationLiteral<PlaybookModel> implements PlaybookModel {

        private final PlaybookKind value;

        private Literal(PlaybookKind value) {
            this.value = value;
        }

        public static Literal of(PlaybookKind value) {
            return new Literal(value);
        }

        @Override
        public PlaybookKind value() {
            return value;
        }
    }
}
