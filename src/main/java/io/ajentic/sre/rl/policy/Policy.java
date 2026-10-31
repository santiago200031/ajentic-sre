package io.ajentic.sre.rl.policy;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.action.AgentAction;

@FunctionalInterface
public interface Policy {

    AgentAction next(Environment environment, Observation observation);
}
