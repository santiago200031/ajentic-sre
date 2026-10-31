package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.agent.memory.IncidentMemoryStore;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.UUID;

@ApplicationScoped
public class AgentSessionFactory {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final EnvironmentFactory environments;
    private final ToolAuthorization authorization;
    private final SensitiveDataMasker masker;
    private final MeterRegistry meters;
    private final IncidentMemoryStore memory;
    private final ScenarioRepository scenarios;
    private final int maxTokens;
    private final int stagnationThreshold;
    private final int stagnationEscalateAfter;
    private final Duration approvalTtl;

    @Inject
    public AgentSessionFactory(
            LlmClient llm,
            ToolRegistry tools,
            EnvironmentFactory environments,
            ToolAuthorization authorization,
            SensitiveDataMasker masker,
            MeterRegistry meters,
            IncidentMemoryStore memory,
            ScenarioRepository scenarios,
            @ConfigProperty(name = "ajentic.agent.max-tokens", defaultValue = "0") int maxTokens,
            @ConfigProperty(name = "ajentic.agent.stagnation.threshold", defaultValue = "2")
            int stagnationThreshold,
            @ConfigProperty(name = "ajentic.agent.stagnation.escalate-after", defaultValue = "3")
            int stagnationEscalateAfter,
            @ConfigProperty(name = "ajentic.approval.ttl", defaultValue = "10m") Duration approvalTtl) {
        this.llm = llm;
        this.tools = tools;
        this.environments = environments;
        this.authorization = authorization;
        this.masker = masker;
        this.meters = meters;
        this.memory = memory;
        this.scenarios = scenarios;
        this.maxTokens = maxTokens;
        this.stagnationThreshold = stagnationThreshold;
        this.stagnationEscalateAfter = stagnationEscalateAfter;
        this.approvalTtl = approvalTtl;
    }

    public AgentSession create(AgentSessionConfig config) {
        Environment environment = environments.create(config.scenarioId(), config.seed());
        return new AgentSession(
                UUID.randomUUID().toString(),
                config.withMaxTokens(maxTokens).withStagnation(stagnationThreshold, stagnationEscalateAfter),
                llm,
                tools,
                environment,
                systemPrompt(environment),
                authorization,
                masker.newSession(),
                meters,
                approvalTtl);
    }

    private String systemPrompt(Environment environment) {
        String memorySummary = scenarios.byId(environment.spec().scenarioId())
                .map(memory::summaryFor)
                .orElse("");
        return """
                You are Ajentic SRE, an autonomous site reliability engineer.
                Investigate the incident with the available tools, identify the root cause, apply the correct
                remediation, and record your diagnosis. Ground every conclusion in the evidence returned by tools.
                Report: %s
                %s
                """.formatted(environment.observe().incidentSummary(),
                memorySummary.isBlank() ? "" : memorySummary);
    }
}
