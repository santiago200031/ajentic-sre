package io.ajentic.sre.simulation.action;

import java.util.Objects;

public record ApplyRemediation(RemediationKind remediation, String service) implements AgentAction {

    public ApplyRemediation {
        Objects.requireNonNull(remediation, "remediation");
        Objects.requireNonNull(service, "service");
    }

    @Override
    public String describe() {
        return "apply " + remediation + " to " + service;
    }
}
