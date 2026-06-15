package org.llm4s.benchmarks

import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.vectorstore.KeywordDocument

object BenchmarkFixtures {

  private val sampleTexts = Vector(
    "Scala is a strong statically typed high-level general-purpose programming language.",
    "The JVM provides garbage collection and cross-platform compatibility for Scala programs.",
    "Functional programming techniques improve composability and testability of LLM applications.",
    "LLMs can be integrated with tool calling for building powerful agentic workflows.",
    "Token counting is essential for managing LLM context windows and controlling API costs.",
    "Context compression reduces the number of tokens in a conversation history before submission.",
    "SQLite provides an embedded relational database with full-text search support via FTS5.",
    "BM25 is a ranking function widely used in information retrieval and search systems.",
    "JMH benchmarks measure the performance of Java and Scala code accurately and repeatably.",
    "Throughput and latency are the two primary performance metrics for libraries and frameworks."
  )

  def makeConversation(messageCount: Int): Conversation = {
    val messages = (0 until messageCount).map { i =>
      val text = sampleTexts(i % sampleTexts.length) * ((i / sampleTexts.length) + 1)
      if (i % 2 == 0) UserMessage(text)
      else AssistantMessage(contentOpt = Some(text))
    }
    Conversation(messages)
  }

  def makeDocuments(count: Int): Seq[KeywordDocument] =
    (0 until count).map { i =>
      KeywordDocument(
        s"doc-$i",
        s"${sampleTexts(i % sampleTexts.length)} document $i scala jvm programming language"
      )
    }

  def makeToolRegistry(toolCount: Int): ToolRegistry = {
    val tools = (0 until toolCount).flatMap { i =>
      val schema = Schema
        .`object`[Map[String, Any]](s"Parameters for echo tool $i")
        .withProperty(Schema.property("message", Schema.string("Message to echo")))

      ToolBuilder[Map[String, Any], String](
        s"echo_$i",
        s"Echoes a message (tool $i)",
        schema
      ).withHandler(extractor => extractor.getString("message").map(msg => s"echo-$i: $msg"))
        .buildSafe()
        .toOption
    }

    new ToolRegistry(tools)
  }
}
