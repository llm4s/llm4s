package org.llm4s.rag.benchmark

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.rag.evaluation.{ EvalSample, EvaluationError, TestDataset }
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Generates ground truth evaluation datasets from documents using LLM.
 *
 * Creates question-answer pairs with context that can be used for RAGAS evaluation.
 * Supports multiple generation strategies for different testing scenarios.
 *
 * @param llmClient LLM client for generation
 * @param options Generation options
 *
 * @example
 * {{{
 * val generator = GroundTruthGenerator(llmClient)
 *
 * // Generate from documents
 * val dataset = generator.generateFromDocuments(
 *   documents = Seq(doc1, doc2, doc3),
 *   questionsPerDoc = 5,
 *   datasetName = "my-test-set"
 * )
 *
 * // Save for later use
 * TestDataset.save(dataset, "data/generated/my-test-set.json")
 * }}}
 */
class GroundTruthGenerator(
  llmClient: LLMClient,
  options: GeneratorOptions = GeneratorOptions()
) {

  /**
   * Generate a test dataset from documents.
   *
   * @param documents Sequence of (id, content) pairs
   * @param questionsPerDoc Number of QA pairs per document
   * @param datasetName Name for the generated dataset
   * @return Generated TestDataset or error
   */
  def generateFromDocuments(
    documents: Seq[(String, String)],
    questionsPerDoc: Int = 3,
    datasetName: String = "generated"
  ): Result[TestDataset] = {
    if (documents.isEmpty) {
      return Left(EvaluationError("No documents provided"))
    }

    val allSamples = documents.flatMap { case (docId, content) =>
      generateFromDocument(docId, content, questionsPerDoc) match {
        case Right(samples) => samples
        case Left(_)        => Seq.empty // Skip failed documents
      }
    }

    if (allSamples.isEmpty) {
      Left(EvaluationError("Failed to generate any samples"))
    } else {
      Right(
        TestDataset(
          name = datasetName,
          samples = allSamples,
          metadata = Map(
            "generated"       -> "true",
            "generator"       -> "GroundTruthGenerator",
            "documentCount"   -> documents.size.toString,
            "questionsPerDoc" -> questionsPerDoc.toString
          )
        )
      )
    }
  }

  /**
   * Generate QA pairs from a single document.
   *
   * @param docId Document identifier
   * @param content Document content
   * @param count Number of QA pairs to generate
   * @return Sequence of EvalSamples
   */
  def generateFromDocument(
    docId: String,
    content: String,
    count: Int = 3
  ): Result[Seq[EvalSample]] = {
    val systemPrompt =
      s"""You are an expert at creating high-quality question-answer pairs for RAG evaluation.
         |Given a document, generate exactly $count diverse question-answer pairs.
         |
         |Requirements:
         |1. Questions should be answerable from the document content
         |2. Answers should be accurate, complete, and grounded in the document
         |3. Include a mix of question types:
         |   - Factual questions (who, what, when, where)
         |   - Explanatory questions (how, why)
         |   - Comparative or analytical questions (if applicable)
         |4. Answers should be self-contained (don't say "as mentioned in the document")
         |5. Questions should be clear and specific
         |
         |Respond with ONLY a JSON array of objects with "question" and "answer" fields.
         |Example: [{"question": "What is X?", "answer": "X is..."}]""".stripMargin

    val userPrompt =
      s"""Generate $count question-answer pairs from this document:
         |
         |---
         |$content
         |---
         |
         |Respond with ONLY a JSON array:""".stripMargin

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val completionOptions = CompletionOptions(
      temperature = options.temperature,
      maxTokens = Some(options.maxTokens)
    )

    for {
      completion <- llmClient.complete(conversation, completionOptions)
      qaPairs    <- parseQAPairs(completion.content)
    } yield qaPairs.map { case (question, answer) =>
      EvalSample(
        question = question,
        answer = answer,
        contexts = Seq(content),
        groundTruth = Some(answer),
        metadata = Map(
          "sourceDocId" -> docId,
          "generated"   -> "true"
        )
      )
    }
  }

  /**
   * Generate multi-hop questions that require information from multiple documents.
   *
   * @param documents Documents to use as sources
   * @param count Number of multi-hop questions to generate
   * @return Sequence of EvalSamples with multiple contexts
   */
  def generateMultiHop(
    documents: Seq[(String, String)],
    count: Int = 5
  ): Result[Seq[EvalSample]] = {
    if (documents.size < 2) {
      return Left(EvaluationError("Need at least 2 documents for multi-hop questions"))
    }

    // Take pairs of documents
    val docPairs = documents.sliding(2).take(count).toSeq

    val samples = docPairs.flatMap { pair =>
      if (pair.size >= 2) {
        generateMultiHopFromPair(pair(0), pair(1))
      } else {
        None
      }
    }

    Right(samples)
  }

  private def generateMultiHopFromPair(
    doc1: (String, String),
    doc2: (String, String)
  ): Option[EvalSample] = {
    val systemPrompt =
      """You are an expert at creating multi-hop reasoning questions.
        |Given two documents, create a question that requires information from BOTH documents to answer.
        |
        |Requirements:
        |1. The question must require combining facts from both documents
        |2. Neither document alone should be sufficient to answer
        |3. The answer should synthesize information from both sources
        |
        |Respond with ONLY a JSON object with "question" and "answer" fields.
        |Example: {"question": "How does X relate to Y?", "answer": "..."}""".stripMargin

    val userPrompt =
      s"""Create a multi-hop question from these two documents:
         |
         |Document 1:
         |${doc1._2}
         |
         |Document 2:
         |${doc2._2}
         |
         |Respond with ONLY JSON:""".stripMargin

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val completionOptions = CompletionOptions(
      temperature = options.temperature,
      maxTokens = Some(options.maxTokens)
    )

    llmClient.complete(conversation, completionOptions).toOption.flatMap { completion =>
      parseSingleQA(completion.content).map { case (question, answer) =>
        EvalSample(
          question = question,
          answer = answer,
          contexts = Seq(doc1._2, doc2._2),
          groundTruth = Some(answer),
          metadata = Map(
            "multiHop"   -> "true",
            "sourceDoc1" -> doc1._1,
            "sourceDoc2" -> doc2._1,
            "generated"  -> "true"
          )
        )
      }
    }
  }

  /**
   * Generate from a directory of documents.
   *
   * @param dirPath Path to directory
   * @param questionsPerDoc QA pairs per document
   * @param extensions File extensions to include
   * @param datasetName Name for dataset
   * @return Generated TestDataset
   */
  def generateFromDirectory(
    dirPath: String,
    questionsPerDoc: Int = 3,
    extensions: Set[String] = Set(".txt", ".md"),
    datasetName: String = "generated"
  ): Result[TestDataset] = {
    val datasetManager = DatasetManager()

    datasetManager.loadDocumentsFromDirectory(dirPath, extensions).flatMap { docs =>
      val docPairs = docs.map { case (filename, content) => (filename, content) }
      generateFromDocuments(docPairs, questionsPerDoc, datasetName)
    }
  }

  /**
   * Parse QA pairs from LLM response.
   */
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
    }.toResult.left.map(e => EvaluationError(s"Failed to parse QA pairs: ${e.message}"))

  /**
   * Parse single QA from LLM response.
   */
  private def parseSingleQA(response: String): Option[(String, String)] =
    Try {
      val jsonStr  = extractJsonObject(response)
      val obj      = ujson.read(jsonStr).obj
      val question = obj("question").str
      val answer   = obj("answer").str
      (question, answer)
    }.toOption

  /**
   * Extract JSON array from response.
   */
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
   * Extract JSON object from response.
   */
  private def extractJsonObject(response: String): String = {
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

    val startIdx = withoutCodeBlock.indexOf('{')
    val endIdx   = withoutCodeBlock.lastIndexOf('}')

    if (startIdx >= 0 && endIdx > startIdx) {
      withoutCodeBlock.substring(startIdx, endIdx + 1)
    } else {
      withoutCodeBlock
    }
  }
}

/**
 * Options for ground truth generation.
 *
 * @param temperature LLM temperature (lower = more deterministic)
 * @param maxTokens Max tokens for generation
 */
final case class GeneratorOptions(
  temperature: Double = 0.7,
  maxTokens: Int = 2000
)

object GroundTruthGenerator {

  /**
   * Create a generator with default options.
   */
  def apply(llmClient: LLMClient): GroundTruthGenerator =
    new GroundTruthGenerator(llmClient)

  /**
   * Create a generator with custom options.
   */
  def apply(llmClient: LLMClient, options: GeneratorOptions): GroundTruthGenerator =
    new GroundTruthGenerator(llmClient, options)

  /**
   * Generate and save a dataset in one call.
   *
   * @param llmClient LLM client
   * @param documents Source documents
   * @param outputPath Output JSON file path
   * @param questionsPerDoc QA pairs per document
   * @param datasetName Dataset name
   * @return Unit or error
   */
  def generateAndSave(
    llmClient: LLMClient,
    documents: Seq[(String, String)],
    outputPath: String,
    questionsPerDoc: Int = 3,
    datasetName: String = "generated"
  ): Result[Unit] = {
    val generator = GroundTruthGenerator(llmClient)

    for {
      dataset <- generator.generateFromDocuments(documents, questionsPerDoc, datasetName)
      _       <- TestDataset.save(dataset, outputPath)
    } yield ()
  }
}
