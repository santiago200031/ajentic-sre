package io.ajentic.sre.simulation.action;

public record ListRemediations() implements AgentAction {

    @Override
    public String describe() {
        return "list remediations";
    }
}
