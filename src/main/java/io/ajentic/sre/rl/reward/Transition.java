package io.ajentic.sre.rl.reward;

import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.action.AgentAction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record Transition(
        AgentAction action,
        Observation before,
        Observation after,
        Set<TransitionEffect> effects) {

    public Transition {
        effects = effects.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(effects));
    }
}
