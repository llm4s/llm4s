package org.llm4s.eval.dataset

import java.time.Instant
import java.util.UUID

/** Opaque identifier for a [[Dataset]]. Wraps a UUID string. */
final case class DatasetId(value: String) extends AnyVal {
  override def toString: String = value
}

object DatasetId {

  /** Generates a new, globally-unique [[DatasetId]] backed by a random UUID. */
  def generate(): DatasetId = DatasetId(UUID.randomUUID().toString)
}

/** Opaque identifier for an [[Example]] within a dataset. Wraps a UUID string. */
final case class ExampleId(value: String) extends AnyVal {
  override def toString: String = value
}

object ExampleId {

  /** Generates a new, globally-unique [[ExampleId]] backed by a random UUID. */
  def generate(): ExampleId = ExampleId(UUID.randomUUID().toString)
}

/** Opaque identifier for a [[DatasetSnapshot]]. Wraps a UUID string. */
final case class SnapshotId(value: String) extends AnyVal {
  override def toString: String = value
}

object SnapshotId {

  /** Generates a new, globally-unique [[SnapshotId]] backed by a random UUID. */
  def generate(): SnapshotId = SnapshotId(UUID.randomUUID().toString)
}

/**
 * A single labelled example in a dataset.
 *
 * @param id              unique identifier for this example
 * @param input           the model input (generic; often `ujson.Value`)
 * @param referenceOutput optional ground-truth output to compare model responses against
 * @param tags            free-form labels for filtering (e.g. `"qa"`, `"rag"`)
 * @param metadata        arbitrary string key-value annotations
 * @tparam I input type
 * @tparam O output type
 */
final case class Example[I, O](
  id: ExampleId,
  input: I,
  referenceOutput: Option[O],
  tags: Set[String] = Set.empty,
  metadata: Map[String, String] = Map.empty
)

/**
 * A named, versioned collection of [[Example]] instances.
 *
 * @param id           unique identifier
 * @param name         human-readable name
 * @param description  purpose and content of the dataset
 * @param inputSchema  optional JSON Schema describing the expected `input` structure
 * @param outputSchema optional JSON Schema describing the expected `referenceOutput` structure
 * @param examples     ordered list of examples
 * @param createdAt    creation timestamp
 * @param tags         dataset-level labels for discovery
 * @tparam I input type
 * @tparam O output type
 */
final case class Dataset[I, O](
  id: DatasetId,
  name: String,
  description: String,
  inputSchema: Option[ujson.Value] = None,
  outputSchema: Option[ujson.Value] = None,
  examples: List[Example[I, O]] = List.empty,
  createdAt: Instant,
  tags: Set[String] = Set.empty
)

/**
 * An immutable point-in-time copy of the examples in a [[Dataset]].
 *
 * Snapshots are created by [[DatasetStore.createSnapshot]] and are unaffected
 * by subsequent mutations to the originating dataset.
 *
 * @param snapshotId unique identifier for this snapshot
 * @param datasetId  the dataset from which the snapshot was taken
 * @param examples   the frozen example list at snapshot time
 * @param createdAt  snapshot creation timestamp
 * @tparam I input type
 * @tparam O output type
 */
final case class DatasetSnapshot[I, O](
  snapshotId: SnapshotId,
  datasetId: DatasetId,
  examples: List[Example[I, O]],
  createdAt: Instant
)

/**
 * Selects a subset of examples from a dataset.
 *
 * Pattern-match exhaustively over the three cases to handle all variants:
 * {{{
 *   selector match {
 *     case ExampleSelector.All        => // return everything
 *     case ExampleSelector.ByTags(ts) => // filter by tag intersection
 *     case ExampleSelector.ByIds(ids) => // filter by exact ID membership
 *   }
 * }}}
 */
sealed trait ExampleSelector

object ExampleSelector {

  /** Returns all examples in the dataset without filtering. */
  case object All extends ExampleSelector

  /**
   * Returns examples that have at least one tag in common with `tags`.
   * An empty `tags` set always produces an empty result.
   */
  final case class ByTags(tags: Set[String]) extends ExampleSelector

  /** Returns only the examples whose [[ExampleId]] appears in `ids`. */
  final case class ByIds(ids: Set[ExampleId]) extends ExampleSelector
}
