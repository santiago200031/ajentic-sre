package io.ajentic.sre.simulation.playbook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Resolves the {@link Playbook} for a requested kind. Policies are stateful, so each lookup returns a fresh
 * {@code @Dependent} instance selected by its {@link PlaybookModel} qualifier.
 */
@ApplicationScoped
public class PlaybookFactory {

    private final Instance<Playbook> policies;

    @Inject
    public PlaybookFactory(@Any Instance<Playbook> policies) {
        this.policies = policies;
    }

    public Playbook byName(String name) {
        return policies.select(PlaybookModel.Literal.of(PlaybookKind.fromName(name))).get();
    }
}
