package org.llm4s.knowledgegraph.neo4j

import org.llm4s.error.ProcessingError
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.knowledgegraph.storage._
import org.llm4s.types.Result
import org.neo4j.driver._
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * Neo4j implementation of GraphStore.
 *
 * Maps GraphStore operations to Cypher queries via the official Neo4j Java driver.
 * All nodes carry the `:LLM4S` label for namespace isolation alongside any
 * user-defined label stored as the `llm4s_label` property.
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
) extends GraphStore {

  private val logger = LoggerFactory.getLogger(getClass)

  // Create uniqueness constraint once at construction time.
  initializeSchema()

  // ─────────────────────────────────────────────────────────────────────────
  // GraphStore implementation
  // ─────────────────────────────────────────────────────────────────────────

  override def upsertNode(node: Node): Result[Unit] =
    withSession { session =>
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("id", node.id)
      params.put("label", node.label)
      params.put("props", toNeo4jMap(node.properties))
      session.executeWriteWithoutResult { tx =>
        tx.run(
          """MERGE (n:LLM4S {llm4s_id: $id})
            |SET n = { llm4s_id: $id, llm4s_label: $label }
            |SET n += $props""".stripMargin,
          params
        )
        ()
      }
      Right(())
    }

  override def upsertEdge(edge: Edge): Result[Unit] =
    validateRelType(edge.relationship).flatMap { relType =>
      withSession { session =>
        val params = new java.util.HashMap[String, AnyRef]()
        params.put("src", edge.source)
        params.put("tgt", edge.target)
        params.put("props", toNeo4jMap(edge.properties))
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
                   |MERGE (a)-[r:$relType]->(b)
                   |SET r = $$props""".stripMargin,
                params
              )
              Right(())
            }
          }
        }.toEither.left
          .map(e => ProcessingError("neo4j-store", s"Failed to upsert edge: ${e.getMessage}"))
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
          "MATCH (a:LLM4S {llm4s_id: $id})-[r]-(b:LLM4S) WHERE b.llm4s_id <> $id RETURN r, b, type(r) AS relType, startNode(r).llm4s_id AS srcId, endNode(r).llm4s_id AS tgtId"
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
      // Uses one read query to avoid per-node session churn.
      // Follow-up: if GraphTraversal visibility is widened, shared traversal helpers can be reused across stores.
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("startId", startId)
      params.put("maxDepth", Integer.valueOf(config.maxDepth))
      params.put("excludedIds", config.visitedNodeIds.toList.asJava)

      val pattern = config.direction match {
        case Direction.Outgoing => "-[*0..$maxDepth]->"
        case Direction.Incoming => "<-[*0..$maxDepth]-"
        case Direction.Both     => "-[*0..$maxDepth]-"
      }

      Right(session.executeRead { tx =>
        val rs = tx.run(
          s"MATCH p=(start:LLM4S {llm4s_id: $$startId})$pattern(n:LLM4S) WHERE NOT n.llm4s_id IN $$excludedIds WITH n, min(length(p)) AS depth ORDER BY depth ASC, n.llm4s_id ASC RETURN n",
          params
        )

        val buf = scala.collection.mutable.ArrayBuffer.empty[Node]
        while (rs.hasNext)
          buf += recordToNode(rs.next().get("n").asNode())
        buf.toSeq
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
    validateRelType(relationship).flatMap { relType =>
      withSession { session =>
        val params = new java.util.HashMap[String, AnyRef]()
        params.put("src", source)
        params.put("tgt", target)
        session.executeWriteWithoutResult { tx =>
          tx.run(
            s"MATCH (a:LLM4S {llm4s_id: $$src})-[r:$relType]->(b:LLM4S {llm4s_id: $$tgt}) DELETE r",
            params
          )
          ()
        }
        Right(())
      }
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
      val nodeCount = session.executeRead { tx =>
        tx.run("MATCH (n:LLM4S) RETURN count(n) AS c").single().get("c").asLong()
      }

      val edgeCount = session.executeRead { tx =>
        tx.run("MATCH (a:LLM4S)-[r]->(b:LLM4S) RETURN count(r) AS c").single().get("c").asLong()
      }

      val avgDegree = if (nodeCount > 0) {
        session.executeRead { tx =>
          val rs = tx.run(
            "MATCH (n:LLM4S) OPTIONAL MATCH (n)-[r]-() RETURN n.llm4s_id AS id, count(r) AS deg"
          )
          var total = 0L
          var cnt   = 0L
          while (rs.hasNext) { total += rs.next().get("deg").asLong(); cnt += 1 }
          if (cnt > 0) total.toDouble / cnt else 0.0
        }
      } else 0.0

      val densestNodeId: Option[String] = if (nodeCount > 0) {
        session.executeRead { tx =>
          val rs = tx.run(
            "MATCH (n:LLM4S) OPTIONAL MATCH (n)-[r]-() RETURN n.llm4s_id AS id, count(r) AS deg ORDER BY deg DESC LIMIT 1"
          )
          if (rs.hasNext) Some(rs.next().get("id").asString()) else None
        }
      } else None

      Right(GraphStats(nodeCount, edgeCount, avgDegree, densestNodeId))
    }

  /** Closes the driver if this store owns it. */
  def close(): Unit =
    if (ownsDriver) driver.close()

  // ─────────────────────────────────────────────────────────────────────────
  // Native Cypher pass-through
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Executes an arbitrary read-only Cypher query and returns results as a list
   * of maps (column name → value as String).
   */
  def executeRead(cypher: String, params: Map[String, AnyRef] = Map.empty): Result[List[Map[String, String]]] =
    withSession { session =>
      val jParams = new java.util.HashMap[String, AnyRef](params.asJava)
      Right(session.executeRead { tx =>
        val rs  = tx.run(cypher, jParams)
        val buf = scala.collection.mutable.ArrayBuffer.empty[Map[String, String]]
        while (rs.hasNext) {
          val rec = rs.next()
          buf += rec.keys().asScala.map(k => k -> rec.get(k).toString).toMap
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

  private def initializeSchema(): Unit =
    Try {
      Using.resource(driver.session(sessionConfig)) { session =>
        session.executeWriteWithoutResult { tx =>
          tx.run(
            "CREATE CONSTRAINT llm4s_id_unique IF NOT EXISTS FOR (n:LLM4S) REQUIRE n.llm4s_id IS UNIQUE"
          )
          ()
        }
      }
    }.fold(
      e => logger.warn(s"Could not create LLM4S uniqueness constraint (may already exist): ${e.getMessage}"),
      _ => ()
    )

  private def sessionConfig: SessionConfig =
    SessionConfig.forDatabase(database)

  /** Wraps a session-scoped operation, converting exceptions to ProcessingError. */
  private def withSession[A](f: Session => Result[A]): Result[A] =
    Try {
      Using.resource(driver.session(sessionConfig))(f)
    }.toEither.left
      .map(e => ProcessingError("neo4j-store", s"Session error: ${e.getMessage}"))
      .flatMap(identity)

  private def recordToNode(n: org.neo4j.driver.types.Node): Node = {
    val id    = n.get("llm4s_id").asString()
    val label = n.get("llm4s_label").asString()
    val props = n
      .asMap()
      .asScala
      .toMap
      .removed("llm4s_id")
      .removed("llm4s_label")
      .map { case (k, v) => k -> fromNeo4jValue(v) }
    Node(id, label, props)
  }

  private def fromNeo4jRelProps(rel: org.neo4j.driver.types.Relationship): Map[String, ujson.Value] =
    rel.asMap().asScala.toMap.map { case (k, v) => k -> fromNeo4jValue(v) }

  private def fromNeo4jValue(v: AnyRef): ujson.Value = v match {
    case s: String            => ujson.Str(s)
    case b: java.lang.Boolean => ujson.Bool(b.booleanValue())
    case n: java.lang.Long    => ujson.Num(n.doubleValue())
    case n: java.lang.Integer => ujson.Num(n.doubleValue())
    case n: java.lang.Double  => ujson.Num(n.doubleValue())
    case n: java.lang.Float   => ujson.Num(n.doubleValue())
    case null                 => ujson.Null
    case other                => ujson.Str(other.toString)
  }

  private def toNeo4jMap(props: Map[String, ujson.Value]): java.util.Map[String, AnyRef] = {
    val m = new java.util.HashMap[String, AnyRef]()
    props.foreach {
      case (k, ujson.Str(s))  => m.put(k, s)
      case (k, ujson.Bool(b)) => m.put(k, java.lang.Boolean.valueOf(b))
      case (k, ujson.Num(n))  => m.put(k, java.lang.Double.valueOf(n))
      case (_, ujson.Null)    => ()
      case (k, other)         => m.put(k, other.toString)
    }
    m
  }

  private def validateRelType(relType: String): Result[String] =
    if (relType.matches("^[A-Za-z0-9_]+$")) Right(relType)
    else Left(ProcessingError("neo4j-store", s"Invalid relationship type '$relType'. Allowed pattern: [A-Za-z0-9_]+"))

  private def sanitizePropertyKey(key: String): Result[String] =
    if (key.matches("^[A-Za-z0-9_]+$")) Right(key)
    else Left(ProcessingError("neo4j-store", s"Invalid property key '$key'. Allowed pattern: [A-Za-z0-9_]+"))

  private def buildWhereClause(filter: GraphFilter): Result[(List[String], java.util.Map[String, AnyRef])] = {
    val clauses = scala.collection.mutable.ListBuffer.empty[String]
    val params  = new java.util.HashMap[String, AnyRef]()

    filter.nodeLabel.foreach { lbl =>
      clauses += "n.llm4s_label = $filterLabel"
      params.put("filterLabel", lbl)
    }

    val propResult = filter.propertyKey.zip(filter.propertyValue).foldLeft[Result[Unit]](Right(())) {
      case (Right(_), (key, value)) =>
        sanitizePropertyKey(key).map { safeKey =>
          clauses += s"toString(n.`$safeKey`) = $$filterPropValue"
          params.put("filterPropValue", value)
        }
      case (Left(err), _) => Left(err)
    }

    propResult.map(_ => (clauses.toList, params))
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Companion object
// ─────────────────────────────────────────────────────────────────────────────

object Neo4jGraphStore {

  /**
   * Configuration for Neo4jGraphStore.
   *
   * @param uri         Bolt URI, e.g. `bolt://localhost:7687`
   * @param user        Database user (default "neo4j")
   * @param password    Database password (default "")
   * @param database    Target database (default "neo4j")
   * @param maxPoolSize Maximum number of connections in the driver pool (default 10)
   */
  final case class Config(
    uri: String = "bolt://localhost:7687",
    user: String = "neo4j",
    password: String = "",
    database: String = "neo4j",
    maxPoolSize: Int = 10
  )

  /** Creates a Neo4jGraphStore from a [[Config]]. */
  def apply(config: Config): Result[Neo4jGraphStore] =
    Try {
      val driverConfig = org.neo4j.driver.Config
        .builder()
        .withMaxConnectionPoolSize(config.maxPoolSize)
        .build()
      val driver = GraphDatabase.driver(config.uri, AuthTokens.basic(config.user, config.password), driverConfig)
      new Neo4jGraphStore(driver, config.database, ownsDriver = true)
    }.toEither.left.map(e => ProcessingError("neo4j-store", s"Failed to create store: ${e.getMessage}"))

  /** Creates a Neo4jGraphStore from individual connection parameters. */
  def apply(
    uri: String,
    user: String = "neo4j",
    password: String = "",
    database: String = "neo4j"
  ): Result[Neo4jGraphStore] = apply(Config(uri, user, password, database))

  /** Connects to bolt://localhost:7687 with the given credentials. */
  def local(user: String = "neo4j", password: String = "neo4j"): Result[Neo4jGraphStore] =
    apply(Config(user = user, password = password))

  /**
   * Creates a Neo4jGraphStore from an existing driver.
   * The driver will NOT be closed when the store is closed.
   *
   * @param driver   Existing Neo4j driver (caller manages lifecycle)
   * @param database Target database
   */
  def apply(driver: Driver, database: String): Result[Neo4jGraphStore] =
    Try(new Neo4jGraphStore(driver, database, ownsDriver = false)).toEither.left.map(e =>
      ProcessingError("neo4j-store", s"Failed to create store: ${e.getMessage}")
    )
}
