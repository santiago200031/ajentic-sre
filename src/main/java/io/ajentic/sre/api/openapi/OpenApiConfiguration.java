package io.ajentic.sre.api.openapi;

import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Application-level OpenAPI metadata: API info, the resource tags that group the surface in Swagger UI, and the
 * default server. Declared as a JAX-RS {@link Application} so SmallRye OpenAPI picks it up at build time.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "Ajentic SRE API",
                version = "1.0.0",
                description = "Practice range and autonomous agent harness for infrastructure incident response.",
                license = @License(name = "Apache 2.0"),
                contact = @Contact(name = "Ajentic SRE Team")),
        servers = {@Server(url = "/", description = "Default Server")},
        tags = {
                @Tag(name = "Agent", description = "Autonomous ReAct investigation agent and interactive sessions"),
                @Tag(name = "Simulations", description = "Deterministic chaos simulation and incident response playbooks"),
                @Tag(name = "Episodes", description = "Legacy incident simulation endpoint (backwards-compatible alias)"),
                @Tag(name = "Scenarios", description = "Incident scenario definitions and operational runbooks"),
                @Tag(name = "Schedules", description = "Versioned cron scheduler for recurring and manual drills"),
                @Tag(name = "Tools", description = "Registered SRE tool catalog"),
                @Tag(name = "MCP", description = "Model Context Protocol HTTP streamable transport")
        })
public class OpenApiConfiguration extends Application {
}
