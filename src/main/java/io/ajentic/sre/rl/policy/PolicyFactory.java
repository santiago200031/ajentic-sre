package io.ajentic.sre.rl.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Resolves the {@link Policy} for a requested kind. Policies are stateful, so each lookup returns a fresh
 * {@code @Dependent} instance selected by its {@link PolicyModel} qualifier.
 */
@ApplicationScoped
public class PolicyFactory {

    private final Instance<Policy> policies;

    @Inject
    public PolicyFactory(@Any Instance<Policy> policies) {
        this.policies = policies;
    }

    public Policy byName(String name) {
        return policies.select(PolicyModel.Literal.of(PolicyKind.fromName(name))).get();
    }
}
