package io.ajentic.sre.agent.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.TreeMap;

/**
 * Discovers {@link AgentPrompt} methods across the CDI container and renders them. Keeps prompt declarations and
 * argument coercion in one place instead of a hardcoded switch in the protocol handler.
 */
@ApplicationScoped
public class PromptCatalog {

    private final SequencedMap<String, Prompt> prompts;

    @Inject
    public PromptCatalog(BeanManager beanManager, ObjectMapper mapper) {
        TreeMap<String, Prompt> index = new TreeMap<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            Method[] methods = beanClass.getMethods();
            if (Arrays.stream(methods).noneMatch(method -> method.isAnnotationPresent(AgentPrompt.class))) {
                continue;
            }
            Object instance = beanManager.getReference(bean, beanClass, beanManager.createCreationalContext(bean));
            for (Method method : methods) {
                AgentPrompt annotation = method.getAnnotation(AgentPrompt.class);
                if (annotation != null) {
                    index.put(annotation.name(), new Prompt(annotation, method, instance, mapper));
                }
            }
        }
        this.prompts = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(index));
    }

    public List<PromptView> all() {
        return prompts.values().stream().map(Prompt::view).toList();
    }

    public Optional<PromptView> find(String name) {
        return Optional.ofNullable(prompts.get(name)).map(Prompt::view);
    }

    public String render(String name, Map<String, Object> arguments) {
        Prompt prompt = prompts.get(name);
        if (prompt == null) {
            throw new IllegalArgumentException("Unknown prompt: " + name);
        }
        return prompt.render(arguments);
    }

    public record PromptView(String name, String description, List<PromptArgument> arguments) {
    }

    public record PromptArgument(String name, String description, boolean required) {
    }

    private static final class Prompt {

        private final AgentPrompt annotation;
        private final Method method;
        private final Object instance;
        private final ObjectMapper mapper;
        private final List<Parameter> parameters;

        private Prompt(AgentPrompt annotation, Method method, Object instance, ObjectMapper mapper) {
            this.annotation = annotation;
            this.method = method;
            this.instance = instance;
            this.mapper = mapper;
            this.parameters = List.of(method.getParameters());
        }

        private PromptView view() {
            List<PromptArgument> arguments = parameters.stream()
                    .map(parameter -> new PromptArgument(name(parameter), description(parameter), true))
                    .toList();
            return new PromptView(annotation.name(), annotation.description(), arguments);
        }

        private String render(Map<String, Object> arguments) {
            Object[] values = new Object[parameters.size()];
            for (int index = 0; index < parameters.size(); index++) {
                Parameter parameter = parameters.get(index);
                Object value = arguments.get(name(parameter));
                if (value == null) {
                    throw new IllegalArgumentException(
                            "Missing required prompt argument '" + name(parameter) + "'");
                }
                values[index] = mapper.convertValue(value, parameter.getType());
            }
            try {
                return (String) method.invoke(instance, values);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(cause);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot render prompt " + annotation.name(), exception);
            }
        }

        private static String name(Parameter parameter) {
            PromptParam annotation = parameter.getAnnotation(PromptParam.class);
            if (annotation == null) {
                throw new IllegalStateException(
                        "Prompt parameter '%s' must be annotated with @PromptParam".formatted(parameter.getName()));
            }
            return annotation.name().isBlank() ? parameter.getName() : annotation.name();
        }

        private static String description(Parameter parameter) {
            PromptParam annotation = parameter.getAnnotation(PromptParam.class);
            return annotation == null ? "" : annotation.value();
        }
    }
}
