package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, TryOps }

import java.nio.file.{ Files, Paths }
import scala.util.Try

/**
 * Test dataset for RAG evaluation.
 *
 * Supports loading from JSON files and generating synthetic test cases
 * from documents using LLM.
 *
 * @param name Name identifier for this dataset
 * @param samples The evaluation samples
 * @param metadata Additional metadata for tracking/filtering
 *
 * @example
 * {{{{
 * // Load from file
 * val dataset = TestDataset.fromJsonFile("test_cases.json")
 *
 * // Generate synthetic test cases
 * val generated = TestDataset.generateFromDocuments(
 *   documents = Seq("Paris is the capital of France...", "Tokyo is the capital of Japan..."),
 *   llmClient = client,
 *   samplesPerDoc = 3
 * )
 *
 * // Save to file
 * TestDataset.save(dataset, "output.json")
 * }}}}
 */
final case class TestDataset(
  name: String,
  samples: Seq[EvalSample],
  metadata: Map[String, String] = Map.empty
) {

  /**
   * Filter samples based on a predicate.
   */
  def filter(predicate: EvalSample => Boolean): TestDataset =
    copy(samples = samples.filter(predicate))

  /**
   * Get only samples that have ground truth.
   */
  def withGroundTruth: TestDataset =
    filter(_.groundTruth.isDefined)

  /**
   * Get samples without ground truth.
   */
  def withoutGroundTruth: TestDataset =
    filter(_.groundTruth.isEmpty)

  /**
   * Take first n samples.
   */
  def take(n: Int): TestDataset =
    copy(samples = samples.take(n))

  /**
   * Get a random subset of samples.
   */
  def sample(n: Int, seed: Long = System.currentTimeMillis()): TestDataset = {
    val random = new scala.util.Random(seed)
    copy(samples = random.shuffle(samples).take(n))
  }

  /**
   * Add metadata to the dataset.
   */
  def withMetadata(key: String, value: String): TestDataset =
    copy(metadata = metadata + (key -> value))
}

object TestDataset {

  /**
   * Load dataset from a JSON file.
   *
   * Expected JSON format:
   * {{{{
   * {
   *   "name": "my_dataset",
   *   "metadata": {"source": "manual"},
   *   "samples": [
   *     {
   *       "question": "What is the capital of France?",
   *       "answer": "Paris is the capital of France.",
   *       "contexts": ["Paris is the capital and largest city of France."],
   *       "ground_truth": "The capital of France is Paris.",
   *       "metadata": {"category": "geography"}
   *     }
   *   ]
   * }
   * }}}}
   *
   * @param path Path to the JSON file
   * @return The loaded dataset or an error
   */
  def fromJsonFile(path: String): Result[TestDataset] =
    Try {
      val content = new String(Files.readAllBytes(Paths.get(path)))
      parseJson(content)
    }.toResult.left.map(e => EvaluationError(s"Failed to read file: ${e.message}")).flatten

  /**
   * Parse dataset from JSON string.
   *
   * @param json The JSON string
   * @return The parsed dataset or an error
   */
  def fromJson(json: String): Result[TestDataset] =
    Try(parseJson(json)).toResult.left.map(e => EvaluationError(s"Failed to parse JSON: ${e.message}")).flatten

  private def parseJson(json: String): Result[TestDataset] =
    Try {
      val root = ujson.read(json)

      val name = root.obj.get("name").map(_.str).getOrElse("unnamed")

      val metadata = root.obj
        .get("metadata")
        .map(_.obj.map { case (k, v) => k -> v.str }.toMap)
        .getOrElse(Map.empty)

      val samples = root("samples").arr.map { s =>
        val obj = s.obj

        val question = obj("question").str
        val answer   = obj("answer").str

        val contexts = obj("contexts").arr.map(_.str).toSeq

        val groundTruth = obj.get("ground_truth").flatMap(gt => if (gt.isNull) None else Some(gt.str))

        val sampleMetadata = obj
          .get("metadata")
          .map(_.obj.map { case (k, v) => k -> v.str }.toMap)
          .getOrElse(Map.empty)

        EvalSample(
          question = question,
          answer = answer,
          contexts = contexts,
          groundTruth = groundTruth,
          metadata = sampleMetadata
        )
      }.toSeq

      TestDataset(name = name, samples = samples, metadata = metadata)
    }.toResult.left.map(e => EvaluationError(s"Invalid JSON structure: ${e.message}"))

  /**
   * Generate synthetic test cases from documents using LLM.
   *
   * For each document, generates question-answer pairs with the document
   * serving as both context and source of ground truth.
   *
   * @param documents The source documents
   * @param llmClient LLM client for generation
   * @param samplesPerDoc Number of QA pairs to generate per document
   * @param datasetName Name for the generated dataset
   * @return Generated dataset or an error
   */
  def generateFromDocuments(
    documents: Seq[String],
    llmClient: LLMClient,
    samplesPerDoc: Int = 3,
    datasetName: String = "generated"
  ): Result[TestDataset] = {
    val allSamples = documents.zipWithIndex.flatMap { case (doc, docIdx) =>
      generateSamplesFromDocument(doc, llmClient, samplesPerDoc, docIdx) match {
        case Right(samples) => samples
        case Left(_)        => Seq.empty // Skip failed documents
      }
    }

    if (allSamples.isEmpty) {
      Left(EvaluationError("Failed to generate any samples from documents"))
    } else {
      Right(
        TestDataset(
          name = datasetName,
          samples = allSamples,
          metadata = Map(
            "generated"     -> "true",
            "documentCount" -> documents.size.toString,
            "samplesPerDoc" -> samplesPerDoc.toString
          )
        )
      )
    }
  }

  private def generateSamplesFromDocument(
    document: String,
    llmClient: LLMClient,
    count: Int,
    docIndex: Int
  ): Result[Seq[EvalSample]] = {
    val systemPrompt =
      s"""You are an expert at creating evaluation test cases for RAG systems.
         |Given a document, generate exactly $count question-answer pairs.
         |
         |Requirements:
         |- Questions should be answerable from the document
         |- Answers should be accurate and grounded in the document
         |- Questions should be diverse (factual, explanatory, comparative)
         |- Answers should be complete but concise
         |
         |Respond with ONLY a JSON array of objects with "question" and "answer" fields.
         |Example: [{"question": "What is X?", "answer": "X is..."}]""".stripMargin

    val userPrompt = s"""Generate $count question-answer pairs from this document:

\"\"\"
$document
\"\"\"

Respond with ONLY a JSON array:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(temperature = 0.7, maxTokens = Some(2000))

    for {
      completion <- llmClient.complete(conversation, options)
      qaPairs    <- parseQAPairs(completion.content)
    } yield qaPairs.map { case (question, answer) =>
      EvalSample(
        question = question,
        answer = answer,
        contexts = Seq(document),
        groundTruth = Some(answer), // Answer serves as ground truth
        metadata = Map("sourceDoc" -> docIndex.toString, "generated" -> "true")
      )
    }
  }

  private def parseQAPairs(response: String): Result[Seq[(String, String)]] =
    Try {
      val jsonStr = extractJsonArray(response)
      val arr     = ujson.read(jsonStr).arr

      arr.map { v =>
        val obj      = v.obj
        val question = obj("question").str
        val answer   = obj("answer").str
        (question, answer)
      }.toSeq
    }.toResult.left.map(e => EvaluationError.parseError(s"Failed to parse QA pairs: ${e.message}"))

  private def extractJsonArray(response: String): String = {
    val trimmed = response.trim

    val withoutCodeBlock = if (trimmed.startsWith("```")) {
      val lines = trimmed.split("\n")
      val start = 1
      val end   = lines.lastIndexWhere(_.trim == "```")
      if (end > start) {
        lines.slice(start, end).mkString("\n")
      } else {
        trimmed.stripPrefix("```json").stripPrefix("```").stripSuffix("```")
      }
    } else {
      trimmed
    }

    val startIdx = withoutCodeBlock.indexOf('[')
    val endIdx   = withoutCodeBlock.lastIndexOf(']')

    if (startIdx >= 0 && endIdx > startIdx) {
      withoutCodeBlock.substring(startIdx, endIdx + 1)
    } else {
      withoutCodeBlock
    }
  }

  /**
   * Convert dataset to JSON string.
   *
   * @param dataset The dataset to convert
   * @param pretty Whether to format with indentation
   * @return JSON string representation
   */
  def toJson(dataset: TestDataset, pretty: Boolean = true): String = {
    val samplesJson = dataset.samples.map { s =>
      val base = ujson.Obj(
        "question" -> s.question,
        "answer"   -> s.answer,
        "contexts" -> ujson.Arr(s.contexts.map(ujson.Str(_)): _*)
      )

      s.groundTruth.foreach(gt => base("ground_truth") = gt)

      if (s.metadata.nonEmpty) {
        base("metadata") = ujson.Obj.from(s.metadata.map { case (k, v) => k -> ujson.Str(v) })
      }

      base
    }

    val root = ujson.Obj(
      "name"    -> dataset.name,
      "samples" -> ujson.Arr(samplesJson: _*)
    )

    if (dataset.metadata.nonEmpty) {
      root("metadata") = ujson.Obj.from(dataset.metadata.map { case (k, v) => k -> ujson.Str(v) })
    }

    if (pretty) ujson.write(root, indent = 2) else ujson.write(root)
  }

  /**
   * Save dataset to a JSON file.
   *
   * @param dataset The dataset to save
   * @param path Path for the output file
   * @return Success or an error
   */
  def save(dataset: TestDataset, path: String): Result[Unit] =
    Try {
      val json = toJson(dataset)
      Files.write(Paths.get(path), json.getBytes)
      ()
    }.toResult.left.map(e => EvaluationError(s"Failed to save dataset: ${e.message}"))

  /**
   * Create an empty dataset.
   */
  def empty(name: String = "empty"): TestDataset = TestDataset(name, Seq.empty)

  /**
   * Create a dataset from a sequence of samples.
   */
  def create(name: String, samples: Seq[EvalSample]): TestDataset =
    new TestDataset(name = name, samples = samples)

  /**
   * Create a simple dataset with a single sample (useful for quick testing).
   */
  def single(
    question: String,
    answer: String,
    contexts: Seq[String],
    groundTruth: Option[String] = None
  ): TestDataset = TestDataset(
    name = "single",
    samples = Seq(EvalSample(question, answer, contexts, groundTruth))
  )
}
