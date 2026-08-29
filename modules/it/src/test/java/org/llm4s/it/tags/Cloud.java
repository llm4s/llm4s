package org.llm4s.it.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.scalatest.TagAnnotation;

/**
 * Tier 4: needs live provider API keys and spends real money.
 *
 * <p>Run with {@code sbt testSmoke}; CI runs it only on manual {@code workflow_dispatch}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Cloud {}
