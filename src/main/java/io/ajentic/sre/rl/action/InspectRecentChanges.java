package io.ajentic.sre.rl.action;

import java.util.Objects;

public record InspectRecentChanges(String service, int windowMinutes) implements AgentAction {

    public InspectRecentChanges {
        Objects.requireNonNull(service, "service");
        if (windowMinutes < 1) {
            throw new IllegalArgumentException("windowMinutes must be at least 1");
        }
    }

    @Override
    public String describe() {
        return "inspect recent changes for " + service + " within " + windowMinutes + "m";
    }
}
