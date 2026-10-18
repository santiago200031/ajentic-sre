package io.ajentic.sre.rl.policy;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects the {@link Policy} implementation for a {@link PolicyKind}. Policies are stateful, so they are
 * {@code @Dependent} and a fresh instance is resolved for every episode.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface PolicyModel {

    PolicyKind value();

    final class Literal extends AnnotationLiteral<PolicyModel> implements PolicyModel {

        private final PolicyKind value;

        private Literal(PolicyKind value) {
            this.value = value;
        }

        public static Literal of(PolicyKind value) {
            return new Literal(value);
        }

        @Override
        public PolicyKind value() {
            return value;
        }
    }
}
