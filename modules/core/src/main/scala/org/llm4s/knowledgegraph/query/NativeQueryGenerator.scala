package org.llm4s.knowledgegraph.query

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Supported native graph query languages.
 */
sealed trait QueryLanguage {
  def name: String
}

object QueryLanguage {
  case object Cypher  extends QueryLanguage { val name = "Cypher"  }
  case object Gremlin extends QueryLanguage { val name = "Gremlin" }
  case object SPARQL  extends QueryLanguage { val name = "SPARQL"  }
}

/**
 * A generated native query string for a specific graph query language.
 *
 * @param language The target query language
 * @param queryString The generated query string
 * @param explanation Optional human-readable explanation of what the query does
 */
case class NativeQuery(
  language: QueryLanguage,
  queryString: String,
  explanation: String = ""
)

/**
 * Generates native graph query strings (Cypher, Gremlin, SPARQL) from natural language
 * or structured [[GraphQuery]] operations using an LLM.
 *
 * This is an extension point for graph databases that support native query languages.
 * The primary query path uses [[GraphQueryExecutor]] against the `GraphStore` trait;
 * this generator provides an alternative for engines where native queries are more efficient.
 *
 * @param llmClient The LLM client to use for query generation
 */
class NativeQueryGenerator(llmClient: LLMClient) {

  /**
   * Generates a native query from a natural language question.
   *
   * @param question The natural language question
   * @param language The target query language
   * @param schemaContext Optional schema description for the target database
   * @return Right(nativeQuery) on success, Left(error) on failure
   */
  def generate(
    question: String,
    language: QueryLanguage,
    schemaContext: String = ""
  ): Result[NativeQuery] = {
    val prompt = buildPrompt(question, language, schemaContext)
    val conversation = Conversation(
      messages = List(
        SystemMessage(systemPromptFor(language)),
        UserMessage(prompt)
      )
    )

    llmClient
      .complete(conversation, CompletionOptions(temperature = 0.0))
      .flatMap(completion => parseResponse(completion.content, language))
  }

  /**
   * Generates a native query from a structured GraphQuery.
   *
   * @param query The structured graph query
   * @param language The target query language
   * @param schemaContext Optional schema description for the target database
   * @return Right(nativeQuery) on success, Left(error) on failure
   */
  def fromGraphQuery(
    query: GraphQuery,
    language: QueryLanguage,
    schemaContext: String = ""
  ): Result[NativeQuery] = {
    val description = describeQuery(query)
    generate(description, language, schemaContext)
  }

  private def describeQuery(query: GraphQuery): String = query match {
    case GraphQuery.FindNodes(label, properties) =>
      val labelStr = label.map(l => s"with label '$l'").getOrElse("")
      val propsStr = if (properties.nonEmpty) {
        s"where ${properties.map { case (k, v) => s"$k = '$v'" }.mkString(" and ")}"
      } else ""
      s"Find all nodes $labelStr $propsStr".trim

    case GraphQuery.FindNeighbors(nodeId, direction, relType, maxDepth) =>
      val dirStr = direction.toString.toLowerCase
      val relStr = relType.map(r => s"via '$r' relationships").getOrElse("")
      s"Find $dirStr neighbors of node '$nodeId' $relStr within $maxDepth hops".trim

    case GraphQuery.FindPath(from, to, maxHops) =>
      s"Find paths from node '$from' to node '$to' within $maxHops hops"

    case GraphQuery.DescribeNode(nodeId, includeNeighbors) =>
      val neighborsStr = if (includeNeighbors) "including neighbors" else ""
      s"Describe node '$nodeId' $neighborsStr".trim

    case GraphQuery.CompositeQuery(steps) =>
      steps.map(describeQuery).mkString("; then ")
  }

  private def systemPromptFor(language: QueryLanguage): String = language match {
    case QueryLanguage.Cypher =>
      """You are a Cypher query expert. Given a natural language question and optional schema,
        |generate a valid Neo4j Cypher query. Output ONLY valid JSON with "query" and "explanation" fields.
        |Example: {"query": "MATCH (p:Person {name: 'Alice'}) RETURN p", "explanation": "Find person named Alice"}""".stripMargin

    case QueryLanguage.Gremlin =>
      """You are a Gremlin query expert. Given a natural language question and optional schema,
        |generate a valid Apache TinkerPop Gremlin traversal. Output ONLY valid JSON with "query" and "explanation" fields.
        |Example: {"query": "g.V().hasLabel('Person').has('name','Alice')", "explanation": "Find person named Alice"}""".stripMargin

    case QueryLanguage.SPARQL =>
      """You are a SPARQL query expert. Given a natural language question and optional schema,
        |generate a valid SPARQL query. Output ONLY valid JSON with "query" and "explanation" fields.
        |Example: {"query": "SELECT ?s WHERE { ?s rdf:type :Person ; :name 'Alice' }", "explanation": "Find person named Alice"}""".stripMargin
  }

  private def buildPrompt(question: String, language: QueryLanguage, schemaContext: String): String = {
    val schemaSection = if (schemaContext.nonEmpty) {
      s"""
         |Database Schema:
         |$schemaContext
         |""".stripMargin
    } else ""

    s"""${schemaSection}Question: $question
       |
       |Generate a ${language.name} query as JSON with "query" and "explanation" fields:""".stripMargin
  }

  private def parseResponse(jsonStr: String, language: QueryLanguage): Result[NativeQuery] = {
    val cleaned = jsonStr.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    Try {
      val json        = ujson.read(cleaned)
      val queryString = json("query").str
      val explanation = json.obj.get("explanation").map(_.str).getOrElse("")
      NativeQuery(language = language, queryString = queryString, explanation = explanation)
    }.fold(
      e => Left(ProcessingError("native_query_parse", s"Failed to parse native query response: ${e.getMessage}")),
      Right(_)
    )
  }
}
