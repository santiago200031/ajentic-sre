package io.ajentic.sre.rl.action;

public record ListRemediations() implements AgentAction {

    @Override
    public String describe() {
        return "list remediations";
    }
}
