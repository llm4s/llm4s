import sbt._

/**
 * The one-sentence description in the POM of every published `llm4s-*` artifact.
 *
 * A POM `<description>` is what Maven Central's search, IDE dependency pickers and dependency-management
 * tools show next to an artifact name. Every artifact used to publish its own name as its description
 * (`llm4s-core` described as "llm4s-core"), which tells a reader nothing about what to pick.
 *
 * The descriptions live in one table, so a reviewer reads them side by side, and `check` (run by
 * `sbt publishedArtifactsCheck`) fails the build for a published artifact that has no description of its
 * own, repeats another's, or only repeats its name. A new module therefore has to say what it is before it
 * can be released: a Maven Central release cannot be amended (`docs/reference/release.md`).
 *
 * Wording rule: say what the module provides, in one sentence, from what is on `main`.
 */
object PomDescriptions {

  /** Artifact name (without the Scala binary suffix) to its description. */
  val byArtifact: Map[String, String] = Map(
    "llm4s-agent" ->
      "Agent runtime for LLM4S: agents, guardrails, handoffs, orchestration, streaming and an assistant.",
    "llm4s-agent-tools" ->
      "Built-in tools for LLM4S agents: core utilities, file system, HTTP, shell and web search.",
    "llm4s-anthropic" ->
      "Anthropic Claude chat provider for LLM4S, built on the Anthropic Java SDK.",
    "llm4s-bedrock" ->
      "AWS Bedrock chat provider for LLM4S, using the Converse and ConverseStream APIs.",
    "llm4s-cohere" ->
      "Cohere embedding provider for LLM4S.",
    "llm4s-core" ->
      "Core of LLM4S, a Scala 3 toolkit for LLM applications: Result-based errors, the LLM client and provider SPI, the tool-calling API, context management and the tracing contract.",
    "llm4s-effect" ->
      "cats-effect integration for LLM4S: the LLM client and agent calls as IO.",
    "llm4s-gemini" ->
      "Google chat providers for LLM4S: the Gemini API and Vertex AI.",
    "llm4s-image" ->
      "Image generation and image vision and processing clients for LLM4S.",
    "llm4s-java-api" ->
      "Java-friendly facade over LLM4S: a client, an agent and a conversation builder for Java callers.",
    "llm4s-jina" ->
      "Jina AI embedding provider for LLM4S.",
    "llm4s-knowledgegraph" ->
      "Knowledge graph model, storage, query and LLM-based extraction for LLM4S.",
    "llm4s-knowledgegraph-neo4j" ->
      "Neo4j graph store for the LLM4S knowledge graph.",
    "llm4s-mcp" ->
      "Model Context Protocol (MCP) client, server and transports for LLM4S.",
    "llm4s-media" ->
      "Shared media vocabulary for LLM4S: media types and categories, with no I/O and no third-party dependencies.",
    "llm4s-memory" ->
      "Agent memory for LLM4S: memory managers with in-memory and SQLite stores.",
    "llm4s-memory-postgres" ->
      "Postgres and pgvector store for LLM4S agent memory.",
    "llm4s-observability" ->
      "Tracing backends for LLM4S: Langfuse, a trace collector and store, and a cost tracker.",
    "llm4s-observability-otel" ->
      "OpenTelemetry tracing backend for LLM4S.",
    "llm4s-observability-prometheus" ->
      "Prometheus metrics collector and metrics endpoint for LLM4S.",
    "llm4s-ollama" ->
      "Ollama chat and embedding provider for LLM4S.",
    "llm4s-openai" ->
      "OpenAI, Azure OpenAI and Requesty chat providers and OpenAI embeddings for LLM4S.",
    "llm4s-openai-compatible" ->
      "Chat client for OpenAI-compatible APIs in LLM4S: DeepSeek, Z.ai, OpenRouter, Mistral, Cohere and any generic endpoint.",
    "llm4s-provider-testkit" ->
      "Test checks for authors of LLM4S provider modules: discovery, registration and the config-to-client round trip.",
    "llm4s-rag" ->
      "Retrieval-augmented generation for LLM4S: vector stores, chunking, reranking, evaluation and extraction.",
    "llm4s-speech" ->
      "Speech-to-text and text-to-speech for LLM4S.",
    "llm4s-spring-boot-starter" ->
      "Spring Boot auto-configuration for the LLM4S Java client.",
    "llm4s-voyage" ->
      "Voyage AI embedding provider for LLM4S.",
    "llm4s-watsonx" ->
      "IBM watsonx.ai chat provider for LLM4S.",
    "llm4s-workspace-client" ->
      "Client for LLM4S containerised workspaces: run commands and code tasks in a sandbox over WebSocket.",
    "llm4s-workspace-shared" ->
      "Shared protocol for LLM4S workspaces: the commands and JSON codec used between the workspace client and runner.",
    "llm4s-zio" ->
      "ZIO integration for LLM4S: ZIO wrappers for the LLM client and agent."
  )

  /** The description of a project: its entry in the table, or its own name when it has none (which `check` rejects). */
  def of(artifact: String): String = byArtifact.getOrElse(artifact, artifact)

  /** The problems with the descriptions of the published artifacts, one line each. Empty when there are none. */
  def problems(published: Seq[(String, String)]): Seq[String] = {
    val perArtifact = published.sortBy(_._1).flatMap { case (artifact, description) =>
      if (description.trim.isEmpty) Some(s"$artifact: the description is empty")
      else if (description.trim == artifact)
        Some(s"$artifact: the description only repeats the artifact name (no entry in project/PomDescriptions.scala)")
      else if (description.exists(c => c == '\n' || c == '\r')) Some(s"$artifact: the description spans several lines")
      else None
    }
    val repeated = published
      .groupBy(_._2.trim)
      .collect {
        case (description, artifacts) if description.nonEmpty && artifacts.size > 1 => (description, artifacts)
      }
      .toSeq
      .sortBy(_._1)
      .map { case (_, artifacts) =>
        s"${artifacts.map(_._1).sorted.mkString(", ")}: these artifacts share one description"
      }
    perArtifact ++ repeated
  }

  /**
   * @param projects (artifact name, whether `publish / skip` is set, description) for every project in the build
   */
  def check(projects: Seq[(String, Boolean, String)], log: Logger): Unit = {
    val published = projects.collect {
      case (name, false, description) if name.startsWith("llm4s-") => (name, description)
    }
    if (published.isEmpty)
      throw new MessageOnlyException("No published llm4s-* artifacts were found: the check read an empty project list.")

    val found = problems(published)
    log.info(s"Published artifacts: ${published.size}; with their own description: ${published.size - found.size}")
    if (found.nonEmpty)
      throw new MessageOnlyException(
        s"""${found.size} problem(s) with the POM descriptions of published artifacts:
           |${found.map("  " + _).mkString("\n")}
           |Give each published module its own one-sentence entry in project/PomDescriptions.scala.""".stripMargin
      )
  }
}
