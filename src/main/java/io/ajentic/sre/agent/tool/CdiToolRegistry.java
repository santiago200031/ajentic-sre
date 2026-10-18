package io.ajentic.sre.agent.tool;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.TreeMap;

/**
 * The single tool catalog. It indexes beans that implement {@link Tool} directly and methods annotated with
 * {@link AgentTool} on {@link ToolBox} beans, building the JSON Schema and argument bindings from the method
 * signatures.
 */
@ApplicationScoped
public class CdiToolRegistry implements ToolRegistry {

    private final SequencedMap<String, Tool> byName;
    private final List<Tool> ordered;
    private final List<ToolDescriptor> descriptors;

    @Inject
    public CdiToolRegistry(Instance<Tool> discovered, BeanManager beanManager, ObjectMapper mapper) {
        TreeMap<String, Tool> index = new TreeMap<>();
        discovered.forEach(tool -> index.put(tool.descriptor().name(), tool));
        for (Tool tool : annotatedTools(beanManager, mapper)) {
            index.putIfAbsent(tool.descriptor().name(), tool);
        }
        this.byName = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(index));
        this.ordered = List.copyOf(index.values());
        this.descriptors = ordered.stream().map(Tool::descriptor).toList();
    }

    @Override
    public List<Tool> all() {
        return ordered;
    }

    @Override
    @CacheResult(cacheName = "tool-registry")
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return descriptors;
    }

    private static List<Tool> annotatedTools(BeanManager beanManager, ObjectMapper mapper) {
        List<Tool> tools = new ArrayList<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (!beanClass.isAnnotationPresent(ToolBox.class)) {
                continue;
            }
            Object instance = beanManager.getReference(bean, beanClass, beanManager.createCreationalContext(bean));
            for (Method method : beanClass.getMethods()) {
                AgentTool annotation = method.getAnnotation(AgentTool.class);
                if (annotation != null) {
                    tools.add(new AnnotatedTool(instance, method, annotation, mapper));
                }
            }
        }
        return tools;
    }
}
