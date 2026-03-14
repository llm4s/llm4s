package org.llm4s.rag.benchmark

import org.llm4s.rag.evaluation.{ EvalSample, EvaluationError, TestDataset }
import org.llm4s.types.{ Result, TryOps }

import java.nio.file.{ Files, Path, Paths }
import scala.util.Try

/**
 * Manages loading and processing of benchmark datasets.
 *
 * Supports multiple dataset formats:
 * - RAGBench (Hugging Face JSONL format)
 * - MultiHop-RAG (JSON format)
 * - Custom JSON format (TestDataset format)
 *
 * @example
 * {{{
 * val manager = DatasetManager()
 *
 * // Load RAGBench dataset
 * val dataset = manager.loadRAGBench("data/datasets/ragbench/test.jsonl")
 *
 * // Load with subset
 * val subset = manager.loadRAGBench("data/datasets/ragbench/test.jsonl", Some(100))
 * }}}
 */
class DatasetManager {

  /**
   * Load a dataset from a JSON/JSONL file, auto-detecting format.
   *
   * @param path Path to the dataset file
   * @param subsetSize Optional limit on samples to load
   * @param seed Random seed for subset selection
   * @return Loaded dataset or error
   */
  def load(
    path: String,
    subsetSize: Option[Int] = None,
    seed: Long = 42L
  ): Result[TestDataset] = {
    val p = Paths.get(path)

    if (!Files.exists(p)) {
      return Left(EvaluationError(s"Dataset file not found: $path"))
    }

    val format = detectFormat(p)

    val result = format match {
      case DatasetFormat.RAGBench    => loadRAGBench(path)
      case DatasetFormat.MultiHopRAG => loadMultiHopRAG(path)
      case DatasetFormat.TestDataset => TestDataset.fromJsonFile(path)
      case DatasetFormat.Unknown     => Left(EvaluationError(s"Unknown dataset format: $path"))
    }

    // Apply subset if requested
    result.map { dataset =>
      subsetSize match {
        case Some(n) if n < dataset.samples.size => dataset.sample(n, seed)
        case _                                   => dataset
      }
    }
  }

  /**
   * Load RAGBench dataset (Hugging Face JSONL format).
   *
   * RAGBench format:
   * {
   *   "question": "...",
   *   "response": "...",
   *   "documents": ["...", "..."],
   *   "answer": "..."  // ground truth
   * }
   *
   * @param path Path to JSONL file
   * @return TestDataset or error
   */
  def loadRAGBench(path: String): Result[TestDataset] =
    Try {
      val lines = scala.io.Source.fromFile(path).getLines().toSeq
      val samples = lines.zipWithIndex.flatMap { case (line, idx) =>
        parseRAGBenchLine(line, idx)
      }

      TestDataset(
        name = s"ragbench-${Paths.get(path).getFileName}",
        samples = samples,
        metadata = Map(
          "source"     -> "RAGBench",
          "sourcePath" -> path,
          "format"     -> "ragbench"
        )
      )
    }.toResult.left.map(e => EvaluationError(s"Failed to load RAGBench: ${e.message}"))

  private def parseRAGBenchLine(line: String, index: Int): Option[EvalSample] =
    Try {
      val json = ujson.read(line)
      val obj  = json.obj

      val question = obj.get("question").map(_.str).getOrElse("")
      val answer   = obj.get("response").map(_.str).getOrElse("")
      val contexts = obj.get("documents").map(_.arr.map(_.str).toSeq).getOrElse(Seq.empty)
      val groundTruth = obj
        .get("answer")
        .orElse(obj.get("ground_truth"))
        .map(_.str)

      if (question.nonEmpty && contexts.nonEmpty) {
        Some(
          EvalSample(
            question = question,
            answer = answer,
            contexts = contexts,
            groundTruth = groundTruth,
            metadata = Map("sourceIndex" -> index.toString, "format" -> "ragbench")
          )
        )
      } else {
        None
      }
    }.toOption.flatten

  /**
   * Load MultiHop-RAG dataset.
   *
   * MultiHop-RAG format:
   * {
   *   "data": [
   *     {
   *       "question": "...",
   *       "answer": "...",
   *       "supporting_facts": [...]
   *     }
   *   ]
   * }
   *
   * @param path Path to JSON file
   * @return TestDataset or error
   */
  def loadMultiHopRAG(path: String): Result[TestDataset] =
    Try {
      val content = new String(Files.readAllBytes(Paths.get(path)))
      val json    = ujson.read(content)

      val dataArray = json.obj.get("data").map(_.arr).getOrElse(json.arr)

      val samples = dataArray.zipWithIndex.flatMap { case (item, idx) =>
        parseMultiHopRAGItem(item, idx)
      }.toSeq

      TestDataset(
        name = s"multihop-rag-${Paths.get(path).getFileName}",
        samples = samples,
        metadata = Map(
          "source"     -> "MultiHop-RAG",
          "sourcePath" -> path,
          "format"     -> "multihop-rag"
        )
      )
    }.toResult.left.map(e => EvaluationError(s"Failed to load MultiHop-RAG: ${e.message}"))

  private def parseMultiHopRAGItem(json: ujson.Value, index: Int): Option[EvalSample] =
    Try {
      val obj = json.obj

      val question = obj.get("question").map(_.str).getOrElse("")
      val answer   = obj.get("answer").map(_.str).getOrElse("")

      // Supporting facts become contexts
      val contexts = obj
        .get("supporting_facts")
        .map(_.arr.map(_.str).toSeq)
        .orElse(obj.get("context").map(c => Seq(c.str)))
        .getOrElse(Seq.empty)

      if (question.nonEmpty) {
        Some(
          EvalSample(
            question = question,
            answer = answer,
            contexts = contexts,
            groundTruth = Some(answer), // Answer is the ground truth
            metadata = Map("sourceIndex" -> index.toString, "format" -> "multihop-rag")
          )
        )
      } else {
        None
      }
    }.toOption.flatten

  /**
   * Load documents from a directory for indexing.
   *
   * @param path Directory path
   * @param extensions File extensions to include (default: .txt, .md)
   * @return Sequence of (filename, content) pairs or error
   */
  def loadDocumentsFromDirectory(
    path: String,
    extensions: Set[String] = Set(".txt", ".md")
  ): Result[Seq[(String, String)]] =
    Try {
      val dir = Paths.get(path)
      if (!Files.isDirectory(dir)) {
        Left(EvaluationError(s"Not a directory: $path"))
      } else {
        import scala.jdk.CollectionConverters._
        Right(
          Files
            .walk(dir)
            .iterator()
            .asScala
            .filter(p => Files.isRegularFile(p))
            .filter(p => extensions.exists(ext => p.toString.toLowerCase.endsWith(ext)))
            .map { p =>
              val filename = dir.relativize(p).toString
              val content  = new String(Files.readAllBytes(p))
              (filename, content)
            }
            .toSeq
        )
      }
    }.toResult.left
      .map(e => EvaluationError(s"Failed to load documents: ${e.message}"))
      .flatMap(identity)

  /**
   * Detect dataset format from file.
   */
  private def detectFormat(path: Path): DatasetFormat = {
    val filename = path.getFileName.toString.toLowerCase

    if (filename.endsWith(".jsonl")) {
      DatasetFormat.RAGBench
    } else if (filename.contains("multihop") || filename.contains("multi_hop")) {
      DatasetFormat.MultiHopRAG
    } else {
      // Try to detect from content
      Try {
        val firstLine = scala.io.Source.fromFile(path.toFile).getLines().take(1).mkString
        val json      = ujson.read(firstLine)

        if (json.obj.contains("question") && json.obj.contains("documents")) {
          DatasetFormat.RAGBench
        } else if (json.obj.contains("samples")) {
          DatasetFormat.TestDataset
        } else {
          DatasetFormat.Unknown
        }
      }.getOrElse {
        // Try full JSON parse
        Try {
          val content = new String(Files.readAllBytes(path))
          val json    = ujson.read(content)

          if (json.obj.contains("samples")) {
            DatasetFormat.TestDataset
          } else if (json.obj.contains("data") || json.arr.nonEmpty) {
            DatasetFormat.MultiHopRAG
          } else {
            DatasetFormat.Unknown
          }
        }.getOrElse(DatasetFormat.Unknown)
      }
    }
  }
}

/**
 * Supported dataset formats.
 */
sealed trait DatasetFormat

object DatasetFormat {
  case object RAGBench    extends DatasetFormat
  case object MultiHopRAG extends DatasetFormat
  case object TestDataset extends DatasetFormat
  case object Unknown     extends DatasetFormat
}

object DatasetManager {

  /** Create a new dataset manager */
  def apply(): DatasetManager = new DatasetManager()

  /**
   * Standard dataset paths.
   */
  object Paths {
    val dataRoot: String     = "data"
    val datasetsDir: String  = s"$dataRoot/datasets"
    val ragbenchDir: String  = s"$datasetsDir/ragbench"
    val multihopDir: String  = s"$datasetsDir/multihop-rag"
    val generatedDir: String = s"$dataRoot/generated"
    val resultsDir: String   = s"$dataRoot/results"

    def ragbenchTest: String  = s"$ragbenchDir/test.jsonl"
    def ragbenchTrain: String = s"$ragbenchDir/train.jsonl"
    def multihopTest: String  = s"$multihopDir/test.json"
  }

  /**
   * Check if standard datasets are available.
   */
  def checkDatasets(): Map[String, Boolean] =
    Map(
      "RAGBench (test)"  -> Files.exists(java.nio.file.Paths.get(Paths.ragbenchTest)),
      "RAGBench (train)" -> Files.exists(java.nio.file.Paths.get(Paths.ragbenchTrain)),
      "MultiHop-RAG"     -> Files.exists(java.nio.file.Paths.get(Paths.multihopTest))
    )

  /**
   * Get instructions for downloading datasets.
   */
  def downloadInstructions: String =
    """
      |=== Dataset Download Instructions ===
      |
      |Run the download script:
      |  ./scripts/download-datasets.sh all
      |
      |Or download individually:
      |  ./scripts/download-datasets.sh ragbench    # RAGBench from Hugging Face
      |  ./scripts/download-datasets.sh multihop    # MultiHop-RAG from GitHub
      |
      |Manual download:
      |
      |1. RAGBench:
      |   - Visit: https://huggingface.co/datasets/rungalileo/ragbench
      |   - Download test.jsonl and train.jsonl
      |   - Place in: data/datasets/ragbench/
      |
      |2. MultiHop-RAG:
      |   - Clone: https://github.com/yixuantt/MultiHop-RAG
      |   - Copy dataset to: data/datasets/multihop-rag/
      |""".stripMargin
}
