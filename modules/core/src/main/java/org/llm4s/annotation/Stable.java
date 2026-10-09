package org.llm4s.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public type as covered by the 1.x compatibility promise.
 *
 * <p>A {@code @Stable} type keeps source and binary compatibility within 1.x: it is removed only
 * after a deprecation cycle, and {@code mimaReportBinaryIssues} enforces binary compatibility on the
 * module it lives in. The tier of every package is defined in {@code docs/reference/v1-scope.md};
 * this annotation records it in the code, where it cannot drift from the type it describes.
 *
 * <p>Applying it to a companion {@code object} is unnecessary: the annotation on the class, trait
 * or enum covers the pair.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Stable {}
