package org.llm4s.llmconnect.provider

import org.llm4s.config.NamedProviderConfig
import org.llm4s.llmconnect.config.JlamaConfig
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import com.sun.net.httpserver.HttpExchange
import org.llm4s.testkit.{ Applicability, ContractCase, ProviderModuleChecks, ProviderTestConfig, RecordedReply }
import org.llm4s.testkit.LocalProviderTestServer.sendJsonResponse
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * `llm4s-jlama` registers itself, and what it registers works: discovery finds the services entry, the module is the
 * only supplier of `jlama`, a section with no API key round-trips to a client, and the client streams and honours
 * interruption. No test downloads or loads a model: the engine is stubbed ([[JlamaTestEngines]]).
 */
class Llm4sJlamaModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  import JlamaTestEngines.*

  private val section = NamedProviderConfig(
    provider = JlamaProvider.id,
    model = ModelName("tjake/Llama-3.2-1B-Instruct-JQ4"),
    baseUrl = None,
    apiKey = None,
    headers = Map.empty,
    extras = Map.empty
  )

  private def load(hocon: String) =
    given ProviderRegistry = ProviderRegistry.default
    ProviderTestConfig.loadProvider("local", hocon)

  private def loadError(fields: String): String =
    load(s"""llm4s.providers.local { provider = "jlama", $fields }""")
      .fold(_.message, config => fail(s"expected an error, got $config"))

  "the llm4s-jlama services entry" should {

    "be discovered, the only supplier of jlama, and registrable explicitly" in {
      assertModule(new Llm4sJlamaModule)
    }

    "contribute the chat provider under the id jlama and no embeddings" in {
      val module = new Llm4sJlamaModule
      module.providers shouldBe Seq(JlamaProvider)
      module.embeddingProviders shouldBe empty
      JlamaProvider.id.asString shouldBe "jlama"
    }
  }

  "JlamaProvider" should {

    "build a JlamaClient from a config section without loading the model" in {
      val client = assertBuildsClient(JlamaProvider, section)
      client.getClass.getSimpleName shouldBe "JlamaClient"
      client.getContextWindow() shouldBe JlamaConfig.DEFAULT_CONTEXT_WINDOW
      client.getReserveCompletion() shouldBe JlamaConfig.DEFAULT_RESERVE_COMPLETION
      client.close()
    }

    "refuse a config belonging to another provider" in {
      assertRefusesForeignConfig(JlamaProvider)
    }

    "declare streaming and no tool calling" in {
      JlamaProvider.features.streaming shouldBe true
      JlamaProvider.features.toolCalling shouldBe false
    }

    "load a JlamaConfig from a named section with no API key, as an application does" in {
      load("""llm4s.providers.local { provider = "jlama", model = "tjake/Llama-3.2-1B-Instruct-JQ4" }""") match
        case Right(config: JlamaConfig) =>
          config.model.asString shouldBe "tjake/Llama-3.2-1B-Instruct-JQ4"
          config.modelDir shouldBe JlamaConfig.defaultModelDir
          config.threads shouldBe None
          config.endpointUrl shouldBe None
        case other => fail(s"expected a JlamaConfig, got $other")
    }

    "expand a modelDir starting with ~ to the user's home" in {
      load("""llm4s.providers.local { provider = "jlama", model = "tjake/x", modelDir = "~/models" }""") match
        case Right(config: JlamaConfig) =>
          config.modelDir shouldBe Paths.get(System.getProperty("user.home") + "/models")
        case other => fail(s"expected a JlamaConfig, got $other")
    }

    "read modelDir and threads from the section" in {
      val dir = Paths.get("models", "jlama").toAbsolutePath.toString.replace("\\", "\\\\")
      load(
        s"""llm4s.providers.local {
           |  provider = "jlama", model = "tjake/Llama-3.2-1B-Instruct-JQ4"
           |  modelDir = "$dir", threads = 2
           |}""".stripMargin
      ) match
        case Right(config: JlamaConfig) =>
          config.modelDir shouldBe Paths.get("models", "jlama").toAbsolutePath
          config.threads shouldBe Some(2)
        case other => fail(s"expected a JlamaConfig, got $other")
    }

    "name the section and key of a bad value" in {
      loadError("""model = "llama3"""") should include("llm4s.providers.local.model")
      loadError("""model = "tjake/x", threads = 0""") should include("llm4s.providers.local.threads")
      loadError("""model = "tjake/x", threads = many""") should include("llm4s.providers.local.threads")
    }
  }

  "a JlamaClient" should {

    "report a clean ending on complete and on a stream alike, really stream, and declare the endings it cannot serve (RFC 0009)" in {
      // the model runs in-process: there are no provider bytes to serve. CleanText runs against the scripted engine,
      // which answers every call with text and Stop, so the reply the local server holds is never asked for; the
      // other endings need a differently scripted engine, which JlamaClientSpec checks on complete and on a stream
      val inProcess = "an in-process engine with no wire format; JlamaClientSpec checks it with a scripted engine"
      val unread =
        (exchange: HttpExchange) => sendJsonResponse(exchange, 500, """{"error":"JlamaClient sends no request"}""")
      assertModelCallContract(
        Map(
          ContractCase.CleanText          -> Applicability.Applies(RecordedReply(unread, unread)),
          ContractCase.TextCutByLimit     -> Applicability.NotApplicable(inProcess),
          ContractCase.ToolCall           -> Applicability.NotApplicable("JlamaClient does not call tools"),
          ContractCase.NoArgumentToolCall -> Applicability.NotApplicable("JlamaClient does not call tools"),
          ContractCase.ToolCallCutByLimit -> Applicability.NotApplicable("JlamaClient does not call tools"),
          ContractCase.MalformedArguments -> Applicability.NotApplicable("JlamaClient does not call tools"),
          ContractCase.ContentFiltered    -> Applicability.NotApplicable("a local model has no content filter"),
          // the engine's generate call returns or throws: there is no connection to drop, and no event to send
          ContractCase.StreamEndsEarly -> Applicability.NotApplicable("an in-process engine has no stream to cut off"),
          ContractCase.ErrorEvent -> Applicability.NotApplicable("an in-process engine reports a failure by throwing")
        )
      )(_ => client(new ScriptedEngine()))
    }

    "return CancelledError when a call is interrupted while the model generates" in {
      val engine = new EndlessEngine
      val local  = client(engine)
      assertCallCancelsOnceReady("complete")(local.complete(Conversation(Seq(UserMessage("Hello"))))) {
        if !engine.started.await(10, TimeUnit.SECONDS) then fail("the model never started generating")
      }
    }

    "return CancelledError when a stream is interrupted after its first token" in {
      assertCancelsStreamWhenInterrupted(client(new EndlessEngine))
    }
  }
