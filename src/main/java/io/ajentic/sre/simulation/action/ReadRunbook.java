package io.ajentic.sre.simulation.action;

import java.util.Objects;

public record ReadRunbook(String runbookId) implements AgentAction {

    public ReadRunbook {
        Objects.requireNonNull(runbookId, "runbookId");
    }

    @Override
    public String describe() {
        return "read runbook " + runbookId;
    }
}
