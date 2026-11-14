package org.llm4s.config

import org.llm4s.configpolicy.{ CatalogEnvironment, ConfigPolicy, ConfigPolicyEngine }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.{ AzureConfig, BedrockConfig, OpenAICompatibleConfig, ProviderConfig }
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ openAICompletion, sendJsonResponse, withServer }
import org.llm4s.testkit.ProviderModuleChecks
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `docs/guide/reference-architecture.md` (#2315) and the `deploy/overlays/enterprise` files it shows, tested as
 * written: the blocks are read from the guide and the overlay, never copied here.
 *
 * The gateway section is loaded through `Llm4sConfig` and then driven against a local server standing in for the
 * gateway, which records what the guide says the gateway receives: the path, the key from the file (rotated), the
 * tenant headers and the model. It is in package `org.llm4s.config` because loading from a given source is not public
 * API. The environment is injected with the real one switched off.
 */
class ReferenceArchitectureGuideSpec extends AnyWordSpec with Matchers with EitherValues:

  private val Guide    = "docs/guide/reference-architecture.md"
  private val Overlay  = "deploy/overlays/enterprise"
  private val KeyPath  = "/var/run/secrets/llm4s/gateway-api-key"
  private val DrKey    = "/var/run/secrets/llm4s/gateway-dr-api-key"
  private val Gateway  = "http://litellm:4000/v1"
  private val hello    = Conversation(Seq(UserMessage("Hello")))
  private val Template = "(?s)```([a-z]*)\\n(.*?)```".r

  private lazy val root: Path =
    Iterator
      .iterate(Paths.get(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve(Guide)))
      .getOrElse(fail(s"$Guide was not found above ${System.getProperty("user.dir")}"))

  private def read(relative: String): String =
    new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8)

  private def blocks(language: String): List[String] =
    Template.findAllMatchIn(read(Guide)).collect { case m if m.group(1) == language => m.group(2) }.toList

  private lazy val hocon = blocks("hocon")
  private lazy val scala = blocks("scala")

  private def squash(text: String): String = text.replaceAll("\\s+", " ").trim

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("gateway-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content)

  /** The block with its mounted key file replaced by `file`, and the gateway by `baseUrl` when given. */
  private def withLocal(block: String, mounted: String, file: Path, baseUrl: Option[String] = None): String =
    val local = block.replace("\"" + mounted + "\"", quoted(file))
    baseUrl.fold(local)(url => local.replace("\"" + Gateway + "\"", "\"" + url + "\""))

  private def load(text: String, section: Option[String] = None): ProviderConfig =
    // No environment variable; `user.home` only because the samples' classpath carries termflow's reference.conf,
    // which substitutes it, and ReferenceConfig resolves without system properties.
    val source = ReferenceConfig.withEnv(text, Map("user.home" -> System.getProperty("user.home")))
    section.fold(Llm4sConfig.defaultProvider(source))(name => Llm4sConfig.provider(source, name)).value

  private def gatewayConfig(file: Path, baseUrl: Option[String] = None): OpenAICompatibleConfig =
    load(withLocal(hocon.head, KeyPath, file, baseUrl)) match
      case cfg: OpenAICompatibleConfig => cfg
      case other                       => fail(s"expected the openai-compatible gateway section, got $other")

  "the guide" should {

    "have the gateway, private-endpoint and failover configuration blocks, in that order" in {
      hocon should have size 3
    }

    "show the enterprise overlay's application.conf exactly as the overlay mounts it" in {
      squash(hocon.head) shouldBe squash(read(s"$Overlay/application.conf"))
    }
  }

  "the gateway section" should {

    "load through Llm4sConfig as the default provider, with the tenant headers, the window and the timeouts" in {
      val cfg = gatewayConfig(keyFile("sk-gateway-0001"))

      cfg.baseUrl shouldBe Gateway
      cfg.model shouldBe "gpt-4o-mini"
      cfg.headers shouldBe Map("X-Tenant-Id" -> "acme-retail", "X-Cost-Centre" -> "cc-4711")
      cfg.contextWindow shouldBe 128000
      cfg.timeouts.request shouldBe Some(60.seconds)
      cfg.timeouts.stream shouldBe Some(5.minutes)
    }

    "send the gateway key, the tenant headers and the model to <baseUrl>/chat/completions, a rotated key next" in {
      val file = keyFile("sk-gateway-first-0001\n")
      val seen = new ConcurrentLinkedQueue[(String, String, String, String, String)]()
      withServer("/") { exchange =>
        val headers = exchange.getRequestHeaders
        val body    = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        seen.add(
          (
            exchange.getRequestURI.getPath,
            headers.getFirst("Authorization"),
            headers.getFirst("X-Tenant-Id"),
            headers.getFirst("X-Cost-Centre"),
            ujson.read(body)("model").str
          )
        )
        sendJsonResponse(exchange, 200, openAICompletion("ok"))
      } { serverUrl =>
        val cfg                    = gatewayConfig(file, Some(s"$serverUrl/v1"))
        given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
        val client                 = LLMConnect.getClient(cfg).value

        client.complete(hello).value.content shouldBe "ok"
        Files.writeString(file, "sk-gateway-rotated-0002")
        client.complete(hello).value.content shouldBe "ok"
        client.close()
      }
      seen.asScala.toList shouldBe List(
        ("/v1/chat/completions", "Bearer sk-gateway-first-0001", "acme-retail", "cc-4711", "gpt-4o-mini"),
        ("/v1/chat/completions", "Bearer sk-gateway-rotated-0002", "acme-retail", "cc-4711", "gpt-4o-mini")
      )
    }

    "comply with the guide's gateway policy, which the prod preset's refusal of openai-compatible makes necessary" in {
      val cfg = gatewayConfig(keyFile("sk-gateway-0001"))
      val gatewayOnly =
        ConfigPolicy()
          .withAllowedProviders("openai-compatible")
          .withAllowedModelPatterns("openai-compatible/gpt-4o-mini")
          .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "openai-compatible", "http://litellm:4000/v1")
          .withMaxContextWindow(CatalogEnvironment.Prod, "openai-compatible", 128000)
          .withOwnApiKeyRequired(CatalogEnvironment.Prod)
      // the policy above is the guide's, line for line
      val policyBlock = squash(scala.find(_.contains("gatewayOnly")).getOrElse(fail("no policy block in the guide")))
      List(
        """.withAllowedProviders("openai-compatible")""",
        """.withAllowedModelPatterns("openai-compatible/gpt-4o-mini")""",
        """.withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "openai-compatible", "http://litellm:4000/v1")""",
        """.withMaxContextWindow(CatalogEnvironment.Prod, "openai-compatible", 128000)""",
        """.withOwnApiKeyRequired(CatalogEnvironment.Prod)"""
      ).foreach(line => policyBlock should include(line))

      ConfigPolicyEngine.check(cfg, gatewayOnly, CatalogEnvironment.Prod) shouldBe Nil
      ConfigPolicyEngine.check(cfg, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod).map(_.rule) should
        contain("allowedProviders")
    }
  }

  "the failover section" should {

    "load beside the gateway, with its own key file and the same tenant headers" in {
      load(withLocal(hocon(2), DrKey, keyFile("sk-gateway-dr-0001")), Some("gateway-dr")) match
        case cfg: OpenAICompatibleConfig =>
          cfg.baseUrl shouldBe "https://llm-gateway.eu-west-1.example.internal/v1"
          cfg.model shouldBe "gpt-4o-mini"
          cfg.headers shouldBe Map("X-Tenant-Id" -> "acme-retail", "X-Cost-Centre" -> "cc-4711")
          cfg.contextWindow shouldBe 128000
        case other => fail(s"expected an openai-compatible section, got $other")
    }
  }

  "the private-endpoint sections" should {

    "load Azure OpenAI with Entra ID and no key, and Bedrock on its regional endpoint" in {
      load(hocon(1), Some("azure-private")) match
        case azure: AzureConfig =>
          azure.endpoint shouldBe "https://contoso-llm.openai.azure.com"
          azure.model shouldBe "gpt-4o"
          azure.tokenProvider should not be empty
        case other => fail(s"expected an Azure section, got $other")

      load(hocon(1), Some("bedrock-private")) match
        case bedrock: BedrockConfig =>
          bedrock.region shouldBe "eu-central-1"
          bedrock.model shouldBe "anthropic.claude-3-5-sonnet-20240620-v1:0"
          bedrock.endpointUrl shouldBe None
          bedrock.credentials shouldBe None
        case other => fail(s"expected a Bedrock section, got $other")
    }
  }

  "the enterprise overlay" should {

    "serve the model the app asks for, and mount the key where the app's section reads it" in {
      """model_name: (\S+)""".r.findAllMatchIn(read(s"$Overlay/litellm-config.yaml")).map(_.group(1)).toList shouldBe
        List("gpt-4o-mini")

      val patch = read(s"$Overlay/deployment-gateway.yaml")
      patch should include("mountPath: /var/run/secrets/llm4s")
      patch should include("secretName: llm4s-gateway-key")
      patch should include("-Dconfig.file=/etc/llm4s/application.conf")
      (patch should not).include("subPath")
      read(s"$Overlay/externalsecret.yaml") should include("secretKey: gateway-api-key")
      // the file the section names is that Secret key, in that mount
      KeyPath shouldBe "/var/run/secrets/llm4s/" + "gateway-api-key"
      squash(read(s"$Overlay/application.conf")) should include(s"""apiKeyFile = "$KeyPath"""")
    }
  }
