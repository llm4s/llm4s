package org.llm4s.javaapi.services;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.llm4s.annotation.Experimental;

/**
 * A template variable of an AI service method (#2277): the argument, as {@code String.valueOf} prints it, replaces
 * every {@code {{name}}} in the method's {@link SystemMessage} and {@link UserMessage} templates. The name is given
 * here, not read from the parameter, so no {@code javac -parameters} flag is needed. A {@code null} argument fails the
 * call with kind {@code VALIDATION}.
 *
 * @see org.llm4s.javaapi.Llm4s#aiService(Class)
 */
@Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface V {

    /** The placeholder's name: {@code @V("product")} fills {@code {{product}}}. */
    String value();
}
