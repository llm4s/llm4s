package org.llm4s.knowledgegraph.neo4j

import org.llm4s.util.Redaction
import org.llm4s.error.ProcessingError
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.knowledgegraph.storage._
import org.llm4s.types.Result
import org.llm4s.security.Secret
import org.neo4j.driver._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * Neo4j implementation of GraphStore.
 *
 * Maps GraphStore operations to Cypher queries via the official Neo4j Java driver.
 * All nodes carry the `:LLM4S` label for namespace isolation alongside any
 * user-defined label stored as the `llm4s_label` property.
 *
 * Properties round-trip as the in-memory store keeps them: strings, booleans and numbers are stored natively (a whole
 * number as an integer, so a property filter on `42` matches as it does in memory), an array of one primitive type
 * as a Neo4j list, and any other value (`null`, an object, a nested or mixed array) as JSON in the reserved
 * `llm4s_json` property. Property keys starting with `llm4s_` are reserved for the store: an `upsertNode` or
 * `upsertEdge` that uses one is a `Left` (#2969).
 *
 * Relationship types and property keys may be any non-empty string, as in the other stores, except that a
 * relationship type with a backslash is refused: a relationship type is written backtick-quoted, and a property key is
 * passed as a parameter (#2970). The factories create the schema, and return a `Left` when they cannot (a user
 * without schema rights needs an administrator to run these once):
 * {{{
 * CREATE CONSTRAINT llm4s_id_unique IF NOT EXISTS FOR (n:LLM4S) REQUIRE n.llm4s_id IS UNIQUE
 * CREATE INDEX llm4s_label_index IF NOT EXISTS FOR (n:LLM4S) ON (n.llm4s_label)
 * }}}
 *
 * Connection management: each GraphStore call acquires a single session, executes
 * the query, then closes the session — mirroring the PgVectorStore pattern.
 * The driver (and its internal connection pool) is managed externally; pass
 * `ownsDriver = true` only when this store should close the driver on `close()`.
 *
 * Thread-safety: the Neo4j Java driver is thread-safe; this class inherits that.
 *
 * @param driver     Neo4j Java driver instance
 * @param database   Target database name (default "neo4j")
 * @param ownsDriver Whether to close the driver on `close()` (default true)
 */
final class Neo4jGraphStore private (
  private val driver: Driver,
  val database: String,
  private val ownsDriver: Boolean
) extends GraphStore
    with AutoCloseable {

  // ─────────────────────────────────────────────────────────────────────────
  // GraphStore implementation
  // ─────────────────────────────────────────────────────────────────────────

  override def upsertNode(node: Node): Result[Unit] =
    Neo4jProperties.encode(node.properties, s"Node(${node.id})").flatMap { props =>
      withSession { session =>
        val params = new java.util.HashMap[String, AnyRef]()
        params.put("id", node.id)
        params.put("label", node.label)
        params.put("props", props)
        session.executeWriteWithoutResult { tx =>
          // The identity is set after the properties, so nothing in them can replace it
          tx.run(
            """MERGE (n:LLM4S {llm4s_id: $id})
              |SET n = $props
              |SET n.llm4s_id = $id, n.llm4s_label = $label""".stripMargin,
            params
          )
          ()
        }
        Right(())
      }
    }

  override def upsertEdge(edge: Edge): Result[Unit] =
    (for {
      relType <- validateRelType(edge.relationship)
      props   <- Neo4jProperties.encode(edge.properties, s"Edge(${edge.source}->${edge.target})")
    } yield (relType, props)).flatMap { case (relType, props) =>
      withSession { session =>
        val params = new java.util.HashMap[String, AnyRef]()
        params.put("src", edge.source)
        params.put("tgt", edge.target)
        params.put("props", props)
        Try {
          session.executeWrite { tx =>
            val srcExists =
              tx.run("MATCH (n:LLM4S {llm4s_id: $src}) RETURN count(n) AS c", params).single().get("c").asLong() > 0
            val tgtExists =
              tx.run("MATCH (n:LLM4S {llm4s_id: $tgt}) RETURN count(n) AS c", params).single().get("c").asLong() > 0

            if (!srcExists || !tgtExists) {
              Left(
                ProcessingError(
                  "neo4j-store",
                  s"Edge(${edge.source}->${edge.target}): source or target node does not exist"
                )
              )
            } else {
              tx.run(
                s"""MATCH (a:LLM4S {llm4s_id: $$src}), (b:LLM4S {llm4s_id: $$tgt})
                   |MERGE (a)-[r:${Neo4jGraphStore.quote(relType)}]->(b)
                   |SET r = $$props""".stripMargin,
                params
              )
              Right(())
            }
          }
        }.toEither.left
          .map(e => ProcessingError("neo4j-store", s"Failed to upsert edge: ${Redaction.scrubKnown(e.getMessage)}"))
          .flatMap(identity)
      }
    }

  override def getNode(id: String): Result[Option[Node]] =
    withSession { session =>
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("id", id)
      Right(session.executeRead { tx =>
        val rs = tx.run("MATCH (n:LLM4S {llm4s_id: $id}) RETURN n", params)
        if (rs.hasNext) Some(recordToNode(rs.next().get("n").asNode()))
        else None
      })
    }

  override def getNeighbors(nodeId: String, direction: Direction = Direction.Both): Result[Seq[EdgeNodePair]] =
    withSession { session =>
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("id", nodeId)
      val cypher = direction match {
        case Direction.Outgoing =>
          "MATCH (a:LLM4S {llm4s_id: $id})-[r]->(b:LLM4S) RETURN r, b, type(r) AS relType"
        case Direction.Incoming =>
          "MATCH (a:LLM4S {llm4s_id: $id})<-[r]-(b:LLM4S) RETURN r, b, type(r) AS relType"
        case Direction.Both =>
          // DISTINCT: a self-loop is one neighbour, as in the other stores (#2970)
          "MATCH (a:LLM4S {llm4s_id: $id})-[r]-(b:LLM4S) RETURN DISTINCT r, b, type(r) AS relType, startNode(r).llm4s_id AS srcId, endNode(r).llm4s_id AS tgtId"
      }

      Right(session.executeRead { tx =>
        val rs  = tx.run(cypher, params)
        val buf = scala.collection.mutable.ArrayBuffer.empty[EdgeNodePair]
        while (rs.hasNext) {
          val record   = rs.next()
          val relType  = record.get("relType").asString()
          val relProps = fromNeo4jRelProps(record.get("r").asRelationship())
          val neighbor = recordToNode(record.get("b").asNode())
          val (src, tgt) = direction match {
            case Direction.Outgoing => (nodeId, neighbor.id)
            case Direction.Incoming => (neighbor.id, nodeId)
            case Direction.Both =>
              (record.get("srcId").asString(), record.get("tgtId").asString())
          }
          buf += EdgeNodePair(Edge(src, tgt, relType, relProps), neighbor)
        }
        buf.toSeq
      })
    }

  override def query(filter: GraphFilter): Result[Graph] =
    buildWhereClause(filter).flatMap { case (whereClauses, params) =>
      withSession { session =>
        val nodeWhere = if (whereClauses.isEmpty) "" else s"WHERE ${whereClauses.mkString(" AND ")}"

        val nodes: Map[String, Node] = session.executeRead { tx =>
          val rs  = tx.run(s"MATCH (n:LLM4S) $nodeWhere RETURN n", params)
          val buf = scala.collection.mutable.ArrayBuffer.empty[(String, Node)]
          while (rs.hasNext) {
            val n = recordToNode(rs.next().get("n").asNode())
            buf += n.id -> n
          }
          buf.toMap
        }

        val edges: List[Edge] = if (nodes.isEmpty) {
          List.empty
        } else {
          session.executeRead { tx =>
            val edgeParams = new java.util.HashMap[String, AnyRef]()
            edgeParams.put("nodeIds", nodes.keys.toList.asJava)
            val relTypeClause = filter.relationshipType match {
              case Some(rt) =>
                edgeParams.put("filterRelType", rt)
                " AND type(r) = $filterRelType"
              case None =>
                ""
            }
            val rs = tx.run(
              s"MATCH (a:LLM4S)-[r]->(b:LLM4S) WHERE a.llm4s_id IN $$nodeIds AND b.llm4s_id IN $$nodeIds$relTypeClause RETURN a.llm4s_id AS src, b.llm4s_id AS tgt, type(r) AS relType, r",
              edgeParams
            )
            val buf = scala.collection.mutable.ArrayBuffer.empty[Edge]
            while (rs.hasNext) {
              val rec      = rs.next()
              val relProps = fromNeo4jRelProps(rec.get("r").asRelationship())
              buf += Edge(
                source = rec.get("src").asString(),
                target = rec.get("tgt").asString(),
                relationship = rec.get("relType").asString(),
                properties = relProps
              )
            }
            buf.toList
          }
        }

        Right(Graph(nodes, edges))
      }
    }

  override def traverse(startId: String, config: TraversalConfig = TraversalConfig()): Result[Seq[Node]] =
    withSession { session =>
      // Breadth-first, one query per level, rather than one variable-length pattern.
      //
      // `MATCH p=(start)-[*0..]-(n)` enumerates every relationship-unique path before
      // `min(length(p))` collapses them back to one row per node, so on a cyclic or densely
      // connected graph the work grows exponentially in the number of edges - and
      // TraversalConfig's default depth is unbounded, which is the worst case rather than a
      // rare one. Expanding a whole frontier per level visits each node exactly once, at the
      // cost of one round trip per level.
      //
      // Semantics follow GraphTraversal.bfs, which backs the in-memory store: nodes in
      // `visitedNodeIds` are skipped rather than traversed through, and the start node is
      // included at depth 0 when it exists.
      val expand = config.direction match {
        case Direction.Outgoing => "MATCH (a:LLM4S)-[]->(b:LLM4S)"
        case Direction.Incoming => "MATCH (a:LLM4S)<-[]-(b:LLM4S)"
        case Direction.Both     => "MATCH (a:LLM4S)-[]-(b:LLM4S)"
      }
      val expandCypher =
        s"$expand WHERE a.llm4s_id IN $$frontier AND NOT b.llm4s_id IN $$seen RETURN DISTINCT b"

      Right(session.executeRead { tx =>
        val startParams = new java.util.HashMap[String, AnyRef]()
        startParams.put("startId", startId)
        val startResult = tx.run("MATCH (n:LLM4S {llm4s_id: $startId}) RETURN n", startParams)

        if (!startResult.hasNext || config.visitedNodeIds.contains(startId)) Seq.empty
        else {
          val startNode = recordToNode(startResult.next().get("n").asNode())

          def expandLevel(frontier: Seq[String], seen: Set[String]): Seq[Node] = {
            val params = new java.util.HashMap[String, AnyRef]()
            params.put("frontier", frontier.asJava)
            params.put("seen", seen.toList.asJava)
            val rs  = tx.run(expandCypher, params)
            val buf = scala.collection.mutable.ArrayBuffer.empty[Node]
            while (rs.hasNext)
              buf += recordToNode(rs.next().get("b").asNode())
            // By id within a level, as GraphTraversal.bfs orders it; sorted here so ids compare as in Scala (#2970)
            buf.toSeq.sortBy(_.id)
          }

          @scala.annotation.tailrec
          def loop(frontier: Seq[String], seen: Set[String], depth: Int, acc: Vector[Node]): Vector[Node] =
            if (frontier.isEmpty || depth >= config.maxDepth) acc
            else {
              val next = expandLevel(frontier, seen)
              if (next.isEmpty) acc
              else loop(next.map(_.id), seen ++ next.map(_.id), depth + 1, acc ++ next)
            }

          loop(Seq(startId), config.visitedNodeIds + startId, 0, Vector(startNode))
        }
      })
    }

  override def deleteNode(id: String): Result[Unit] =
    withSession { session =>
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("id", id)
      session.executeWriteWithoutResult { tx =>
        tx.run("MATCH (n:LLM4S {llm4s_id: $id}) DETACH DELETE n", params)
        ()
      }
      Right(())
    }

  override def deleteEdge(source: String, target: String, relationship: String): Result[Unit] =
    withSession { session =>
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("src", source)
      params.put("tgt", target)
      params.put("relType", relationship)
      session.executeWriteWithoutResult { tx =>
        // The type is a parameter, so any type, even one upsertEdge could not write, is a Right (#2970)
        tx.run(
          "MATCH (a:LLM4S {llm4s_id: $src})-[r]->(b:LLM4S {llm4s_id: $tgt}) WHERE type(r) = $relType DELETE r",
          params
        )
        ()
      }
      Right(())
    }

  override def loadAll(): Result[Graph] =
    withSession { session =>
      val nodes: Map[String, Node] = session.executeRead { tx =>
        val rs  = tx.run("MATCH (n:LLM4S) RETURN n")
        val buf = scala.collection.mutable.ArrayBuffer.empty[(String, Node)]
        while (rs.hasNext) {
          val n = recordToNode(rs.next().get("n").asNode())
          buf += n.id -> n
        }
        buf.toMap
      }

      val edges: List[Edge] = session.executeRead { tx =>
        val rs =
          tx.run("MATCH (a:LLM4S)-[r]->(b:LLM4S) RETURN a.llm4s_id AS src, b.llm4s_id AS tgt, type(r) AS relType, r")
        val buf = scala.collection.mutable.ArrayBuffer.empty[Edge]
        while (rs.hasNext) {
          val rec      = rs.next()
          val relProps = fromNeo4jRelProps(rec.get("r").asRelationship())
          buf += Edge(
            source = rec.get("src").asString(),
            target = rec.get("tgt").asString(),
            relationship = rec.get("relType").asString(),
            properties = relProps
          )
        }
        buf.toList
      }

      Right(Graph(nodes, edges))
    }

  override def stats(): Result[GraphStats] =
    withSession { session =>
      session.executeRead { tx =>
        val nodeCount = tx.run("MATCH (n:LLM4S) RETURN count(n) AS c").single().get("c").asLong()
        val edgeCount = tx.run("MATCH (a:LLM4S)-[r]->(b:LLM4S) RETURN count(r) AS c").single().get("c").asLong()
        // 2E/V, as the other stores compute it; a relationship to a node outside the store is not counted (#2970)
        val avgDegree = if (nodeCount > 0) (edgeCount * 2.0) / nodeCount else 0.0
        val densestNodeId =
          if (nodeCount == 0) None
          else {
            val rs = tx.run(
              "MATCH (n:LLM4S) OPTIONAL MATCH (n)-[r]-(:LLM4S) WITH n.llm4s_id AS id, count(DISTINCT r) AS deg " +
                "RETURN id, deg ORDER BY deg DESC, id ASC LIMIT 1"
            )
            if (rs.hasNext) Some(rs.next().get("id").asString()) else None
          }
        Right(GraphStats(nodeCount, edgeCount, avgDegree, densestNodeId))
      }
    }

  /** Closes the driver if this store owns it. */
  override def close(): Unit =
    if (ownsDriver) driver.close()

  // ─────────────────────────────────────────────────────────────────────────
  // Native Cypher pass-through
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Executes an arbitrary read-only Cypher query and returns results as a list
   * of maps (column name → value).
   *
   * Strings, numbers, booleans, `null`, lists and maps convert to the matching `ujson.Value`. A node is an object with
   * its `id`, `label` and `properties` (for an `:LLM4S` node, as `getNode` reads them), and a relationship one with
   * its `type` and `properties`; any other driver value is its string form.
   */
  def executeRead(cypher: String, params: Map[String, AnyRef] = Map.empty): Result[List[Map[String, ujson.Value]]] =
    withSession { session =>
      val jParams = new java.util.HashMap[String, AnyRef](params.asJava)
      Right(session.executeRead { tx =>
        val rs  = tx.run(cypher, jParams)
        val buf = scala.collection.mutable.ArrayBuffer.empty[Map[String, ujson.Value]]
        while (rs.hasNext) {
          val rec = rs.next()
          buf += rec.keys().asScala.map(k => k -> Neo4jProperties.fromDriverValue(rec.get(k).asObject())).toMap
        }
        buf.toList
      })
    }

  /**
   * Executes an arbitrary write Cypher query.
   * Useful for schema migrations and bulk operations outside the GraphStore API.
   */
  def executeWrite(cypher: String, params: Map[String, AnyRef] = Map.empty): Result[Unit] =
    withSession { session =>
      val jParams = new java.util.HashMap[String, AnyRef](params.asJava)
      session.executeWriteWithoutResult { tx =>
        tx.run(cypher, jParams)
        ()
      }
      Right(())
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Private helpers
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Creates the uniqueness constraint on `llm4s_id` and the index on `llm4s_label`, if they do not exist. The
   * factories call it, and return its `Left`: without the constraint concurrent `MERGE`s can create duplicate nodes
   * and every id lookup scans the label (#2970).
   */
  private[neo4j] def initializeSchema(): Result[Unit] = {
    def run(cypher: String): Unit =
      Using.resource(driver.session(sessionConfig)) { session =>
        session.executeWriteWithoutResult { tx =>
          tx.run(cypher)
          ()
        }
      }
    // A user without schema rights may be refused even a CREATE ... IF NOT EXISTS that would do nothing, so a failed
    // create is still a Right when both already exist
    def present: Boolean =
      Try {
        Using.resource(driver.session(sessionConfig)) { session =>
          session.executeRead { tx =>
            tx.run("SHOW INDEXES YIELD name RETURN name").list().asScala.map(_.get("name").asString()).toSet
          }
        }
      }.toOption.exists(names => Neo4jGraphStore.SchemaNames.forall(names.contains))
    Try {
      run("CREATE CONSTRAINT llm4s_id_unique IF NOT EXISTS FOR (n:LLM4S) REQUIRE n.llm4s_id IS UNIQUE")
      run("CREATE INDEX llm4s_label_index IF NOT EXISTS FOR (n:LLM4S) ON (n.llm4s_label)")
    }.toEither.left.flatMap { e =>
      if (present) Right(())
      else
        Left(
          ProcessingError(
            "neo4j-store",
            "Could not create the LLM4S schema (constraint llm4s_id_unique on llm4s_id, index llm4s_label_index on " +
              s"llm4s_label): ${Redaction.scrubKnown(e.getMessage)}"
          )
        )
    }
  }

  private def sessionConfig: SessionConfig =
    SessionConfig.forDatabase(database)

  /** Wraps a session-scoped operation, converting exceptions to ProcessingError. */
  private def withSession[A](f: Session => Result[A]): Result[A] =
    Try {
      Using.resource(driver.session(sessionConfig))(f)
    }.toEither.left
      .map(e => ProcessingError("neo4j-store", s"Session error: ${Redaction.scrubKnown(e.getMessage)}"))
      .flatMap(identity)

  private def recordToNode(n: org.neo4j.driver.types.Node): Node = Neo4jProperties.toNode(n)

  private def fromNeo4jRelProps(rel: org.neo4j.driver.types.Relationship): Map[String, ujson.Value] =
    Neo4jProperties.decode(rel.asMap())

  /**
   * A relationship type upsertEdge can write: any non-empty string without a backslash. Cypher reads a `\u0060` in a
   * quoted name as a backtick before it parses the name, so a backslash could end the quoting; it is refused rather
   * than escaped.
   */
  private def validateRelType(relType: String): Result[String] =
    if (relType.isEmpty)
      Left(ProcessingError("neo4j-store", "A relationship type must not be empty: Neo4j cannot store one"))
    else if (relType.contains('\\'))
      Left(
        ProcessingError(
          "neo4j-store",
          s"Relationship type '$relType' contains a backslash, which Neo4jGraphStore refuses"
        )
      )
    else Right(relType)

  private def buildWhereClause(filter: GraphFilter): Result[(List[String], java.util.Map[String, AnyRef])] =
    GraphTraversal.checkPropertyFilter(filter).map { _ =>
      val clauses = scala.collection.mutable.ListBuffer.empty[String]
      val params  = new java.util.HashMap[String, AnyRef]()

      filter.nodeLabel.foreach { lbl =>
        clauses += "n.llm4s_label = $filterLabel"
        params.put("filterLabel", lbl)
      }

      filter.propertyKey.zip(filter.propertyValue).foreach { case (key, value) =>
        // As the in-memory store compares: the value's text when it is an array or stored as JSON, else toString of
        // the native value. CASE is evaluated lazily, so toString never sees a list. The key is a parameter, so any key
        // a node can be written with can be filtered on (#2970).
        clauses += "(CASE WHEN n[$filterTextKey] IS NOT NULL THEN n[$filterTextKey] = $filterPropValue " +
          "ELSE toString(n[$filterPropKey]) = $filterPropValue END)"
        params.put("filterPropKey", key)
        params.put("filterTextKey", Neo4jProperties.textKey(key))
        params.put("filterPropValue", value)
      }

      (clauses.toList, params)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Companion object
// ─────────────────────────────────────────────────────────────────────────────

object Neo4jGraphStore {

  /**
   * Configuration for Neo4jGraphStore.
   *
   * @param uri               Bolt URI, e.g. `bolt://localhost:7687`
   * @param user              Database user (default "neo4j")
   * @param password          Database password (default "")
   * @param database          Target database (default "neo4j")
   * @param maxPoolSize       Maximum number of connections in the driver pool (default 10)
   * @param connectionTimeout How long the driver waits to open a connection (default 30 seconds, the driver's own)
   */
  final case class Config(
    uri: String = "bolt://localhost:7687",
    user: String = "neo4j",
    password: Secret = Secret(""),
    database: String = "neo4j",
    maxPoolSize: Int = 10,
    connectionTimeout: FiniteDuration = 30.seconds
  )

  /**
   * Creates a Neo4jGraphStore from a [[Config]], and its schema (see [[Neo4jGraphStore]]). A failure to connect or to
   * create the schema is a `Left`, and the driver is closed.
   */
  def apply(config: Config): Result[Neo4jGraphStore] =
    Try {
      val driverConfig = org.neo4j.driver.Config
        .builder()
        .withMaxConnectionPoolSize(config.maxPoolSize)
        .withConnectionTimeout(
          math.max(1L, config.connectionTimeout.toMillis),
          java.util.concurrent.TimeUnit.MILLISECONDS
        )
        .build()
      GraphDatabase.driver(config.uri, AuthTokens.basic(config.user, config.password.reveal), driverConfig)
    }.toEither.left
      .map(e => ProcessingError("neo4j-store", s"Failed to create store: ${Redaction.scrubKnown(e.getMessage)}"))
      .flatMap { driver =>
        val store = new Neo4jGraphStore(driver, config.database, ownsDriver = true)
        store.initializeSchema() match {
          case Right(()) => Right(store)
          case Left(err) =>
            Try(driver.close())
            Left(err)
        }
      }

  /** Creates a Neo4jGraphStore from individual connection parameters. */
  def apply(
    uri: String,
    user: String = "neo4j",
    password: Secret = Secret(""),
    database: String = "neo4j"
  ): Result[Neo4jGraphStore] = apply(Config(uri, user, password, database))

  /** Connects to bolt://localhost:7687 with the given credentials. */
  def local(user: String = "neo4j", password: Secret = Secret("neo4j")): Result[Neo4jGraphStore] =
    apply(Config(user = user, password = password))

  /**
   * Creates a Neo4jGraphStore from an existing driver, and its schema (see [[Neo4jGraphStore]]); a failure to create
   * the schema is a `Left`. The driver will NOT be closed when the store is closed.
   *
   * @param driver   Existing Neo4j driver (caller manages lifecycle)
   * @param database Target database
   */
  def apply(driver: Driver, database: String): Result[Neo4jGraphStore] = {
    val store = new Neo4jGraphStore(driver, database, ownsDriver = false)
    store.initializeSchema().map(_ => store)
  }

  /** The constraint and index the factories create. */
  private val SchemaNames = Seq("llm4s_id_unique", "llm4s_label_index")

  /** A backtick-quoted Cypher name: any string, a backtick in it doubled (#2970). */
  private[neo4j] def quote(name: String): String = "`" + name.replace("`", "``") + "`"
}
