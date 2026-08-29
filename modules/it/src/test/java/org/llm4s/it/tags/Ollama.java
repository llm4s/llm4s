package org.llm4s.it.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.scalatest.TagAnnotation;

/**
 * Tier 3: needs a local Ollama server with the test model pulled
 * ({@code ollama pull qwen2.5:0.5b}).
 *
 * <p>Run with {@code sbt testOllama}; CI runs it on pushes to main.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Ollama {}
