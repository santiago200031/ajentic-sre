package io.ajentic.sre.agent.mcp;

import io.ajentic.sre.simulation.Runbook;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ScenarioRepository;
import io.ajentic.sre.simulation.ServiceSpec;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers MCP resources dynamically for all scenarios, topologies, and runbooks via {@link ResourceManager}.
 */
@ApplicationScoped
public class SreMcpResources {

    private static final String SCENARIO_URI_PREFIX = "sre://scenarios/";
    private static final String RUNBOOK_URI_PREFIX = "sre://runbooks/";
    private static final String TOPOLOGY_URI_PREFIX = "sre://topology/";

    private final ScenarioRepository scenarios;
    private final ResourceManager resourceManager;
    private final ObjectMapper mapper;

    @Inject
    public SreMcpResources(ScenarioRepository scenarios, ResourceManager resourceManager, ObjectMapper mapper) {
        this.scenarios = scenarios;
        this.resourceManager = resourceManager;
        this.mapper = mapper;
    }

    void onStart(@Observes StartupEvent event) {
        for (Scenario scenario : scenarios.all()) {
            registerScenario(scenario);
            registerTopology(scenario);
            registerRunbooks(scenario);
        }
    }

    private void registerScenario(Scenario scenario) {
        String uri = SCENARIO_URI_PREFIX + scenario.id();
        String json;
        try {
            json = mapper.writeValueAsString(ScenarioPayload.from(scenario));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize scenario MCP resource", e);
        }
        resourceManager.newResource(uri)
                .setUri(uri)
                .setTitle(scenario.title())
                .setDescription(scenario.summary())
                .setMimeType("application/json")
                .setHandler(args -> new ResourceResponse(new TextResourceContents(uri, json, "application/json")))
                .register();
    }

    private void registerTopology(Scenario scenario) {
        String uri = TOPOLOGY_URI_PREFIX + scenario.id();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenarioId", scenario.id());
        payload.put("services", scenario.services().stream()
                .map(spec -> Map.of("name", spec.name(), "dependencies", spec.dependencies()))
                .toList());
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize topology MCP resource", e);
        }
        resourceManager.newResource(uri)
                .setUri(uri)
                .setTitle("Service dependency graph for " + scenario.id())
                .setDescription("Dependency graph and topology for scenario " + scenario.id())
                .setMimeType("application/json")
                .setHandler(args -> new ResourceResponse(new TextResourceContents(uri, json, "application/json")))
                .register();
    }

    private void registerRunbooks(Scenario scenario) {
        for (Runbook runbook : scenario.runbooks()) {
            String uri = RUNBOOK_URI_PREFIX + scenario.id() + "/" + runbook.id();
            String content = runbook.title() + "\n\n" + runbook.body();
            resourceManager.newResource(uri)
                .setUri(uri)
                .setTitle(runbook.title())
                .setDescription("Runbook " + runbook.id() + " for " + scenario.id())
                .setMimeType("text/plain")
                .setHandler(args -> new ResourceResponse(new TextResourceContents(uri, content, "text/plain")))
                .register();
        }
    }

    record ScenarioPayload(
            String id,
            String title,
            String summary,
            String incidentSummary,
            List<ServiceSpec> services,
            List<Runbook> runbooks,
            int maxSteps) {

        static ScenarioPayload from(Scenario scenario) {
            return new ScenarioPayload(
                    scenario.id(),
                    scenario.title(),
                    scenario.summary(),
                    scenario.incidentSummary(),
                    scenario.services(),
                    scenario.runbooks(),
                    scenario.maxSteps());
        }
    }
}
