package org.llm4s.eval.dataset

import cats.Id

import java.time.Instant

/**
 * Algebra for managing labelled evaluation datasets.
 *
 * The effect type `F[_]` is left unconstrained so that implementations can
 * range from the trivial `cats.Id` (synchronous, in-memory) to any async
 * effect (e.g. `Future`, `IO`) without requiring `cats-effect` as a core
 * dependency.
 *
 * All methods use `ujson.Value` for both input and output, making the store
 * format-agnostic; callers handle (de)serialisation at their own boundary.
 *
 * @tparam F effect wrapper (e.g. `cats.Id`, `Future`, `IO`)
 */
trait DatasetStore[F[_]] {

  /**
   * Creates a new dataset and returns its generated [[DatasetId]].
   *
   * @param name         human-readable name
   * @param description  purpose of the dataset
   * @param inputSchema  optional JSON Schema for input validation
   * @param outputSchema optional JSON Schema for output validation
   * @param tags         dataset-level labels
   */
  def create(
    name: String,
    description: String,
    inputSchema: Option[ujson.Value],
    outputSchema: Option[ujson.Value],
    tags: Set[String]
  ): F[DatasetId]

  /**
   * Appends a new [[Example]] to the given dataset and returns its [[ExampleId]].
   *
   * @param datasetId       target dataset
   * @param input           model input value
   * @param referenceOutput optional ground-truth output
   * @param tags            example-level labels for filtering
   * @param metadata        arbitrary string annotations
   */
  def addExample(
    datasetId: DatasetId,
    input: ujson.Value,
    referenceOutput: Option[ujson.Value],
    tags: Set[String],
    metadata: Map[String, String]
  ): F[ExampleId]

  /**
   * Retrieves examples from the dataset according to the given [[ExampleSelector]].
   *
   * @param datasetId target dataset
   * @param selector  `All`, `ByTags`, or `ByIds` — see [[ExampleSelector]]
   */
  def getExamples(
    datasetId: DatasetId,
    selector: ExampleSelector
  ): F[List[Example[ujson.Value, ujson.Value]]]

  /**
   * Returns the full [[Dataset]] record for the given ID, or `None` if it does not exist.
   * The returned dataset's `examples` field reflects the current state of all examples.
   */
  def getDataset(datasetId: DatasetId): F[Option[Dataset[ujson.Value, ujson.Value]]]

  /**
   * Creates an immutable snapshot of the dataset's current examples.
   *
   * The snapshot is unaffected by subsequent calls to [[addExample]] or [[delete]].
   *
   * @return the [[SnapshotId]] of the newly created snapshot
   */
  def createSnapshot(datasetId: DatasetId): F[SnapshotId]

  /**
   * Retrieves a previously created [[DatasetSnapshot]], or `None` if the ID is unknown
   * (e.g. because the originating dataset was deleted).
   */
  def getSnapshot(snapshotId: SnapshotId): F[Option[DatasetSnapshot[ujson.Value, ujson.Value]]]

  /**
   * Imports examples from a JSONL stream into the dataset.
   *
   * Each line is decoded with [[JsonlCodec.decode]]; lines that fail to parse
   * are silently skipped.
   *
   * @param datasetId target dataset
   * @param lines     iterator of raw JSONL strings (one JSON object per element)
   * @return `(imported, skipped)` counts
   */
  def importJsonl(datasetId: DatasetId, lines: Iterator[String]): F[(Int, Int)]

  /**
   * Exports all examples in the dataset as a JSONL iterator.
   *
   * Each element is a compact JSON string produced by [[JsonlCodec.encode]].
   * The iterator reflects the dataset state at the moment this method is called;
   * concurrent mutations are not reflected in the returned iterator.
   */
  def exportJsonl(datasetId: DatasetId): F[Iterator[String]]

  /**
   * Deletes the dataset along with all its examples and snapshots.
   *
   * @return `true` if the dataset existed and was removed, `false` if it was not found
   */
  def delete(datasetId: DatasetId): F[Boolean]

  /** Returns all datasets currently held in the store (examples field included). */
  def listDatasets(): F[List[Dataset[ujson.Value, ujson.Value]]]
}

/**
 * A synchronous, in-memory implementation of [[DatasetStore]] backed by mutable Scala maps.
 *
 * Intended for unit tests and local experimentation. All public methods are
 * `synchronized` on the store instance to provide basic thread safety within
 * a single JVM.
 *
 * Obtain an instance via the companion object factory: {{{ val store = InMemoryDatasetStore() }}}
 */
class InMemoryDatasetStore extends DatasetStore[Id] {

  private var datasets: Map[DatasetId, Dataset[ujson.Value, ujson.Value]]           = Map.empty
  private var examples: Map[DatasetId, List[Example[ujson.Value, ujson.Value]]]     = Map.empty
  private var snapshots: Map[SnapshotId, DatasetSnapshot[ujson.Value, ujson.Value]] = Map.empty

  override def create(
    name: String,
    description: String,
    inputSchema: Option[ujson.Value] = None,
    outputSchema: Option[ujson.Value] = None,
    tags: Set[String] = Set.empty
  ): DatasetId = synchronized {
    val id = DatasetId.generate()
    val dataset = Dataset[ujson.Value, ujson.Value](
      id = id,
      name = name,
      description = description,
      inputSchema = inputSchema,
      outputSchema = outputSchema,
      examples = List.empty,
      createdAt = Instant.now(),
      tags = tags
    )
    datasets = datasets + (id -> dataset)
    examples = examples + (id -> List.empty)
    id
  }

  override def addExample(
    datasetId: DatasetId,
    input: ujson.Value,
    referenceOutput: Option[ujson.Value] = None,
    tags: Set[String] = Set.empty,
    metadata: Map[String, String] = Map.empty
  ): ExampleId = synchronized {
    require(datasets.contains(datasetId), s"Dataset ${datasetId.value} does not exist")
    val id = ExampleId.generate()
    val example = Example[ujson.Value, ujson.Value](
      id = id,
      input = input,
      referenceOutput = referenceOutput,
      tags = tags,
      metadata = metadata
    )
    val existing = examples.getOrElse(datasetId, List.empty)
    examples = examples + (datasetId -> (existing :+ example))
    id
  }

  override def getExamples(
    datasetId: DatasetId,
    selector: ExampleSelector
  ): List[Example[ujson.Value, ujson.Value]] = synchronized {
    val allExamples = examples.getOrElse(datasetId, List.empty)
    selector match {
      case ExampleSelector.All => allExamples
      case ExampleSelector.ByTags(filterTags) =>
        if (filterTags.isEmpty) List.empty
        else allExamples.filter(e => e.tags.intersect(filterTags).nonEmpty)
      case ExampleSelector.ByIds(ids) =>
        allExamples.filter(e => ids.contains(e.id))
    }
  }

  override def getDataset(datasetId: DatasetId): Option[Dataset[ujson.Value, ujson.Value]] = synchronized {
    datasets.get(datasetId).map(ds => ds.copy(examples = examples.getOrElse(datasetId, List.empty)))
  }

  override def createSnapshot(datasetId: DatasetId): SnapshotId = synchronized {
    require(datasets.contains(datasetId), s"Dataset ${datasetId.value} does not exist")
    val snapshotId      = SnapshotId.generate()
    val currentExamples = examples.getOrElse(datasetId, List.empty)
    val snapshot = DatasetSnapshot[ujson.Value, ujson.Value](
      snapshotId = snapshotId,
      datasetId = datasetId,
      examples = currentExamples,
      createdAt = Instant.now()
    )
    snapshots = snapshots + (snapshotId -> snapshot)
    snapshotId
  }

  override def getSnapshot(snapshotId: SnapshotId): Option[DatasetSnapshot[ujson.Value, ujson.Value]] = synchronized {
    snapshots.get(snapshotId)
  }

  override def importJsonl(datasetId: DatasetId, lines: Iterator[String]): (Int, Int) = synchronized {
    lines.foldLeft((0, 0)) { case ((imported, skipped), line) =>
      JsonlCodec.decode(line) match {
        case Some(example) =>
          addExample(datasetId, example.input, example.referenceOutput, example.tags, example.metadata)
          (imported + 1, skipped)
        case None =>
          (imported, skipped + 1)
      }
    }
  }

  override def exportJsonl(datasetId: DatasetId): Iterator[String] = synchronized {
    // Materialize as a List inside the lock so the returned Iterator is over an
    // immutable snapshot; concurrent mutations to this store do not affect it.
    getExamples(datasetId, ExampleSelector.All).map(JsonlCodec.encode).iterator
  }

  override def delete(datasetId: DatasetId): Boolean = synchronized {
    if (datasets.contains(datasetId)) {
      datasets = datasets - datasetId
      examples = examples - datasetId
      snapshots = snapshots.filterNot { case (_, snap) => snap.datasetId == datasetId }
      true
    } else false
  }

  override def listDatasets(): List[Dataset[ujson.Value, ujson.Value]] = synchronized {
    datasets.values.map(ds => ds.copy(examples = examples.getOrElse(ds.id, List.empty))).toList
  }
}

object InMemoryDatasetStore {
  def apply(): InMemoryDatasetStore = new InMemoryDatasetStore()
}
