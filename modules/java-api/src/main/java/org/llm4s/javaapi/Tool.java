package org.llm4s.javaapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public method as an agent tool, for {@link JTool#fromAnnotated(Object)} (#1484). The model sees the tool's
 * name, its description and a parameter for each of the method's parameters, described by their {@link Param}.
 *
 * <pre>{@code
 * class WeatherTools {
 *     @Tool(description = "The current weather in a city")
 *     public String weather(@Param(name = "city", description = "City name") String city) {
 *         return "Sunny in " + city;
 *     }
 * }
 * }</pre>
 *
 * <p>A parameter of type {@link JToolArgs} is not one the model passes: it receives the call itself, so the method can
 * read {@link JToolArgs#idempotencyKey()} and {@link JToolArgs#toolCallId()} when an agent runs it (#1740).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /** The tool's name, as the model calls it; the method's name when empty. */
    String name() default "";

    /** What the tool does, for the model to decide when to call it. */
    String description();
}
