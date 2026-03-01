package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.storage.{ Direction, GraphStore }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

import scala.util.Try

/**
 * Translates natural language questions into structured [[GraphQuery]] operations using an LLM.
 *
 * The translator provides the LLM with a summary of the graph's schema (node labels,
 * relationship types, sample properties) so it can generate appropriate query operations.
 *
 * @param llmClient The LLM client to use for translation
 * @param graphStore The graph store whose schema is used as context
 */
class GraphQueryTranslator(llmClient: LLMClient, graphStore: GraphStore) {

  /**
   * Translates a natural language question into a structured graph query.
   *
   * @param question The natural language question
   * @return Right(query) on success, Left(error) on failure
   */
  def translate(question: String): Result[GraphQuery] = for {
    schema <- buildSchemaContext()
    prompt = buildPrompt(question, schema)
    conversation = Conversation(
      messages = List(
        SystemMessage(systemPrompt),
        UserMessage(prompt)
      )
    )
    completion <- llmClient.complete(conversation, CompletionOptions(temperature = 0.0))
    query      <- parseQueryResponse(completion.content)
  } yield query

  /**
   * Builds a schema context string from the graph store for the LLM.
   * Includes node labels, relationship types, and sample properties.
   */
  private[query] def buildSchemaContext(): Result[String] =
    graphStore.loadAll().map { graph =>
      val labels        = graph.nodes.values.map(_.label).toSet
      val relationships = graph.edges.map(_.relationship).toSet

      val sampleProperties = graph.nodes.values
        .groupBy(_.label)
        .map { case (label, nodes) =>
          val props = nodes.headOption.map(_.properties.keys.mkString(", ")).getOrElse("none")
          s"  $label: properties=[$props]"
        }
        .mkString("\n")

      val edgePatterns = graph.edges
        .map { e =>
          val sourceLabel = graph.nodes.get(e.source).map(_.label).getOrElse("?")
          val targetLabel = graph.nodes.get(e.target).map(_.label).getOrElse("?")
          s"($sourceLabel)-[:${e.relationship}]->($targetLabel)"
        }
        .toSet
        .take(20)
        .mkString("\n  ")

      s"""Graph Schema:
         |Node Labels: ${labels.mkString(", ")}
         |Relationship Types: ${relationships.mkString(", ")}
         |Node Properties by Label:
         |$sampleProperties
         |Edge Patterns (sample):
         |  $edgePatterns
         |Total Nodes: ${graph.nodes.size}
         |Total Edges: ${graph.edges.size}""".stripMargin
    }

  private val systemPrompt: String =
    """You are a graph query translator. Given a natural language question and a graph schema,
      |you produce a structured JSON query that can be executed against the graph.
      |
      |You must output ONLY valid JSON with no additional text. The JSON must have a "type" field
      |indicating the query type, plus the relevant parameters.
      |
      |Supported query types:
      |
      |1. "find_nodes" - Find nodes by label and/or properties
      |   {"type": "find_nodes", "label": "Person", "properties": {"name": "Alice"}}
      |
      |2. "find_neighbors" - Find neighbors of a node
      |   {"type": "find_neighbors", "node_id": "node1", "direction": "both", "relationship_type": "KNOWS", "max_depth": 1}
      |
      |3. "find_path" - Find paths between two nodes
      |   {"type": "find_path", "from_node_id": "node1", "to_node_id": "node2", "max_hops": 5}
      |
      |4. "describe_node" - Get details about a specific node
      |   {"type": "describe_node", "node_id": "node1", "include_neighbors": true}
      |
      |5. "composite" - Execute multiple queries in sequence
      |   {"type": "composite", "steps": [{"type": "find_nodes", ...}, {"type": "find_neighbors", ...}]}
      |
      |Direction values: "outgoing", "incoming", "both"
      |
      |When a question references entities by name, first use "find_nodes" to locate them,
      |then use other query types with the found node IDs. Prefer "composite" for multi-step questions.
      |
      |Output ONLY the JSON object, nothing else.""".stripMargin

  private def buildPrompt(question: String, schemaContext: String): String =
    s"""$schemaContext
       |
       |Question: $question
       |
       |Translate this question into a graph query JSON:""".stripMargin

  /**
   * Parses the LLM JSON response into a structured GraphQuery.
   */
  private[query] def parseQueryResponse(jsonStr: String): Result[GraphQuery] = {
    val cleaned = jsonStr.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    Try(ujson.read(cleaned)).fold(
      e => Left(ProcessingError("query_parse", s"Invalid JSON in LLM response: ${e.getMessage}")),
      json => {
        val queryType = json("type").str
        queryType match {
          case "find_nodes"     => parseFindNodes(json)
          case "find_neighbors" => parseFindNeighbors(json)
          case "find_path"      => parseFindPath(json)
          case "describe_node"  => parseDescribeNode(json)
          case "composite"      => parseComposite(json)
          case other            => Left(ProcessingError("query_parse", s"Unknown query type: $other"))
        }
      }
    )
  }

  private def parseFindNodes(json: ujson.Value): Result[GraphQuery] = {
    val label = json.obj.get("label").flatMap {
      case ujson.Str(s) => Some(s)
      case ujson.Null   => None
      case _            => None
    }

    val properties = json.obj.get("properties") match {
      case Some(obj: ujson.Obj) =>
        obj.value.map { case (k, v) =>
          k -> (v match {
            case ujson.Str(s) => s
            case other        => other.toString
          })
        }.toMap
      case _ => Map.empty[String, String]
    }

    Right(GraphQuery.FindNodes(label = label, properties = properties))
  }

  private def parseFindNeighbors(json: ujson.Value): Result[GraphQuery] = {
    val nodeId = json.obj.get("node_id").map(_.str)

    nodeId match {
      case None =>
        Left(ProcessingError("query_parse", "find_neighbors requires 'node_id'"))
      case Some(id) =>
        val direction = json.obj.get("direction").map(_.str) match {
          case Some("outgoing") => Direction.Outgoing
          case Some("incoming") => Direction.Incoming
          case _                => Direction.Both
        }
        val relType = json.obj.get("relationship_type").flatMap {
          case ujson.Str(s) => Some(s)
          case _            => None
        }
        val maxDepth = json.obj.get("max_depth").map(_.num.toInt).getOrElse(1)

        Right(
          GraphQuery.FindNeighbors(
            nodeId = id,
            direction = direction,
            relationshipType = relType,
            maxDepth = maxDepth
          )
        )
    }
  }

  private def parseFindPath(json: ujson.Value): Result[GraphQuery] = {
    val from = json.obj.get("from_node_id").map(_.str)
    val to   = json.obj.get("to_node_id").map(_.str)

    (from, to) match {
      case (Some(f), Some(t)) =>
        val maxHops = json.obj.get("max_hops").map(_.num.toInt).getOrElse(5)
        Right(GraphQuery.FindPath(fromNodeId = f, toNodeId = t, maxHops = maxHops))
      case _ =>
        Left(ProcessingError("query_parse", "find_path requires 'from_node_id' and 'to_node_id'"))
    }
  }

  private def parseDescribeNode(json: ujson.Value): Result[GraphQuery] = {
    val nodeId = json.obj.get("node_id").map(_.str)

    nodeId match {
      case None =>
        Left(ProcessingError("query_parse", "describe_node requires 'node_id'"))
      case Some(id) =>
        val includeNeighbors = json.obj.get("include_neighbors").forall {
          case ujson.Bool(b) => b
          case _             => true
        }
        Right(GraphQuery.DescribeNode(nodeId = id, includeNeighbors = includeNeighbors))
    }
  }

  private def parseComposite(json: ujson.Value): Result[GraphQuery] = {
    val stepsJson = json.obj.get("steps") match {
      case Some(ujson.Arr(arr)) => Right(arr.toSeq)
      case _                    => Left(ProcessingError("query_parse", "composite requires 'steps' array"))
    }

    stepsJson.flatMap { steps =>
      val parsed = steps.map { stepJson =>
        val queryType = stepJson("type").str
        queryType match {
          case "find_nodes"     => parseFindNodes(stepJson)
          case "find_neighbors" => parseFindNeighbors(stepJson)
          case "find_path"      => parseFindPath(stepJson)
          case "describe_node"  => parseDescribeNode(stepJson)
          case other            => Left(ProcessingError("query_parse", s"Unknown step type: $other"))
        }
      }

      parsed
        .collectFirst { case Left(e) => Left(e) }
        .getOrElse(Right(GraphQuery.CompositeQuery(parsed.collect { case Right(q) => q })))
    }
  }
}
