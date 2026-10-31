package io.ajentic.sre.simulation.action;

public record Idle() implements AgentAction {

    @Override
    public String describe() {
        return "idle";
    }
}
