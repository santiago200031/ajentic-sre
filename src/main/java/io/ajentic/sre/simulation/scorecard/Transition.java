package io.ajentic.sre.simulation.scorecard;

import io.ajentic.sre.simulation.Observation;
import io.ajentic.sre.simulation.action.AgentAction;

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
