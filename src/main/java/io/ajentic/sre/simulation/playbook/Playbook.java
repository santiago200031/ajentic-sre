package io.ajentic.sre.simulation.playbook;

import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.Observation;
import io.ajentic.sre.simulation.action.AgentAction;

@FunctionalInterface
public interface Playbook {

    AgentAction next(Environment environment, Observation observation);
}
