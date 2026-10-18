package io.ajentic.sre.cli;

import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.AgentSessionFactory;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Unremovable
@Command(name = "ask", mixinStandardHelpOptions = true,
        description = "Ask the SRE agent a single question against a scenario.")
public class AskCommand implements Runnable {

    @Inject
    AgentSessionFactory sessions;

    @Parameters(paramLabel = "<question>", description = "Incident question to investigate.")
    String question;

    @Option(names = "--scenario", defaultValue = "checkout-latency-db-pool",
            description = "Scenario to investigate.")
    String scenarioId;

    @Override
    public void run() {
        AgentSession session = sessions.create(AgentSessionConfig.forScenario(scenarioId));
        ChatTurnResult result = session.chat(question);
        System.out.println(result.answer());
    }
}
