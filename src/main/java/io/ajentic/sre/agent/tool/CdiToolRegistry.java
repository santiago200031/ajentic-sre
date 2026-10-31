package io.ajentic.sre.agent.tool;

import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.TreeMap;

/**
 * The single tool catalog. Indexes beans that implement {@link Tool} directly and methods annotated with
 * {@link io.quarkiverse.mcp.server.Tool} or {@link AgentTool} on {@link ToolBox} beans, building JSON Schemas
 * via LangChain4j {@link JsonObjectSchema}.
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
        for (Tool tool : discoverAnnotatedTools(beanManager, mapper)) {
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
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return descriptors;
    }

    private static List<Tool> discoverAnnotatedTools(BeanManager beanManager, ObjectMapper mapper) {
        List<Tool> tools = new ArrayList<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (!beanClass.isAnnotationPresent(ToolBox.class)) {
                continue;
            }
            Object instance = beanManager.getReference(bean, beanClass, beanManager.createCreationalContext(bean));
            for (Method method : beanClass.getMethods()) {
                io.quarkiverse.mcp.server.Tool mcpTool = method.getAnnotation(io.quarkiverse.mcp.server.Tool.class);
                AgentTool agentTool = method.getAnnotation(AgentTool.class);
                if (mcpTool == null && agentTool == null) {
                    continue;
                }
                String name = mcpTool != null && !mcpTool.name().isBlank() ? mcpTool.name()
                        : (agentTool != null && !agentTool.name().isBlank() ? agentTool.name() : method.getName());
                String description = mcpTool != null && !mcpTool.description().isBlank() ? mcpTool.description()
                        : (agentTool != null ? agentTool.description() : "");
                Capability capability = agentTool != null ? agentTool.capability()
                        : (name.startsWith("apply_") || name.startsWith("start_") || name.startsWith("run_") || name.startsWith("declare_")
                                ? Capability.MUTATE : Capability.READ);

                tools.add(buildTool(instance, method, name, description, capability, mapper));
            }
        }
        return tools;
    }

    private static Tool buildTool(Object instance, Method method, String name, String description,
                                  Capability capability, ObjectMapper mapper) {
        Parameter[] parameters = method.getParameters();
        List<ParamBinding> bindings = new ArrayList<>();
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

        List<String> requiredNames = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> type = param.getType();
            if (ToolContext.class.isAssignableFrom(type)) {
                bindings.add(new ParamBinding(i, null, type, true, false, false, ""));
                continue;
            }
            if (McpConnection.class.isAssignableFrom(type)) {
                bindings.add(new ParamBinding(i, null, type, false, true, false, ""));
                continue;
            }

            ToolArg arg = param.getAnnotation(ToolArg.class);
            ToolParam toolParam = param.getAnnotation(ToolParam.class);

            String pName = arg != null && !arg.name().isBlank() ? arg.name() : param.getName();
            String pDesc = arg != null && !arg.description().isBlank() ? arg.description()
                    : (toolParam != null ? toolParam.value() : "");
            boolean required = arg != null ? arg.required()
                    : (toolParam == null || toolParam.required());
            String defaultValue = toolParam != null ? toolParam.defaultValue() : "";

            JsonSchemaElement element = schemaElementFor(type, pDesc);
            schemaBuilder.addProperty(pName, element);
            if (required) {
                requiredNames.add(pName);
            }

            bindings.add(new ParamBinding(i, pName, type, false, false, required, defaultValue));
        }

        if (!requiredNames.isEmpty()) {
            schemaBuilder.required(requiredNames);
        }

        JsonObjectSchema jsonSchema = schemaBuilder.build();
        Map<String, Object> schemaMap = JsonSchemaElementJsonUtils.toMap(jsonSchema);
        ToolDescriptor descriptor = new ToolDescriptor(name, description, schemaMap, capability);

        return new InvokableTool(descriptor, instance, method, bindings, mapper);
    }

    private static JsonSchemaElement schemaElementFor(Class<?> type, String description) {
        if (type == Integer.class || type == int.class) {
            return JsonIntegerSchema.builder().description(description).build();
        }
        if (List.class.isAssignableFrom(type)) {
            return JsonArraySchema.builder()
                    .items(JsonStringSchema.builder().build())
                    .description(description)
                    .build();
        }
        if (type.isEnum()) {
            List<String> values = Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
            return JsonEnumSchema.builder()
                    .enumValues(values)
                    .description(description)
                    .build();
        }
        return JsonStringSchema.builder().description(description).build();
    }

    private record ParamBinding(int index, String name, Class<?> type, boolean context, boolean connection,
                                boolean required, String defaultValue) {}

    private record InvokableTool(ToolDescriptor descriptor, Object instance, Method method,
                                 List<ParamBinding> bindings, ObjectMapper mapper) implements Tool {
        @Override
        public ToolResult invoke(ToolInvocation invocation, ToolContext context) {
            Object[] args = new Object[bindings.size()];
            for (ParamBinding binding : bindings) {
                if (binding.context()) {
                    args[binding.index()] = context;
                } else if (binding.connection()) {
                    args[binding.index()] = null;
                } else {
                    Object val = invocation.arguments().get(binding.name());
                    if (val == null || (val instanceof String s && s.isBlank())) {
                        if (!binding.defaultValue().isEmpty()) {
                            val = binding.defaultValue();
                        } else if (binding.required()) {
                            throw new IllegalArgumentException("Missing required argument '" + binding.name() + "'");
                        } else {
                            val = null;
                        }
                    }
                    args[binding.index()] = val == null ? null : mapper.convertValue(val, binding.type());
                }
            }
            try {
                Object res = ScopedValue.where(io.ajentic.sre.agent.context.AgentExecutionContext.TOOL_CONTEXT, context)
                        .call(() -> method.invoke(instance, args));
                if (res instanceof ToolResponse toolResponse) {
                    String content = toolResponse.firstContent() != null
                            ? toolResponse.firstContent().asText().text() : "";
                    return toolResponse.isError() ? ToolResult.error(content) : ToolResult.ok(content);
                }
                if (res instanceof ToolResult toolResult) {
                    return toolResult;
                }
                return ToolResult.ok(res == null ? "" : res.toString());
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
    }
}
