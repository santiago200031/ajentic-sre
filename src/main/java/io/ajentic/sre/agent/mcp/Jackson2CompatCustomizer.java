package io.ajentic.sre.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.jackson.JsonMapperBuilderCustomizer;
import jakarta.inject.Singleton;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Bridges Jackson 2 JsonNode serialization into Quarkus 4's Jackson 3 mapper so that MCP schemas and payloads
 * produced as com.fasterxml.jackson.databind.JsonNode are serialized as valid raw JSON rather than POJOs.
 */
@Singleton
public class Jackson2CompatCustomizer implements JsonMapperBuilderCustomizer {

    @Override
    public void customize(JsonMapper.Builder builder) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(JsonNode.class, new ValueSerializer<JsonNode>() {
            @Override
            public void serialize(JsonNode value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                gen.writeRawValue(value.toString());
            }
        });
        builder.addModule(module);
    }
}
