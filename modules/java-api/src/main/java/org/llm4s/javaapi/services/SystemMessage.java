package org.llm4s.javaapi.services;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.llm4s.annotation.Experimental;

/**
 * The system message of an AI service method, or of every method of the interface it annotates (#2277). A method's
 * own annotation replaces the interface's.
 *
 * <p>The text is a template: {@code {{name}}} is replaced by the argument of the parameter annotated
 * {@code @V("name")}. A placeholder no parameter names is an error of {@code build()}.
 *
 * @see org.llm4s.javaapi.Llm4s#aiService(Class)
 */
@Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SystemMessage {

    /** The template of the system message. */
    String value();
}
