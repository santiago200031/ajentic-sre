package io.ajentic.sre.rl.policy;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.action.AgentAction;
import io.ajentic.sre.rl.action.Idle;
import io.ajentic.sre.rl.Observation;
import jakarta.enterprise.context.Dependent;

@Dependent
@PolicyModel(PolicyKind.IDLE)
public final class IdlePolicy implements Policy {

    @Override
    public AgentAction next(Environment environment, Observation observation) {
        return new Idle();
    }
}
