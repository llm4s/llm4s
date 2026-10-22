package org.llm4s.javaapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tools defined from plain Java, for {@code JToolSpec} (#1484): one annotated class, one lambda tool. */
final class ToolsCheck {

    private ToolsCheck() {}

    /** Every call any tool here received, as "name:arguments". */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    enum Unit { CELSIUS, FAHRENHEIT }

    /** Annotated tools: a required string, an enum, an optional boxed int; and a static one that throws. */
    public static final class WeatherTools {

        @Tool(description = "The current weather in a city")
        public String weather(
            @Param(name = "city", description = "City name") String city,
            @Param(name = "unit", description = "Temperature unit") Unit unit,
            @Param(name = "days", description = "Days ahead", required = false) Integer days
        ) {
            CALLS.add("weather:" + city + "," + unit + "," + days);
            return "Sunny in " + city + (unit == Unit.CELSIUS ? ", 21C" : ", 70F");
        }

        @Tool(name = "explode", description = "Always fails")
        public static String fails(@Param(name = "why") String why) {
            CALLS.add("explode:" + why);
            throw new IllegalStateException("cannot: " + why);
        }
    }

    /** A class whose tool parameter has no name: refused unless compiled with -parameters. */
    public static final class Unnamed {
        @Tool(description = "No parameter name")
        public String echo(String text) {
            return text;
        }
    }

    /** An annotated tool that reads the call it runs as (#1740): its JToolArgs parameter is not shown to the model. */
    public static final class KeyedTools {

        @Tool(description = "Charges an amount once")
        public String charge(JToolArgs call, @Param(name = "cents", description = "Amount in cents") long cents) {
            CALLS.add("charge:" + cents + "," + call.toolCallId().orElse("none") + "," + call.idempotencyKey().orElse("none"));
            return call.idempotencyKey().orElse("none");
        }
    }

    /** A lambda tool that reads the call it runs as (#1740). */
    static LlmResult<JTool> refund() {
        return JTool.builder("refund", "Refunds an amount once")
            .param("cents", long.class, "Amount in cents")
            .handler(args -> {
                CALLS.add("refund:" + args.getLong("cents") + "," + args.toolCallId().orElse("none") + ","
                    + args.idempotencyKey().orElse("none"));
                return args.idempotencyKey().orElse("none");
            })
            .build();
    }

    /** A class with no tool. */
    public static final class NoTools {
        public String notATool() {
            return "";
        }
    }

    /** A lambda tool reading every kind of argument. */
    static LlmResult<JTool> calculator() {
        return JTool.builder("add", "Adds two numbers")
            .param("a", long.class, "First")
            .param("b", double.class, "Second")
            .optionalParam("round", Boolean.class, "Round the result")
            .handler(args -> {
                CALLS.add("add:" + args.getLong("a") + "," + args.getDouble("b") + "," + args.has("round"));
                double sum = args.getLong("a") + args.getDouble("b");
                return args.has("round") && args.getBoolean("round") ? Map.of("sum", Math.round(sum)) : sum;
            })
            .build();
    }

    /** The agent the spec runs: the annotated tools and the lambda tools together. */
    static LlmResult<JAgent> agent(JLlmClient client) {
        List<JTool> tools = new ArrayList<>(JTool.fromAnnotated(new WeatherTools()).get());
        tools.add(calculator().get());
        tools.addAll(JTool.fromAnnotated(new KeyedTools()).get());
        tools.add(refund().get());
        return Llm4s.createAgentWithTools(client, tools);
    }
}
