/**
 * Tier tags for the integration-test module.
 *
 * <p>Every suite in {@code modules/it} must carry exactly one of these annotations. The tag
 * names the external thing the suite needs, and therefore which CI job runs it; a suite with
 * no tag is executed by nothing, which is the failure {@code it/itTierCheck} exists to
 * prevent (see issue #1143).
 *
 * <p>The tiers:
 *
 * <ul>
 *   <li>{@link org.llm4s.it.tags.Local} - nothing external. Runs in every {@code sbt test}.
 *   <li>{@link org.llm4s.it.tags.Docker} - a containerised service (Postgres/pgvector, Qdrant,
 *       Neo4j). Runs on every PR via CI service containers ({@code sbt testIntegration}).
 *   <li>{@link org.llm4s.it.tags.Workspace} - a locally built {@code workspace-runner} image
 *       plus a Docker daemon ({@code sbt testWorkspace}).
 *   <li>{@link org.llm4s.it.tags.Ollama} - a local Ollama server ({@code sbt testOllama}).
 *   <li>{@link org.llm4s.it.tags.Cloud} - live provider API keys ({@code sbt testSmoke}).
 * </ul>
 *
 * <p>These are Java annotations rather than ScalaTest {@code Tag} objects because ScalaTest
 * only honours a whole-suite tag when it is a runtime-retained Java annotation that is itself
 * annotated with {@link org.scalatest.TagAnnotation}.
 */
package org.llm4s.it.tags;
