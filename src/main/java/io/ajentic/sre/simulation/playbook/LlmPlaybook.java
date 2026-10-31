package io.ajentic.sre.simulation.playbook;

import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.Observation;
import io.ajentic.sre.simulation.action.AgentAction;
import io.ajentic.sre.simulation.action.Idle;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Bridges the {@link Playbook} contract to the ReAct loop. One {@link AgentSession} per episode drives the shared
 * environment through tool calls; each {@link #next} runs one agent turn and the policy consumes one environment
 * step per turn, so {@code maxSteps}, stagnation and the token budget all apply to the episode as a whole.
 */
@Dependent
@PlaybookModel(PlaybookKind.LLM)
public final class LlmPlaybook implements Playbook {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final ToolAuthorization authorization;
    private final SensitiveDataMasker masker;
    private final MeterRegistry meters;
    private final AgentSessionConfig config;

    private AgentSession session;

    @Inject
    public LlmPlaybook(
            LlmClient llm,
            ToolRegistry tools,
            ToolAuthorization authorization,
            SensitiveDataMasker masker,
            MeterRegistry meters,
            @ConfigProperty(name = "ajentic.llm.model", defaultValue = "") String model,
            @ConfigProperty(name = "ajentic.llm.scenario", defaultValue = "checkout-latency-db-pool")
            String scenarioId) {
        this(llm, tools, authorization, masker, meters,
                AgentSessionConfig.forScenario(scenarioId).withModel(model.isBlank() ? null : model));
    }

    public LlmPlaybook(
            LlmClient llm,
            ToolRegistry tools,
            ToolAuthorization authorization,
            SensitiveDataMasker masker,
            MeterRegistry meters,
            AgentSessionConfig config) {
        this.llm = llm;
        this.tools = tools;
        this.authorization = authorization;
        this.masker = masker;
        this.meters = meters;
        this.config = config;
    }

    @Override
    public AgentAction next(Environment environment, Observation observation) {
        if (environment.isTerminal()) {
            return new Idle();
        }
        if (session == null) {
            session = new AgentSession(
                    "llm-policy-" + config.scenarioId(),
                    config,
                    llm,
                    tools,
                    environment,
                    systemPrompt(environment),
                    authorization,
                    masker.newSession(),
                    meters);
        }
        ChatTurnResult turn = session.chat(promptFor(observation));
        if (meters != null) {
            meters.counter("ajentic.llm.policy.tokens", "model", config.model() == null ? "default" : config.model())
                    .increment(turn.usage().totalTokens());
        }
        return new Idle();
    }

    private static String systemPrompt(Environment environment) {
        return """
                You are Ajentic SRE, an autonomous site reliability engineer.
                Investigate the incident with the available tools, identify the root cause, apply the correct
                remediation, and record your diagnosis. Ground every conclusion in the evidence returned by tools.
                Report: %s
                """.formatted(environment.observe().incidentSummary());
    }

    private static String promptFor(Observation observation) {
        return "Investigate the incident until it is resolved.\n\n"
                + "Latest feedback: " + observation.feedback() + "\n"
                + "Service health: " + observation.serviceHealth();
    }
}
