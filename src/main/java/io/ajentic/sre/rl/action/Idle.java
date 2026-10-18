package io.ajentic.sre.rl.action;

public record Idle() implements AgentAction {

    @Override
    public String describe() {
        return "idle";
    }
}
