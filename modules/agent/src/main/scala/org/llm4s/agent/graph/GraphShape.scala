package org.llm4s.agent.graph

import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.Try

/**
 * A compiled graph's declared structure, as data: what a [[GraphSnapshot]] records of the graph that wrote it, so a
 * restore can tell what changed since. `nodes`, `resumes`, `keys` and each static join's `sources` are sorted;
 * `edges` holds each node's static successors in declaration order, and only nodes that have some; `staticJoins` is
 * in declaration order, which is the order joins released in one superstep schedule their targets.
 * [[CompiledGraph.restore]] says which differences keep a snapshot restorable.
 */
final case class GraphShape(
  entry: String,
  nodes: Vector[String],
  edges: Map[String, Vector[String]],
  staticJoins: Vector[GraphShape.StaticJoin],
  dynamicJoins: Map[String, String],
  resumes: Vector[String],
  keys: Vector[String]
) derives ReadWriter

object GraphShape:
  /** A static join: its target runs once every one of `sources` (sorted) has completed. */
  final case class StaticJoin(id: String, sources: Vector[String], target: String) derives ReadWriter

/**
 * The rules for restoring a snapshot into a graph whose shape may have changed since it was written (design §4.29).
 * A snapshot is in flight when it holds a pending task, a parked continuation or a partially arrived join, and at
 * rest otherwise.
 */
private[graph] object GraphEvolution:

  /** How a restore judges a snapshot: as at rest, in flight, or by what it holds ([[inFlight]]). */
  enum Judged:
    case AtRest, InFlight, ByContent

  /** At most this many keys are searched when matching a snapshot written before shapes were recorded. */
  val MaxLegacyAddedKeys: Int = 16

  /** Whether `snapshot` holds unfinished work. */
  def inFlight(snapshot: GraphSnapshot): Boolean =
    snapshot.frontier.nonEmpty || snapshot.parked.nonEmpty || snapshot.staticJoins.nonEmpty ||
      snapshot.dynamicJoins.nonEmpty

  /**
   * The problems with restoring `snapshot`, which carries `stored`, into a graph of shape `current` at `version`,
   * judged as `judged` says. Key removals are judged by the stored values themselves
   * ([[CompiledGraph.restore]]), and pending writes when they are rebound.
   */
  def problems(
    stored: GraphShape,
    current: GraphShape,
    snapshot: GraphSnapshot,
    version: String,
    judged: Judged
  ): Vector[String] =
    val removed = stored.nodes.filterNot(current.nodes.toSet).map(n => s"node '$n' was removed from this graph")
    val running = judged match
      case Judged.AtRest    => false
      case Judged.InFlight  => true
      case Judged.ByContent => inFlight(snapshot)
    if !running then removed
    else
      val storedNodes = stored.nodes.toSet
      val versioned = Option.when(snapshot.graphVersion != version)(
        s"snapshot is of version '${snapshot.graphVersion}', this graph is version '$version'; " +
          "a run in flight restores only into its own version"
      )
      val edges = stored.nodes.filter(current.nodes.toSet).flatMap { n =>
        val before = stored.edges.getOrElse(n, Vector.empty)
        val after  = current.edges.getOrElse(n, Vector.empty)
        Option.when(before != after)(
          s"the out-edges of node '$n' changed: ${list(before)}, then ${list(after)}"
        )
      }
      val storedJoins                             = stored.staticJoins.map(j => j.id -> j).toMap
      val currentJoins                            = current.staticJoins.map(j => j.id -> j).toMap
      def overStored(join: GraphShape.StaticJoin) = join.sources.exists(storedNodes)
      val statics = (storedJoins.keySet ++ currentJoins.keySet).toVector.sorted.flatMap { id =>
        (storedJoins.get(id), currentJoins.get(id)) match
          case (Some(before), Some(after)) if before != after =>
            Some(s"static join '$id' changed: ${join(before)}, then ${join(after)}")
          case (Some(_), None)                          => Some(s"static join '$id', over stored nodes, was removed")
          case (None, Some(after)) if overStored(after) => Some(s"static join '$id', over stored nodes, was added")
          case _                                        => None
      }
      // joins released in one superstep schedule their targets in declaration order: task ids and commit order
      val kept    = stored.staticJoins.map(_.id).filter(currentJoins.contains)
      val keptNow = current.staticJoins.map(_.id).filter(storedJoins.contains)
      val reordered = Option.when(kept != keptNow)(
        s"the static joins over stored nodes changed their order: ${kept.mkString(", ")}, then ${keptNow.mkString(", ")}"
      )
      val dynamics = stored.dynamicJoins.toVector.sortBy(_._1).flatMap { (id, target) =>
        current.dynamicJoins.get(id) match
          case None                       => Some(s"dynamic join '$id' was removed from this graph")
          case Some(now) if now != target => Some(s"dynamic join '$id' changed its target: $target, then $now")
          case Some(_)                    => None
      }
      versioned.toVector ++ removed ++ edges ++ statics ++ reordered ++ dynamics

  /**
   * The problems with restoring `snapshot`, written before snapshots recorded a shape, into a graph whose
   * fingerprint is computed by `fingerprint`: it restores only if its version is `version` and its fingerprint is
   * this graph's over its keys less some it holds no value for - the graph only gained keys.
   */
  def legacyProblems(snapshot: GraphSnapshot, version: String, fingerprint: LegacyFingerprint): Vector[String] =
    val versioned = Option.when(snapshot.graphVersion != version)(
      s"snapshot is of version '${snapshot.graphVersion}', this graph is version '$version'; " +
        "a snapshot without a shape restores only into its own version"
    )
    val shaped = Option.unless(fingerprint.matches(snapshot.fingerprint, snapshot.state.keySet))(
      "the snapshot was written before graph shapes were recorded, " +
        "and this graph differs from its graph by more than added keys"
    )
    versioned.toVector ++ shaped

  private def list(nodes: Vector[String]): String = if nodes.isEmpty then "none" else nodes.mkString(", ")

  private def join(j: GraphShape.StaticJoin): String = s"${j.sources.mkString(", ")} -> ${j.target}"

/**
 * A graph's structural fingerprint as snapshots have always recorded it: the hex SHA-256 of its structure lines,
 * then one `key:<id>` line per key, sorted, joined by newlines. `matches` also accepts the fingerprint of the same
 * structure over fewer keys, dropping only keys outside `held`.
 */
final private[graph] class LegacyFingerprint(
  structure: Vector[String],
  keys: Vector[String],
  newDigest: () => MessageDigest = () => MessageDigest.getInstance("SHA-256")
):
  private val sortedKeys     = keys.sorted
  private val structureBytes = structure.mkString("\n").getBytes(StandardCharsets.UTF_8)

  private def overStructure(): MessageDigest =
    val digest = newDigest()
    digest.update(structureBytes)
    digest

  private lazy val prefix: MessageDigest = overStructure()

  /**
   * A digest that has read the structure lines: a clone of `prefix`, so the search hashes only key lines, or a fresh
   * one where the provider cannot clone (`CloneNotSupportedException`, as some FIPS providers throw).
   */
  private def started(): MessageDigest =
    Try(prefix.clone().asInstanceOf[MessageDigest]).getOrElse(overStructure())

  private def over(kept: Vector[String]): String =
    val digest = started()
    kept.foreach(k => digest.update(s"\nkey:$k".getBytes(StandardCharsets.UTF_8)))
    digest.digest().map(b => f"$b%02x").mkString

  val value: String = over(sortedKeys)

  def matches(stored: String, held: Set[String]): Boolean =
    stored == value || {
      val optional = sortedKeys.filterNot(held)
      optional.size <= GraphEvolution.MaxLegacyAddedKeys && optional.nonEmpty &&
      (1 until (1 << optional.size)).exists { dropped =>
        val gone = optional.indices.collect { case i if (dropped & (1 << i)) != 0 => optional(i) }.toSet
        over(sortedKeys.filterNot(gone)) == stored
      }
    }
