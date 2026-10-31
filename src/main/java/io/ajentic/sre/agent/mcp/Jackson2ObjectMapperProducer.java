package io.ajentic.sre.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class Jackson2ObjectMapperProducer {

    @Produces
    @Singleton
    @Default
    public ObjectMapper jackson2Mapper() {
        return new ObjectMapper();
    }
}
