package org.llm4s.samples.actions

import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.error.LLMError
import org.llm4s.tokens.Tokenizer
import org.llm4s.identity.TokenizerId
import org.llm4s.identity.TokenizerId.O200K_BASE

/**
 * Example demonstrating how to use LLM4S for text summarization.
 *
 * This example shows how to:
 * 1. Create a conversation with system instructions for summarization
 * 2. Send text to be summarized to the LLM
 * 3. Process the summarized result
 */
object SummarizationExample {
  def main(args: Array[String]): Unit = {
    // Sample text to summarize - you can replace with any text
    val textToSummarize1 = """
    Scala combines object-oriented and functional programming in one concise, high-level language.
    Scala's static types help avoid bugs in complex applications, and its JVM and JavaScript runtimes
    let you build high-performance systems with easy access to huge ecosystems of libraries.
    Scala was created by Martin Odersky and his research group at EPFL, adjacent to Lake Geneva and the Alps,
    in Lausanne, Switzerland. Scala has been released under a BSD 3-Clause License.
    The name Scala comes from the word "scalable", signifying that it is designed to grow with the demands of its users.
    Scala was first released in 2004, and has since seen adoption by many companies including Twitter, Netflix, and LinkedIn.
    """

    val textToSummarize2 = """
    Heidi is a novel about a young orphan girl who is sent to live with her reclusive grandfather in the Swiss Alps. 
    At first, her grandfather is gruff and distant, but Heidi's warmth and kindness gradually soften his heart. 
    She thrives in the mountains, forming deep bonds with her grandfather, the goats, and her friend Peter, a young goatherd. 
    However, her happiness is disrupted when she is sent to Frankfurt to be a companion to Clara,
    a wealthy but sickly girl in a wheelchair. Although Heidi cares for Clara, she becomes homesick and longs for the mountains. 
    Eventually, she returns to her grandfather, and later, Clara visits the Alps, where the fresh air and Heidi’s encouragement 
    help her regain her strength and walk again. The story highlights themes of nature, resilience, and the power of love and friendship.
    """

    // Create a system message with instructions for summarization
    val systemPrompt = """You are a helpful assistant specialized in summarizing text.
    |When given text, create a concise summary that:
    |1. Captures the main points and key information
    |2. Removes unnecessary details and redundancy
    |3. Maintains the original meaning and context
    |4. Is roughly 30% the length of the original text
    |
    |Return only the summary without additional commentary.""".stripMargin

    // Create a conversation with the system prompt and user's text
    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(s"Please summarize the following text:\n\n$textToSummarize1")
      )
    )

    // Get a client using environment variables
    val client = LLM.client()

    println("Original text:")
    println("--------------")
    println(textToSummarize1.trim)
    println()

    println("Generating summary...")

    // Complete the conversation to get the summary
    client.complete(conversation) match {
      case Right(completion) =>
        println("Summary:")
        println("--------")
        println(completion.message.content.trim)

        // Print usage information if available
        completion.usage.foreach { usage =>
          println(
            s"Token usage: ${usage.totalTokens} total (${usage.promptTokens} prompt, ${usage.completionTokens} completion)"
          )

          val tokenizerId = O200K_BASE

          val tokenizerOpt = Tokenizer.lookupStringTokenizer(tokenizerId)
          if (tokenizerOpt.isDefined) {

            val tokenizer          = tokenizerOpt.get
            val systemPromptTokens = tokenizer.encode(systemPrompt).size
            // Calculate compression ratio for summary
            val originalTokens   = usage.promptTokens - systemPromptTokens
            val outputTokens     = usage.completionTokens
            val compressionRatio = outputTokens.toDouble / originalTokens.toDouble
            // add compression in words and in characters as well
            println(f"Compression ratio: ${compressionRatio * 100}%.1f%% (lower is better)")
            println(
              f"${textToSummarize1.split("\\s+").length} words were successfully summarized into ${completion.message.content.trim.split("\\s+").length} words!"
            )
          } else {
            println("Tokenizer not found!")
          }
        }

      case Left(error) =>
        println(s"Error generating summary: ${error.formatted}")
    }

    // Call the helper method like this to use summarization logic elsewhere
    val summaryResult = summarizeText(textToSummarize2, Some("50 words"))
    summaryResult match {
      case Right(summary) => println(summary)
      case Left(error)    => println(s"Error: ${error.formatted}")
    }
  }

  /**
   * Helper method that can be used programmatically to summarize text
   *
   * @param text The text to summarize
   * @param maxLength Optional target maximum length (e.g., "100 words" or "2 paragraphs")
   * @return Either an error or the summarized text
   */
  def summarizeText(text: String, maxLength: Option[String] = None): Result[String] = {
    // Create system prompt with optional length constraint
    val lengthConstraint = maxLength.map(len => s"Ensure the summary is no longer than $len.").getOrElse("")
    val systemPrompt = s"""Summarize the following text concisely while preserving the key information and main points.
                         |$lengthConstraint
                         |Return only the summary without additional commentary.""".stripMargin

    // Create conversation
    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(text)
      )
    )

    // Get client and complete
    val client = LLM.client()
    client.complete(conversation).map(_.message.content.trim)
  }
}
