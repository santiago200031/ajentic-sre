package io.ajentic.sre.simulation.playbook;

import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.action.AgentAction;
import io.ajentic.sre.simulation.action.Idle;
import io.ajentic.sre.simulation.Observation;
import jakarta.enterprise.context.Dependent;

@Dependent
@PlaybookModel(PlaybookKind.IDLE)
public final class IdlePlaybook implements Playbook {

    @Override
    public AgentAction next(Environment environment, Observation observation) {
        return new Idle();
    }
}
