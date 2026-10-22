package org.llm4s.javaapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a parameter of a {@link Tool} method (#1484). {@code name} is how the model passes it; when empty, the
 * parameter's compiled name is used, which needs {@code javac -parameters} (without it the tool is refused, naming the
 * parameter). A parameter that is not {@code required} receives {@code null} when the model leaves it out, so its type
 * must be a boxed or reference type.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Param {

    /** The parameter's name, as the model passes it; the compiled parameter name when empty. */
    String name() default "";

    /** What the parameter means, for the model. */
    String description() default "";

    /** Whether the model must pass it. */
    boolean required() default true;
}
