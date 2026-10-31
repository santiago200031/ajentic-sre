package io.ajentic.sre.simulation;

import io.ajentic.sre.agent.cloud.CloudHttpClient;
import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.environment.LiveTelemetryConfig;
import io.ajentic.sre.simulation.environment.aws.AwsLiveEnvironment;
import io.ajentic.sre.simulation.environment.azure.AzureLiveEnvironment;
import io.ajentic.sre.simulation.environment.cloud.CloudLiveEnvironment;
import io.ajentic.sre.simulation.environment.gcp.GcpLiveEnvironment;
import io.ajentic.sre.simulation.environment.local.LocalContainerEnvironment;
import io.ajentic.sre.simulation.scorecard.Scorecard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Selects the environment backend from {@code ajentic.environment.backend}:
 * <ul>
 *   <li>{@code simulated} (default, deterministic, offline) - the default backend used by tests and CI;</li>
 *   <li>{@code local-container} / {@code cloud-live} - live adapters over Prometheus/Loki;</li>
 *   <li>{@code aws-live} / {@code azure-live} / {@code gcp-live} - cloud provider adapters.</li>
 * </ul>
 * Unknown backend values fail fast at startup.
 */
@ApplicationScoped
public class EnvironmentFactory {

    public static final String SIMULATED = "simulated";
    public static final String LOCAL_CONTAINER = "local-container";
    public static final String CLOUD_LIVE = "cloud-live";
    public static final String AWS_LIVE = "aws-live";
    public static final String AZURE_LIVE = "azure-live";
    public static final String GCP_LIVE = "gcp-live";
    public static final String SIDECAR = "sidecar";

    private static final Logger LOG = Logger.getLogger(EnvironmentFactory.class);

    private final ScenarioRepository scenarios;
    private final Scorecard rewardFunction;
    private final String backend;
    private final LiveTelemetryConfig liveConfig;
    private final io.ajentic.sre.agent.integration.SidecarClient sidecarClient;

    private final String awsRegion;
    private final String awsAccessKeyId;
    private final String awsSecretAccessKey;
    private final String awsSessionToken;
    private final String awsEndpointUrl;
    private final boolean awsAllowMutations;

    private final String azureTenantId;
    private final String azureClientId;
    private final String azureClientSecret;
    private final String azureSubscriptionId;
    private final String azureResourceGroup;
    private final String azureWorkspaceId;
    private final String azureEndpointUrl;
    private final String azureTokenUrl;
    private final boolean azureAllowMutations;

    private final String gcpProjectId;
    private final String gcpClientEmail;
    private final String gcpPrivateKey;
    private final String gcpEndpointUrl;
    private final String gcpTokenUrl;
    private final boolean gcpAllowMutations;

    private final boolean toolsAllowMutations;

    public EnvironmentFactory(
            ScenarioRepository scenarios,
            Scorecard rewardFunction,
            String backend,
            String prometheusUrl,
            String lokiUrl,
            Optional<String> remediationUrl,
            Duration timeout) {
        this(scenarios, rewardFunction, backend, prometheusUrl, lokiUrl, remediationUrl, timeout,
                "us-east-1", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, true, null);
    }

    @Inject
    public EnvironmentFactory(
            ScenarioRepository scenarios,
            Scorecard rewardFunction,
            @ConfigProperty(name = "ajentic.environment.backend", defaultValue = SIMULATED) String backend,
            @ConfigProperty(name = "ajentic.live.prometheus-url", defaultValue = "http://localhost:9090")
            String prometheusUrl,
            @ConfigProperty(name = "ajentic.live.loki-url", defaultValue = "http://localhost:3100") String lokiUrl,
            @ConfigProperty(name = "ajentic.live.remediation-url") Optional<String> remediationUrl,
            @ConfigProperty(name = "ajentic.live.timeout", defaultValue = "5s") Duration timeout,
            @ConfigProperty(name = "ajentic.aws.region", defaultValue = "us-east-1") String awsRegion,
            @ConfigProperty(name = "ajentic.aws.access-key-id") Optional<String> awsAccessKeyId,
            @ConfigProperty(name = "ajentic.aws.secret-access-key") Optional<String> awsSecretAccessKey,
            @ConfigProperty(name = "ajentic.aws.session-token") Optional<String> awsSessionToken,
            @ConfigProperty(name = "ajentic.aws.endpoint-url") Optional<String> awsEndpointUrl,
            @ConfigProperty(name = "ajentic.aws.allow-mutations", defaultValue = "false") boolean awsAllowMutations,
            @ConfigProperty(name = "ajentic.azure.tenant-id") Optional<String> azureTenantId,
            @ConfigProperty(name = "ajentic.azure.client-id") Optional<String> azureClientId,
            @ConfigProperty(name = "ajentic.azure.client-secret") Optional<String> azureClientSecret,
            @ConfigProperty(name = "ajentic.azure.subscription-id") Optional<String> azureSubscriptionId,
            @ConfigProperty(name = "ajentic.azure.resource-group") Optional<String> azureResourceGroup,
            @ConfigProperty(name = "ajentic.azure.workspace-id") Optional<String> azureWorkspaceId,
            @ConfigProperty(name = "ajentic.azure.endpoint-url") Optional<String> azureEndpointUrl,
            @ConfigProperty(name = "ajentic.azure.token-url") Optional<String> azureTokenUrl,
            @ConfigProperty(name = "ajentic.azure.allow-mutations", defaultValue = "false") boolean azureAllowMutations,
            @ConfigProperty(name = "ajentic.gcp.project-id") Optional<String> gcpProjectId,
            @ConfigProperty(name = "ajentic.gcp.client-email") Optional<String> gcpClientEmail,
            @ConfigProperty(name = "ajentic.gcp.private-key") Optional<String> gcpPrivateKey,
            @ConfigProperty(name = "ajentic.gcp.endpoint-url") Optional<String> gcpEndpointUrl,
            @ConfigProperty(name = "ajentic.gcp.token-url") Optional<String> gcpTokenUrl,
            @ConfigProperty(name = "ajentic.gcp.allow-mutations", defaultValue = "false") boolean gcpAllowMutations,
            @ConfigProperty(name = "ajentic.tools.allow-mutations", defaultValue = "true") boolean toolsAllowMutations,
            jakarta.enterprise.inject.Instance<io.ajentic.sre.agent.integration.SidecarClient> sidecarClientInstance) {
        this.scenarios = scenarios;
        this.rewardFunction = rewardFunction;
        this.liveConfig = new LiveTelemetryConfig(prometheusUrl, lokiUrl, remediationUrl.orElse(""), timeout);
        this.sidecarClient = (sidecarClientInstance != null && sidecarClientInstance.isResolvable()) ? sidecarClientInstance.get() : null;
        this.backend = switch (backend == null ? "" : backend.trim().toLowerCase()) {
            case SIMULATED, LOCAL_CONTAINER, CLOUD_LIVE, AWS_LIVE, AZURE_LIVE, GCP_LIVE, SIDECAR -> backend.trim().toLowerCase();
            default -> throw new IllegalStateException("Unknown environment backend: " + backend);
        };

        this.awsRegion = awsRegion;
        this.awsAccessKeyId = awsAccessKeyId.orElse("");
        this.awsSecretAccessKey = awsSecretAccessKey.orElse("");
        this.awsSessionToken = awsSessionToken.orElse("");
        this.awsEndpointUrl = awsEndpointUrl.orElse("");
        this.awsAllowMutations = awsAllowMutations;

        this.azureTenantId = azureTenantId.orElse("");
        this.azureClientId = azureClientId.orElse("");
        this.azureClientSecret = azureClientSecret.orElse("");
        this.azureSubscriptionId = azureSubscriptionId.orElse("");
        this.azureResourceGroup = azureResourceGroup.orElse("");
        this.azureWorkspaceId = azureWorkspaceId.orElse("");
        this.azureEndpointUrl = azureEndpointUrl.orElse("");
        this.azureTokenUrl = azureTokenUrl.orElse("");
        this.azureAllowMutations = azureAllowMutations;

        this.gcpProjectId = gcpProjectId.orElse("");
        this.gcpClientEmail = gcpClientEmail.orElse("");
        this.gcpPrivateKey = gcpPrivateKey.orElse("");
        this.gcpEndpointUrl = gcpEndpointUrl.orElse("");
        this.gcpTokenUrl = gcpTokenUrl.orElse("");
        this.gcpAllowMutations = gcpAllowMutations;

        this.toolsAllowMutations = toolsAllowMutations;

        LOG.infof("Environment backend: %s", this.backend);
    }

    public String backend() {
        return backend;
    }

    public Environment create(String scenarioId, long seed) {
        Scenario scenario = scenarios.byId(scenarioId).orElseThrow(() -> new UnknownScenarioException(scenarioId));
        return switch (backend) {
            case SIMULATED -> new SimulatedProductionEnvironment(scenario, seed, rewardFunction);
            case LOCAL_CONTAINER -> new LocalContainerEnvironment(scenario, liveConfig, rewardFunction);
            case CLOUD_LIVE -> new CloudLiveEnvironment(scenario, liveConfig, rewardFunction);
            case AWS_LIVE -> new AwsLiveEnvironment(
                    scenario,
                    rewardFunction,
                    new CloudHttpClient(),
                    awsRegion,
                    awsAccessKeyId,
                    awsSecretAccessKey,
                    awsSessionToken,
                    awsEndpointUrl,
                    awsAllowMutations && toolsAllowMutations,
                    false);
            case AZURE_LIVE -> new AzureLiveEnvironment(
                    scenario,
                    rewardFunction,
                    new CloudHttpClient(),
                    azureTenantId,
                    azureClientId,
                    azureClientSecret,
                    azureSubscriptionId,
                    azureResourceGroup,
                    azureWorkspaceId,
                    azureEndpointUrl,
                    azureTokenUrl,
                    azureAllowMutations && toolsAllowMutations,
                    false);
            case GCP_LIVE -> new GcpLiveEnvironment(
                    scenario,
                    rewardFunction,
                    new CloudHttpClient(),
                    gcpProjectId,
                    gcpClientEmail,
                    gcpPrivateKey,
                    gcpEndpointUrl,
                    gcpTokenUrl,
                    gcpAllowMutations && toolsAllowMutations,
                    false);
            case SIDECAR -> new io.ajentic.sre.simulation.environment.sidecar.SidecarEnvironment(
                    scenario,
                    rewardFunction,
                    sidecarClient,
                    awsRegion,
                    toolsAllowMutations);
            default -> throw new IllegalStateException("Unknown environment backend: " + backend);
        };
    }
}
