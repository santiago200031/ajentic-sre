package io.ajentic.sre.agent.tool;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Configurable mutation gate. {@code ajentic.tools.mutation-policy} (allow | deny | require-approval) wins;
 * otherwise the legacy {@code ajentic.tools.allow-mutations} boolean maps to allow/deny.
 */
@DefaultBean
@ApplicationScoped
public class DefaultToolAuthorization implements ToolAuthorization {

    private final AuthorizationDecision mutating;

    @Inject
    public DefaultToolAuthorization(
            @ConfigProperty(name = "ajentic.tools.mutation-policy") Optional<String> mutationPolicy,
            @ConfigProperty(name = "ajentic.tools.allow-mutations", defaultValue = "true") boolean allowMutations) {
        this.mutating = mutationPolicy
                .map(AuthorizationDecision::fromName)
                .orElseGet(() -> allowMutations ? AuthorizationDecision.ALLOW : AuthorizationDecision.DENY);
    }

    @Override
    public AuthorizationDecision decide(ToolDescriptor tool) {
        return tool.capability() == Capability.READ ? AuthorizationDecision.ALLOW : mutating;
    }
}
