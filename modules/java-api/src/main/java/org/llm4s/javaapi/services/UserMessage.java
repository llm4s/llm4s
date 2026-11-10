package org.llm4s.javaapi.services;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.llm4s.annotation.Experimental;

/**
 * The user message of an AI service method (#2277), in one of two ways - never both:
 *
 * <ul>
 *   <li>on the method, its {@link #value()} is a template, whose {@code {{name}}} placeholders are replaced by the
 *       arguments of the parameters annotated {@code @V("name")};
 *   <li>on one parameter, that argument is the user message, as it is; the annotation's {@code value} is not read.
 * </ul>
 *
 * @see org.llm4s.javaapi.Llm4s#aiService(Class)
 */
@Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER})
public @interface UserMessage {

    /** The template of the user message, on a method; not read on a parameter. */
    String value() default "";
}
