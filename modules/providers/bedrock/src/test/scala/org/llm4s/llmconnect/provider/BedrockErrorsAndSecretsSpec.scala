package org.llm4s.llmconnect.provider

import org.llm4s.error.*
import org.llm4s.llmconnect.config.{ BedrockConfig, BedrockCredentials }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import com.sun.net.httpserver.HttpServer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer
import scala.util.Using

/**
 * Every Bedrock exception the Converse API documents, mapped through the real SDK parser, on both
 * the synchronous and the streaming path; plus credential handling and what must never appear in
 * errors, logs or `toString`.
 */
class BedrockErrorsAndSecretsSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val conv = Conversation(Seq(UserMessage("Hello")))

  private val secretKey  = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYSECRETSECRET"
  private val sessionTok = "FwoGZXIvYXdzSESSIONTOKENVALUE"
  private val withSecrets = (url: String) =>
    config(url).copy(credentials = Some(BedrockCredentials("AKIDEXAMPLE", secretKey, Some(sessionTok))))

  // One server and one client for every error-mapping case: the server answers with whatever
  // `nextError` holds. A client per case cost ~2 s each, because closing a client that has streamed
  // shuts its Netty event loop down gracefully, and that waits out a quiet period.
  private val nextError = new AtomicReference[(Int, String)]((500, "InternalServerException"))
  @volatile private var server: Option[HttpServer]          = None
  @volatile private var sharedClient: Option[BedrockClient] = None

  override def beforeAll(): Unit = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext(
      "/",
      exchange => {
        val (status, errorType) = nextError.get
        sendJsonResponse(exchange, status, errorBody(errorType, "boom"))
      }
    )
    s.start()
    server = Some(s)
  }

  override def afterAll(): Unit =
    Using.resource(new AutoCloseable { override def close(): Unit = server.foreach(_.stop(0)) })(_ =>
      sharedClient.foreach(_.close())
    )

  private def client: BedrockClient = synchronized {
    sharedClient.getOrElse {
      val port = server.getOrElse(fail("server not started")).getAddress.getPort
      val c    = new BedrockClient(config(s"http://127.0.0.1:$port"))
      sharedClient = Some(c)
      c
    }
  }

  private def failWith(status: Int, errorType: String, stream: Boolean): LLMError = {
    nextError.set((status, errorType))
    val result =
      if (stream) client.streamComplete(conv, CompletionOptions(), _ => ())
      else client.complete(conv, CompletionOptions())
    result.left.toOption.value
  }

  "the AWS exception table" should {

    // (status, __type, expected class, expected status when a ServiceError)
    val table: Seq[(Int, String, String)] = Seq(
      (429, "ThrottlingException", "RateLimitError"),
      (400, "ServiceQuotaExceededException", "RateLimitError"),
      (400, "ValidationException", "ValidationError"),
      (403, "AccessDeniedException", "AuthenticationError"),
      (403, "UnrecognizedClientException", "AuthenticationError"),
      (401, "SomethingUnauthorized", "AuthenticationError"),
      (429, "ModelNotReadyException", "ServiceError"),
      (408, "ModelTimeoutException", "ServiceError"),
      (424, "ModelErrorException", "ServiceError"),
      (404, "ResourceNotFoundException", "ServiceError"),
      (500, "InternalServerException", "ServiceError"),
      (503, "ServiceUnavailableException", "ServiceError")
    )

    for {
      stream                        <- Seq(false, true)
      (status, errorType, expected) <- table
    }
      s"map $errorType ($status) to $expected on the ${if (stream) "streaming" else "synchronous"} path" in {
        val err = failWith(status, errorType, stream)
        err.getClass.getSimpleName shouldBe expected
        err match {
          case s: ServiceError => s.httpStatus shouldBe status
          case _               => succeed
        }
      }

    "treat throttling and service errors as recoverable and validation and auth errors as not" in {
      failWith(429, "ThrottlingException", stream = false) shouldBe a[RecoverableError]
      failWith(503, "ServiceUnavailableException", stream = false) shouldBe a[RecoverableError]
      failWith(400, "ValidationException", stream = false) should not be a[RecoverableError]
      failWith(403, "AccessDeniedException", stream = false) should not be a[RecoverableError]
    }

    "map a refused connection to NetworkError, on both paths" in {
      val client = new BedrockClient(config("http://localhost:1"))
      client.complete(conv, CompletionOptions()).left.toOption.value shouldBe a[NetworkError]
      client.streamComplete(conv, CompletionOptions(), _ => ()).left.toOption.value shouldBe a[NetworkError]
      client.close()
    }

    "map an unparseable error body to a returned error rather than throwing" in {
      withServer("/")(sendJsonResponse(_, 502, "<html>bad gateway</html>")) { url =>
        val client = new BedrockClient(config(url))
        client.complete(conv, CompletionOptions()).isLeft shouldBe true
        client.streamComplete(conv, CompletionOptions(), _ => ()).isLeft shouldBe true
        client.close()
      }
    }
  }

  "credentials" should {

    "refuse static credentials combined with a profile, and a blank access key or secret" in {
      given org.llm4s.llmconnect.config.ContextWindowResolver =
        org.llm4s.llmconnect.config.ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())
      val creds = BedrockCredentials("AKID", "secret")
      BedrockConfig.fromValues("m", "us-east-1", Some(creds), Some("p")).isLeft shouldBe true
      BedrockConfig.fromValues("m", "us-east-1", Some(creds.copy(accessKeyId = " "))).isLeft shouldBe true
      BedrockConfig.fromValues("m", "us-east-1", Some(creds.copy(secretAccessKey = ""))).isLeft shouldBe true
      BedrockConfig.fromValues("m", "us-east-1", Some(creds)).isRight shouldBe true
      BedrockConfig.fromValues("m", "us-east-1", None, Some("p")).isRight shouldBe true
      BedrockConfig.fromValues("m", "us-east-1").isRight shouldBe true
    }

    "build through the factory to a Left, not an exception, for an unusable endpoint" in {
      BedrockClient(config("not a url at all")).isLeft shouldBe true
    }
  }

  "secret handling" should {

    "keep the secret key and session token out of toString of the config and the credentials" in {
      val cfg = withSecrets("http://localhost:1")
      Seq(cfg.toString, cfg.credentials.value.toString).foreach { s =>
        (s should not).include(secretKey)
        (s should not).include(sessionTok)
      }
    }

    "keep them out of every error message and out of the logged exchange" in {
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink {
        override def record(exchange: ProviderExchange): Unit = exchanges += exchange
      }
      val errors  = ListBuffer.empty[LLMError]
      val current = new AtomicReference[(Int, String)]((500, "InternalServerException"))
      withServer("/") { ex =>
        val (status, errorType) = current.get
        sendJsonResponse(ex, status, errorBody(errorType, "denied"))
      } { url =>
        // One client for all four errors: closing a client that has streamed costs ~2 s.
        val client = new BedrockClient(withSecrets(url), exchangeLogging = ProviderExchangeLogging.Enabled(sink))
        Seq(
          (403, "AccessDeniedException"),
          (400, "ValidationException"),
          (429, "ThrottlingException"),
          (500, "InternalServerException")
        ).foreach { error =>
          current.set(error)
          client.complete(conv, CompletionOptions()).left.foreach(errors += _)
          client.streamComplete(conv, CompletionOptions(), _ => ()).left.foreach(errors += _)
        }
        client.close()
      }
      errors.size shouldBe 8
      errors.foreach { e =>
        (e.toString should not).include(secretKey)
        (e.toString should not).include(sessionTok)
        (e.message should not).include(secretKey)
      }
      exchanges.foreach { x =>
        (x.toString should not).include(secretKey)
        (x.toString should not).include(sessionTok)
      }
    }

    "send the secret key only as a SigV4 signature, never in a header or body" in {
      val seen = new AtomicReference[String]("")
      withServer("/") { ex =>
        val headers = ex.getRequestHeaders.entrySet().toArray.mkString("\n")
        val body    = new String(ex.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        seen.set(headers + "\n" + body)
        sendJsonResponse(ex, 200, converseResponse("ok"))
      } { url =>
        val client = new BedrockClient(withSecrets(url))
        client.complete(conv, CompletionOptions()).isRight shouldBe true
        client.close()
      }
      (seen.get should not).include(secretKey)
      seen.get should include("AKIDEXAMPLE")
      seen.get should include(sessionTok) // the token travels, as x-amz-security-token, by design
    }
  }
}
