package org.llm4s.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public type as <em>not</em> covered by the 1.x compatibility promise.
 *
 * <p>An {@code @Experimental} type can change or disappear in a minor release; a migration note ships
 * with the change in that release's CHANGELOG. Use it for a type that lives in a module with
 * {@code @Stable} types, so the exception is visible in the code and a MiMa filter has a reason. A
 * type that is neither annotated is in a module 1.0 does not freeze.
 *
 * <p>Applying it to a companion {@code object} is unnecessary: the annotation on the class, trait
 * or enum covers the pair.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Experimental {}
