package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

/**
 * A first answer with no API key and no model server (#2284): the samples' `jlama-local` section runs
 * `tjake/Llama-3.2-1B-Instruct-JQ4` inside this JVM with llm4s-jlama. The first run downloads the model (about 0.7 GB)
 * to `~/.cache/llm4s/jlama`; later runs load it from there.
 *
 * {{{
 * sbt localJlamaDemo
 * }}}
 *
 * The alias forks a JVM with the flags Jlama needs (`--add-modules=jdk.incubator.vector --enable-preview`, set in the
 * samples' `run / javaOptions`). It prints the answer, and exits with status 1 when there is none, so the nightly
 * `jlama-nightly.yml` workflow can run it as a check.
 */
object LocalJlamaExample {

  def main(args: Array[String]): Unit = {
    val answer = for {
      providerConfig <- Llm4sConfig.provider("jlama-local")
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client <- LLMConnect.getClient(providerConfig)
      completion <- client.streamComplete(
        Conversation(Seq(UserMessage("In one sentence, what is Scala?"))),
        onChunk = chunk => chunk.content.foreach(text => print(text))
      )
      _ = client.close()
    } yield completion.content.trim

    println()
    answer match {
      case Right(text) if text.nonEmpty => println(s"(${text.length} characters, from a model running in this JVM)")
      case Right(_) =>
        System.err.println("The model returned an empty answer")
        sys.exit(1)
      case Left(error) =>
        System.err.println(s"Error: ${error.formatted}")
        sys.exit(1)
    }
  }
}
