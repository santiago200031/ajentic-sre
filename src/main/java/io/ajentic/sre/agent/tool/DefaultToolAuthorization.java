package io.ajentic.sre.agent.tool;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@DefaultBean
@ApplicationScoped
public class DefaultToolAuthorization implements ToolAuthorization {

    private final boolean allowMutations;

    @Inject
    public DefaultToolAuthorization(
            @ConfigProperty(name = "ajentic.tools.allow-mutations", defaultValue = "true") boolean allowMutations) {
        this.allowMutations = allowMutations;
    }

    @Override
    public boolean isAuthorized(ToolDescriptor tool) {
        return tool.capability() == Capability.READ || allowMutations;
    }
}
