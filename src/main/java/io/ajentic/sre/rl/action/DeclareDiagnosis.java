package io.ajentic.sre.rl.action;

import java.util.Objects;

public record DeclareDiagnosis(String service, String rootCause) implements AgentAction {

    public DeclareDiagnosis {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(rootCause, "rootCause");
    }

    @Override
    public String describe() {
        return "declare '" + rootCause + "' on " + service;
    }
}
