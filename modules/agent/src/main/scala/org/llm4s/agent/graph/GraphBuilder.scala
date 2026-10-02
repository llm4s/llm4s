package org.llm4s.agent.graph

import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.mutable

/**
 * Builds a typed graph. The builder issues every handle - nodes, joins - and validates the whole
 * graph in [[compile]], reporting every problem at once.
 *
 * Nodes that route to each other in a cycle are declared first and implemented afterwards:
 * {{{
 * val b      = GraphBuilder("counter", "v1")
 * val count  = StateKey.replace[Int]("count", 0)
 * val tick   = b.declare[Unit]("tick")
 * b.implement(tick, writes = Set(count)) { (_, state, _) =>
 *   NodeResult.fromResult(state.get(count).map { n =>
 *     val next = Command.empty.update(count, n + 1)
 *     if n + 1 < 3 then next.goto(tick) else next
 *   })
 * }
 * val graph = b.compile(tick)(_.get(count))
 * }}}
 *
 * The builder is mutable and meant to be used from one thread while the graph is assembled; the
 * [[CompiledGraph]] it produces is immutable.
 */
final class GraphBuilder private (val id: String, val version: String):
  private val owner        = GraphOwner(id)
  private val declared     = mutable.LinkedHashMap.empty[NodeId, NodeRef[?]]
  private val implemented  = mutable.LinkedHashMap.empty[NodeId, NodeDef[?]]
  private val edges        = mutable.ArrayBuffer.empty[(NodeId, NodeId)]
  private val staticJoins  = mutable.ArrayBuffer.empty[StaticJoin]
  private val dynamicJoins = mutable.ArrayBuffer.empty[DynamicJoin]
  private val readKeys     = mutable.ArrayBuffer.empty[StateKey[?, ?]]
  private val problems     = mutable.ArrayBuffer.empty[String]

  /** Issues a handle for a node consuming `I`; implement it before compiling. */
  def declare[I](nodeId: String)(using codec: ReadWriter[I]): NodeRef[I] =
    if nodeId.isEmpty then problems += "a node id is empty"
    if declared.contains(NodeId(nodeId)) then problems += s"node '$nodeId' is declared twice"
    val ref = new NodeRef[I](NodeId(nodeId), owner, codec)
    declared.update(ref.id, ref)
    ref

  /** Gives a declared node its behaviour and the state keys it may update or remove. */
  def implement[I](ref: NodeRef[I], writes: Set[StateKey[?, ?]] = Set.empty)(node: GraphNode[I]): Unit =
    if !owns(ref) then problems += s"node '${ref.id.value}' was issued by another builder"
    else if implemented.contains(ref.id) then problems += s"node '${ref.id.value}' is implemented twice"
    else implemented.update(ref.id, NodeDef(ref, writes, node))

  /** Declares and implements a node in one step. */
  def node[I](nodeId: String, writes: Set[StateKey[?, ?]] = Set.empty)(node: GraphNode[I])(using
    codec: ReadWriter[I]
  ): NodeRef[I] =
    val ref = declare[I](nodeId)
    implement(ref, writes)(node)
    ref

  /** Registers a key that nodes read but none writes, such as one seeded by a restored snapshot. */
  def stateKey(key: StateKey[?, ?]): Unit = readKeys += key

  /**
   * A static edge: every completed task of `from` also schedules `to`, before the routes in its
   * command. Edges from one node are scheduled in declaration order.
   */
  def edge(from: NodeRef[?], to: NodeRef[Unit]): Unit =
    if !owns(from) || !owns(to) then
      problems += s"edge ${from.id.value} -> ${to.id.value} uses a node from another builder"
    edges += (from.id -> to.id)

  /** A barrier that runs `target` after every node in `sources` has completed; see [[StaticJoin]]. */
  def staticJoin(joinId: String, sources: Set[NodeRef[?]], target: NodeRef[Unit]): StaticJoin =
    checkJoinId(joinId)
    if sources.isEmpty then problems += s"static join '$joinId' has no sources"
    if !sources.forall(owns) || !owns(target) then problems += s"static join '$joinId' uses a node from another builder"
    val join = new StaticJoin(JoinId(joinId), sources.map(_.id), target, owner)
    staticJoins += join
    join

  /** A barrier released when every child of a fan-out has completed; see [[DynamicJoin]]. */
  def dynamicJoin(joinId: String, target: NodeRef[Unit]): DynamicJoin =
    checkJoinId(joinId)
    if !owns(target) then problems += s"dynamic join '$joinId' uses a node from another builder"
    val join = new DynamicJoin(JoinId(joinId), target, owner)
    dynamicJoins += join
    join

  /**
   * Validates the graph and compiles it. `output` projects the final committed state when the
   * run goes quiescent.
   */
  def compile[I, O](entry: NodeRef[I], maxSupersteps: Int = 1000)(
    output: ThreadState => Result[O]
  ): Result[CompiledGraph[I, O]] =
    val keys     = (readKeys ++ implemented.values.flatMap(_.writes)).distinct.toVector
    val keyIndex = keys.groupBy(_.id)
    val found    = Vector.newBuilder[String]
    found ++= problems
    if id.isEmpty then found += "the graph id is empty"
    if maxSupersteps <= 0 then found += s"maxSupersteps must be positive, was $maxSupersteps"
    if !owns(entry) then found += s"entry node '${entry.id.value}' was issued by another builder"
    declared.keys.filterNot(implemented.contains).foreach(n => found += s"node '${n.value}' is never implemented")
    edges.foreach { (from, to) =>
      if !declared.contains(from) || !declared.contains(to) then
        found += s"edge ${from.value} -> ${to.value} uses an undeclared node"
    }
    keys.filter(_.id.value.isEmpty).foreach(_ => found += "a state key id is empty")
    keyIndex.collect { case (keyId, shared) if shared.size > 1 => keyId }.toVector.sortBy(_.value).foreach { keyId =>
      found += s"state key id '${keyId.value}' is used by ${keyIndex(keyId).size} different keys"
    }
    val all = found.result()
    if all.nonEmpty then Left(GraphError.InvalidGraph(id, all.toList))
    else
      Right(
        new CompiledGraph[I, O](
          id = id,
          version = version,
          fingerprint = fingerprint(entry, keys),
          owner = owner,
          entry = entry,
          nodes = implemented.toMap,
          edges = edges.toVector.groupMap(_._1)(_._2),
          staticJoins = staticJoins.toVector,
          dynamicJoins = dynamicJoins.map(j => j.id -> j).toMap,
          keys = keys.map(k => k.id -> k).toMap,
          output = output,
          maxSupersteps = maxSupersteps,
          executor = TaskExecutor.sequential
        )
      )

  private def owns(ref: NodeRef[?]): Boolean = ref.owner eq owner

  private def checkJoinId(joinId: String): Unit =
    if joinId.isEmpty then problems += "a join id is empty"
    if (staticJoins.map(_.id) ++ dynamicJoins.map(_.id)).contains(JoinId(joinId)) then
      problems += s"join '$joinId' is declared twice"

  /** A digest of the graph's structure, so a snapshot is not restored into a reshaped graph. */
  private def fingerprint(entry: NodeRef[?], keys: Vector[StateKey[?, ?]]): String =
    val structure = Vector(s"graph:$id:$version", s"entry:${entry.id.value}") ++
      declared.keys.map(n => s"node:${n.value}").toVector.sorted ++
      edges.map((from, to) => s"edge:${from.value}->${to.value}") ++
      staticJoins.map(j =>
        s"static:${j.id.value}:${j.sources.map(_.value).toVector.sorted.mkString(",")}->${j.target.id.value}"
      ) ++
      dynamicJoins.map(j => s"dynamic:${j.id.value}->${j.target.id.value}") ++
      keys.map(k => s"key:${k.id.value}").sorted
    MessageDigest
      .getInstance("SHA-256")
      .digest(structure.mkString("\n").getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString

object GraphBuilder:
  /** `version` names the graph's behaviour; bump it when node logic changes incompatibly. */
  def apply(id: String, version: String): GraphBuilder = new GraphBuilder(id, version)

/** A node's handle, write set and behaviour, with its input erased at the scheduler boundary. */
final private[graph] case class NodeDef[I](ref: NodeRef[I], writes: Set[StateKey[?, ?]], behaviour: GraphNode[I]):
  def run(input: Any, state: ThreadState, context: NodeContext): NodeResult =
    behaviour.run(input.asInstanceOf[I], state, context)
  def encode(input: Any): ujson.Value = upickle.default.writeJs(input.asInstanceOf[I])(using ref.codec)
  def decode(json: ujson.Value): Any  = upickle.default.read[I](json)(using ref.codec)

/** Runs a superstep's tasks; results come back in task order however the tasks interleave. */
private[graph] trait TaskExecutor:
  def runAll[R](tasks: Vector[() => R]): Vector[R]

private[graph] object TaskExecutor:
  val sequential: TaskExecutor = new TaskExecutor:
    def runAll[R](tasks: Vector[() => R]): Vector[R] = tasks.map(_())
