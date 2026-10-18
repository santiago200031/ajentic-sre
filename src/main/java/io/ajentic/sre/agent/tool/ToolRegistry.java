package io.ajentic.sre.agent.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    List<Tool> all();

    Optional<Tool> find(String name);

    List<ToolDescriptor> descriptors();
}
