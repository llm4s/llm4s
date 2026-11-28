package org.llm4s.knowledgegraph.query

import org.llm4s.knowledgegraph.storage.{ Direction, GraphStore }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

/**
 * Translates natural language questions into structured [[GraphQuery]] operations using an LLM.
 *
 * The translator provides the LLM with a summary of the graph's schema (node labels,
 * relationship types, sample properties) so it can generate appropriate query operations.
 *
 * @example
 * {{{
 * val translator = new GraphQueryTranslator(llmClient, graphStore)
 * val query = translator.translate("Who are Alice's coworkers?")
 * // query: Right(GraphQuery.FindNeighbors(nodeId = "alice", ...))
 * }}}
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
  def translate(question: String): Result[GraphQuery] = translate(question, Seq.empty)

  /**
   * Translates a question, telling the model the ids of entities already resolved from it, so it never has to invent
   * a node id (#2973).
   *
   * @param question The natural language question
   * @param entities Entities resolved from the question, listed in the prompt with their ids, labels and names
   * @return Right(query) on success, Left(error) on failure
   */
  def translate(question: String, entities: Seq[IdentifiedEntity]): Result[GraphQuery] = for {
    schema <- buildSchemaContext()
    prompt = buildPrompt(question, schema, entities)
    conversation = Conversation(
      messages = List(
        SystemMessage(systemPrompt),
        UserMessage(prompt)
      )
    )
    completion <- llmClient.complete(conversation, CompletionOptions(temperature = Some(0.0)))
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
      |Use only node IDs listed under "Known entities". Never invent a node ID. For an entity that is not listed,
      |use "find_nodes" to locate it inside a "composite" query, and refer to the nodes a step found as "$1",
      |"$2", ... (the step's 1-based position) in a later step's node_id, from_node_id or to_node_id:
      |   {"type": "composite", "steps": [{"type": "find_nodes", "properties": {"name": "Alice"}},
      |                                   {"type": "find_neighbors", "node_id": "$1"}]}
      |
      |find_path also takes "direction" (default "both": a path may follow an edge either way).
      |
      |Output ONLY the JSON object, nothing else.""".stripMargin

  private def buildPrompt(question: String, schemaContext: String, entities: Seq[IdentifiedEntity]): String = {
    val known =
      if (entities.isEmpty) "Known entities: none"
      else
        "Known entities (use these node IDs):\n" + entities
          .map(e => s"  - id: ${e.nodeId}, label: ${e.nodeLabel}, mentioned as: \"${e.mention}\"")
          .mkString("\n")
    s"""$schemaContext
       |
       |$known
       |
       |Question: $question
       |
       |Translate this question into a graph query JSON:""".stripMargin
  }

  /**
   * Parses the LLM JSON response into a structured GraphQuery. The object may come fenced or with prose around it;
   * a missing or wrong-typed field is a `Left`, never an exception (#2973).
   */
  private[query] def parseQueryResponse(jsonStr: String): Result[GraphQuery] =
    ModelJson.obj(jsonStr, "graph query").flatMap(json => parseStep(json, allowComposite = true))

  private def parseStep(json: ujson.Value, allowComposite: Boolean): Result[GraphQuery] =
    ModelJson.string(json, "type", "graph query").flatMap {
      case "find_nodes"                  => parseFindNodes(json)
      case "find_neighbors"              => parseFindNeighbors(json)
      case "find_path"                   => parseFindPath(json)
      case "describe_node"               => parseDescribeNode(json)
      case "composite" if allowComposite => parseComposite(json)
      case other =>
        Left(ProcessingError("query_parse", s"Unknown ${if (allowComposite) "query" else "step"} type: $other"))
    }

  private def parseFindNodes(json: ujson.Value): Result[GraphQuery] =
    for {
      label <- ModelJson.optString(json, "label", "find_nodes")
      properties <- json.obj.get("properties") match {
        case Some(obj: ujson.Obj) =>
          Right(obj.value.map { case (k, v) =>
            k -> (v match {
              case ujson.Str(s) => s
              case other        => other.toString
            })
          }.toMap)
        case None | Some(ujson.Null) => Right(Map.empty[String, String])
        case Some(other) =>
          Left(ProcessingError("query_parse", s"find_nodes: 'properties' must be an object, got $other"))
      }
    } yield GraphQuery.FindNodes(label = label, properties = properties)

  private def parseDirection(json: ujson.Value, context: String): Result[Direction] =
    json.obj.get("direction") match {
      case Some(ujson.Str(d)) if d.equalsIgnoreCase("outgoing") => Right(Direction.Outgoing)
      case Some(ujson.Str(d)) if d.equalsIgnoreCase("incoming") => Right(Direction.Incoming)
      case None | Some(ujson.Null) | Some(ujson.Str(_))         => Right(Direction.Both)
      case Some(other) => Left(ProcessingError("query_parse", s"$context: 'direction' must be a string, got $other"))
    }

  private def parseFindNeighbors(json: ujson.Value): Result[GraphQuery] =
    for {
      id        <- ModelJson.string(json, "node_id", "find_neighbors")
      direction <- parseDirection(json, "find_neighbors")
      relType   <- ModelJson.optString(json, "relationship_type", "find_neighbors")
      maxDepth  <- ModelJson.optInt(json, "max_depth", "find_neighbors")
    } yield GraphQuery.FindNeighbors(
      nodeId = id,
      direction = direction,
      relationshipType = relType,
      maxDepth = maxDepth.getOrElse(1)
    )

  private def parseFindPath(json: ujson.Value): Result[GraphQuery] =
    for {
      from      <- ModelJson.string(json, "from_node_id", "find_path")
      to        <- ModelJson.string(json, "to_node_id", "find_path")
      maxHops   <- ModelJson.optInt(json, "max_hops", "find_path")
      direction <- parseDirection(json, "find_path")
    } yield GraphQuery.FindPath(fromNodeId = from, toNodeId = to, maxHops = maxHops.getOrElse(5), direction = direction)

  private def parseDescribeNode(json: ujson.Value): Result[GraphQuery] =
    for {
      id               <- ModelJson.string(json, "node_id", "describe_node")
      includeNeighbors <- ModelJson.optBool(json, "include_neighbors", "describe_node")
    } yield GraphQuery.DescribeNode(nodeId = id, includeNeighbors = includeNeighbors.getOrElse(true))

  private def parseComposite(json: ujson.Value): Result[GraphQuery] =
    json.obj.get("steps") match {
      case Some(ujson.Arr(arr)) =>
        arr.toSeq
          .foldLeft[Result[Vector[GraphQuery]]](Right(Vector.empty)) { (acc, step) =>
            acc.flatMap(done => parseStep(step, allowComposite = false).map(done :+ _))
          }
          .map(steps => GraphQuery.CompositeQuery(steps))
      case _ => Left(ProcessingError("query_parse", "composite requires 'steps' array"))
    }
}
