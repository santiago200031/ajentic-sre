package io.ajentic.sre;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ServerStartupTest {

    @Test
    void rootRedirectsToSwaggerUi() {
        given()
                .redirects().follow(false)
                .when().get("/")
                .then()
                .statusCode(303)
                .header("Location", containsString("/q/swagger-ui"));
    }

    @Test
    void swaggerUiIsAccessible() {
        given()
                .when().get("/q/swagger-ui/")
                .then()
                .statusCode(200)
                .body(containsString("html"));
    }

    @Test
    void openApiSpecificationIsAvailable() {
        given()
                .accept(ContentType.JSON)
                .when().get("/q/openapi")
                .then()
                .statusCode(200)
                .contentType(containsString("json"))
                .body("openapi", startsWith("3."))
                .body("info.title", notNullValue());
    }

    @Test
    void healthCheckIsUp() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void listsScenariosOverHttp() {
        given()
                .when().get("/api/v1/scenarios")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(0))
                .body("id", hasItem("checkout-latency-db-pool"));
    }

    @Test
    void simulatesScenarioOverHttp() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "scenarioId": "orders-bad-deployment",
                          "policy": "heuristic",
                          "seed": 42
                        }
                        """)
                .when().post("/api/v1/episodes/simulate")
                .then()
                .statusCode(200)
                .body("solved", equalTo(true));
    }

    @Test
    void simulatesScenarioOverSimulationsEndpoint() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "scenarioId": "orders-bad-deployment",
                          "playbook": "heuristic",
                          "seed": 42
                        }
                        """)
                .when().post("/api/v1/simulations/simulate")
                .then()
                .statusCode(200)
                .body("solved", equalTo(true));
    }

    @Test
    void asksAgentOverHttp() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "scenarioId": "checkout-latency-db-pool",
                          "question": "What is causing latency?",
                          "seed": 42
                        }
                        """)
                .when().post("/api/v1/agent/ask")
                .then()
                .statusCode(200)
                .body("answer", notNullValue());
    }

    @Test
    void listsSchedulesOverHttp() {
        given()
                .when().get("/api/v1/schedules")
                .then()
                .statusCode(200)
                .body(notNullValue());
    }

    @Test
    void benchmarkEndpointIsNotExposedInProductionRest() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "scenarios": ["orders-bad-deployment"]
                        }
                        """)
                .when().post("/api/v1/benchmarks")
                .then()
                .statusCode(404);
    }

    @Test
    void cliPackageIsCompletelyRemoved() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.ajentic.sre.cli.AjenticCommand"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.ajentic.sre.cli.CliMain"));
    }
}
