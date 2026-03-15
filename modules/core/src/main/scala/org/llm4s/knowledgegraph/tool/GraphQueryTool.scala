package org.llm4s.knowledgegraph.tool

import org.llm4s.knowledgegraph.query.{ GraphQAConfig, GraphQAPipeline, GraphQAResult }
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

/**
 * Result returned by the graph query tool to the agent.
 *
 * @param answer The generated answer
 * @param citations Sources that contributed to the answer
 * @param entityCount Number of entities used for context
 */
case class GraphQueryToolResult(
  answer: String,
  citations: Seq[String],
  entityCount: Int
)

object GraphQueryToolResult {
  implicit val graphQueryToolResultRW: ReadWriter[GraphQueryToolResult] = macroRW[GraphQueryToolResult]
}

/**
 * Agent tool integration for graph-guided question answering.
 *
 * Wraps [[GraphQAPipeline]] as a [[ToolFunction]] that an Agent can call to query
 * a knowledge graph using natural language. The tool accepts a question and optional
 * configuration, executes the full QA pipeline, and returns the answer with citations.
 *
 * @example
 * {{{
 * import org.llm4s.knowledgegraph.tool.GraphQueryTool
 * import org.llm4s.toolapi.ToolRegistry
 *
 * for {
 *   tool <- GraphQueryTool.toolSafe(llmClient, graphStore)
 *   tools = new ToolRegistry(Seq(tool))
 *   state <- agent.run("Who does Alice work with?", tools)
 * } yield state
 * }}}
 */
object GraphQueryTool {

  private val schema = Schema
    .`object`[Map[String, Any]]("Graph query parameters")
    .withProperty(
      Schema.property(
        "question",
        Schema.string("The natural language question to ask about the knowledge graph")
      )
    )
    .withProperty(
      Schema.property(
        "max_hops",
        Schema.integer("Maximum number of relationship hops for traversal (default: 3)"),
        required = false
      )
    )

  /**
   * Creates the graph query tool instance, returning a Result for safe error handling.
   *
   * @param llmClient The LLM client for the QA pipeline
   * @param graphStore The graph store containing the knowledge graph
   * @param config Optional QA pipeline configuration
   * @return Right(tool) on success, Left(error) on failure
   */
  def toolSafe(
    llmClient: LLMClient,
    graphStore: GraphStore,
    config: GraphQAConfig = GraphQAConfig()
  ): Result[ToolFunction[Map[String, Any], GraphQueryToolResult]] =
    ToolBuilder[Map[String, Any], GraphQueryToolResult](
      name = "graph_query",
      description = "Query a knowledge graph using natural language. " +
        "Finds entities, traverses relationships, and generates answers with citations. " +
        "Use this tool when you need to find information about entities and their relationships " +
        "in the knowledge graph.",
      schema = schema
    ).withHandler { extractor =>
      for {
        question <- extractor.getString("question")
        maxHops = extractor.getInt("max_hops").toOption
        pipeline = new GraphQAPipeline(
          llmClient,
          graphStore,
          maxHops.map(h => config.copy(maxHops = h)).getOrElse(config)
        )
        result <- pipeline.ask(question).left.map(e => e.formatted)
      } yield toToolResult(result)
    }.buildSafe()

  /**
   * Converts a GraphQAResult into the tool result format.
   */
  private def toToolResult(result: GraphQAResult): GraphQueryToolResult =
    GraphQueryToolResult(
      answer = result.answer,
      citations = result.citations.map { c =>
        val relStr  = c.relationship.map(r => s" via [$r]").getOrElse("")
        val propStr = if (c.property.nonEmpty) s" (${c.property}: ${c.value})" else ""
        s"[${c.nodeLabel}] ${c.nodeId}$relStr$propStr"
      },
      entityCount = result.entities.size
    )
}
