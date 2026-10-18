package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class AgentSessionFactory {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final EnvironmentFactory environments;
    private final ToolAuthorization authorization;

    @Inject
    public AgentSessionFactory(
            LlmClient llm,
            ToolRegistry tools,
            EnvironmentFactory environments,
            ToolAuthorization authorization) {
        this.llm = llm;
        this.tools = tools;
        this.environments = environments;
        this.authorization = authorization;
    }

    public AgentSession create(AgentSessionConfig config) {
        Environment environment = environments.create(config.scenarioId(), config.seed());
        return new AgentSession(
                UUID.randomUUID().toString(),
                config,
                llm,
                tools,
                environment,
                systemPrompt(environment),
                authorization);
    }

    private static String systemPrompt(Environment environment) {
        return """
                You are Ajentic SRE, an autonomous site reliability engineer.
                Investigate the incident with the available tools, identify the root cause, apply the correct
                remediation, and record your diagnosis. Ground every conclusion in the evidence returned by tools.
                Report: %s
                """.formatted(environment.observe().incidentSummary());
    }
}
