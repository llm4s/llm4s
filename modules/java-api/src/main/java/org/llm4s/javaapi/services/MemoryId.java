package org.llm4s.javaapi.services;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.llm4s.annotation.Experimental;

/**
 * The conversation an AI service call belongs to (#2277). The earlier turns stored under the argument - compared with
 * {@code equals} - are sent before the new user message, and the new turn is stored after a successful call, in the
 * service's {@link ChatMemoryStore} ({@link ChatMemoryStore#inMemory()} unless the builder names another). Calls with
 * different ids never see each other's turns; a method without a {@code @MemoryId} parameter remembers nothing. A
 * {@code null} id fails the call with kind {@code VALIDATION}.
 *
 * @see org.llm4s.javaapi.Llm4s#aiService(Class)
 */
@Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface MemoryId {}
