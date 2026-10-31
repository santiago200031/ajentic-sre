package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.payload.BenchmarkRequest;
import io.ajentic.sre.benchmark.BenchmarkRunner;
import io.ajentic.sre.benchmark.BenchmarkScorecard;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioLibrary;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/v1/benchmarks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Benchmarks", description = "Benchmark policies across scenarios and seeds.")
public class BenchmarkResource {

    private final BenchmarkRunner benchmark;
    private final ScenarioLibrary scenarioLibrary;

    @Inject
    public BenchmarkResource(BenchmarkRunner benchmark, ScenarioLibrary scenarioLibrary) {
        this.benchmark = benchmark;
        this.scenarioLibrary = scenarioLibrary;
    }

    @POST
    @Operation(summary = "Run benchmarks", description = "Benchmark policies across scenarios and seeds; returns a scorecard.")
    public BenchmarkScorecard runBenchmark(BenchmarkRequest request) {
        List<String> ids = (request == null || request.scenarios() == null || request.scenarios().isEmpty()
                || (request.scenarios().size() == 1 && "all".equalsIgnoreCase(request.scenarios().getFirst())))
                ? scenarioLibrary.all().stream().map(Scenario::id).toList()
                : request.scenarios();

        int seeds = (request != null && request.seeds() != null && request.seeds() > 0) ? request.seeds() : 2;

        List<String> policies = (request == null || request.policies() == null || request.policies().isEmpty()
                || (request.policies().size() == 1 && "all".equalsIgnoreCase(request.policies().getFirst())))
                ? List.of("HEURISTIC", "RANDOM", "LLM")
                : request.policies();

        String model = request != null ? request.model() : null;
        return benchmark.run(ids, seeds, policies, model);
    }
}
