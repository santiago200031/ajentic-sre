package io.ajentic.sre.agent.tool;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts one {@link AgentTool} method to the {@link Tool} contract. The JSON Schema is derived from the parameter
 * types and {@link ToolParam} metadata, and invocation arguments are coerced back into those types with the managed
 * {@link ObjectMapper} (so enums honour their {@code @JsonCreator} factories).
 */
final class AnnotatedTool implements Tool {

    private final Object instance;
    private final Method method;
    private final ObjectMapper mapper;
    private final List<Binding> bindings;
    private final ToolDescriptor descriptor;

    AnnotatedTool(Object instance, Method method, AgentTool annotation, ObjectMapper mapper) {
        this.instance = instance;
        this.method = method;
        this.mapper = mapper;
        this.bindings = bindings(method);
        this.descriptor = new ToolDescriptor(
                annotation.name().isBlank() ? method.getName() : annotation.name(),
                annotation.description(),
                schema(bindings),
                annotation.capability());
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation, ToolContext context) {
        Object[] arguments = new Object[bindings.size()];
        for (Binding binding : bindings) {
            arguments[binding.index()] = binding.context() ? context : coerce(invocation, binding);
        }
        try {
            return (ToolResult) method.invoke(instance, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot invoke tool " + descriptor.name(), exception);
        }
    }

    private Object coerce(ToolInvocation invocation, Binding binding) {
        Object value = invocation.arguments().get(binding.name());
        if (value == null || (value instanceof String text && text.isBlank())) {
            if (!binding.defaultValue().isEmpty()) {
                value = binding.defaultValue();
            } else if (binding.required()) {
                throw new IllegalArgumentException("Missing required argument '" + binding.name() + "'");
            } else {
                return null;
            }
        }
        return mapper.convertValue(value, binding.type());
    }

    private static List<Binding> bindings(Method method) {
        Parameter[] parameters = method.getParameters();
        List<Binding> result = new ArrayList<>(parameters.length);
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            boolean context = ToolContext.class.isAssignableFrom(parameter.getType());
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            if (!context && annotation == null) {
                throw new IllegalStateException(
                        "Parameter '%s' of %s must be annotated with @ToolParam".formatted(parameter.getName(), method));
            }
            result.add(new Binding(
                    index,
                    parameter.getName(),
                    parameter.getType(),
                    context,
                    annotation == null || annotation.required(),
                    annotation == null ? "" : annotation.defaultValue(),
                    annotation == null ? "" : annotation.value()));
        }
        return result;
    }

    private static Map<String, Object> schema(List<Binding> bindings) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Binding binding : bindings) {
            if (binding.context()) {
                continue;
            }
            properties.put(binding.name(), property(binding));
            if (binding.required() && binding.defaultValue().isEmpty()) {
                required.add(binding.name());
            }
        }
        return ToolSchemas.object(properties, required);
    }

    private static Map<String, Object> property(Binding binding) {
        Class<?> type = binding.type();
        if (type.isEnum()) {
            List<String> values = Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
            return ToolSchemas.enumeration(binding.description(), values);
        }
        if (type == Long.class || type == long.class || type == Integer.class || type == int.class) {
            return ToolSchemas.integer(binding.description());
        }
        if (type == Boolean.class || type == boolean.class) {
            return Map.of("type", "boolean", "description", binding.description());
        }
        return ToolSchemas.string(binding.description());
    }

    private record Binding(
            int index,
            String name,
            Class<?> type,
            boolean context,
            boolean required,
            String defaultValue,
            String description) {
    }
}
