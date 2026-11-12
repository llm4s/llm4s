package org.llm4s.samples.capability

import org.llm4s.capability.*
import org.llm4s.llmconnect.model.{ ContentPart, Conversation, UserMessage }
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.llm4s.schema.SchemaOf
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.net.URI

/**
 * Checks once, at the edge, that gpt-4o sees images and answers in a schema, then captions an image and extracts a
 * structured caption through the capability-typed client. The model is named with `model"openai/gpt-4o"`, which the
 * compiler checks against the bundled model data, and checked again at run time against the registry. The client is
 * scripted, so the sample runs with no API key.
 *
 * Run with:
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.capability.CapabilityTypedClientExample"
 * }}}
 */
object CapabilityTypedClientExample extends App {

  private val logger = LoggerFactory.getLogger(getClass)

  final case class Caption(altText: String, objects: List[String]) derives SchemaOf, upickle.default.ReadWriter

  // Code that needs a capability takes a Capable of it: a client checked only for Tools would not compile here.
  private def caption(c: Capable[Vision], image: ContentPart.Image): Result[String] =
    c.describe(image, "One-line alt text").map(_.content)

  private def extract(c: Capable[StructuredOutput], image: ContentPart.Image): Result[Caption] =
    c.completeStructuredOf[Caption](
      Conversation(Seq(UserMessage(Vector(image, ContentPart.Text("Describe the picture")))))
    )

  private val client = ScriptedLLMClient.sequence(
    Reply.text("A cat asleep on a red mat"),
    Reply.text("""{"altText":"A cat asleep on a red mat","objects":["cat","mat"]}""")
  )

  private val gpt4o = model"openai/gpt-4o" // a compile error if the bundled data did not know the model

  private val outcome = for {
    registry <- ModelRegistryService.default()
    given ModelRegistryService = registry
    capable    <- client.requireCapabilities[Vision & StructuredOutput](gpt4o) // checked once, here
    image      <- ContentPart.image(MediaSource.Url(URI.create("https://example.com/cat.png")), MediaType.Png)
    alt        <- caption(capable, image)
    structured <- extract(capable, image)
  } yield (capable, alt, structured)

  outcome match {
    case Right((capable, alt, structured)) =>
      logger.info("Model literal: {}", gpt4o)
      logger.info("Checked: {}", capable)
      logger.info("Alt text: {}", alt)
      logger.info("Structured: {}", structured)
    case Left(error) =>
      logger.error("Failed: {}", error.formatted)
  }
}
