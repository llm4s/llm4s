package org.llm4s.it.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.scalatest.TagAnnotation;

/**
 * Tier 2: needs a containerised service - Postgres/pgvector, Qdrant or Neo4j.
 *
 * <p>Run with {@code sbt testIntegration}; CI runs it on every PR with the services provided as
 * job service containers. Reproducible and secret-free, so it is not behind a manual gate.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Docker {}
