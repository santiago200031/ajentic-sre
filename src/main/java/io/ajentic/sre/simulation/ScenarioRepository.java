package io.ajentic.sre.simulation;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository {

    List<Scenario> all();

    Optional<Scenario> byId(String id);
}
