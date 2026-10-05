package org.llm4s.llmconnect.provider

import org.llm4s.config.OpenAICompatibleModelLister
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.AuthenticationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig, TestJwt }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ Callable, Executors }
import scala.jdk.CollectionConverters.*

class OpenAICompatibleWorkloadIdentitySpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  private def sectionOf(body: String): NamedProviderConfig =
    ProviderTestConfig
      .loadSection("main", s"llm4s.providers.main {\n$body\n}")
      .fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  private def svidFile(jwt: String): Path =
    val f = Files.createTempFile("svid", ".jwt")
    f.toFile.deleteOnExit()
    Files.writeString(f, jwt)

  private def authBlock(fake: FakeTokenExchangeServer, svid: Path): String =
    s"""provider = "openai-compatible"
       |model    = "databricks-model"
       |baseUrl  = "${fake.baseUrl}/serving-endpoints"
       |auth {
       |  identityTokenFile = "$svid"
       |  tokenUrl = "${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}"
       |  clientId = "sp-uuid"
       |  scope    = "all-apis"
       |}""".stripMargin

  private def client(fake: FakeTokenExchangeServer, svid: Path): LLMClient =
    assertBuildsClient(OpenAICompatibleProvider, sectionOf(authBlock(fake, svid)))

  private def freshSvid: Path = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "databricks"))

  "an openai-compatible section with auth" should {
    "exchange the SVID and send the access token on complete and stream" in FakeTokenExchangeServer.withServer { fake =>
      val jwt = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val c   = client(fake, svidFile(jwt))
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      assertStreams(c)
      fake.exchanges.head("subject_token") shouldBe jwt
      fake.exchanges.head("client_id") shouldBe "sp-uuid"
      fake.exchanges.head("scope") shouldBe "all-apis"
      fake.apiAuthorizations.distinct shouldBe Seq("Bearer t1")
      fake.exchanges.size shouldBe 1
    }

    "exchange again once the token has expired" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val c = client(fake, freshSvid)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t2")
    }

    "refresh and retry once on a 401" in FakeTokenExchangeServer.withServer { fake =>
      val c = client(fake, freshSvid)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.rejectNextApiCalls(1)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t1", "Bearer t2")
    }

    "refresh and retry once on a 401 while streaming" in FakeTokenExchangeServer.withServer { fake =>
      val c = client(fake, freshSvid)
      fake.rejectNextApiCalls(1)
      val chunks = new AtomicInteger(0)
      c.streamComplete(conversation, CompletionOptions(), _ => chunks.incrementAndGet(): Unit).isRight shouldBe true
      chunks.get should be > 0
      fake.issuedTokens shouldBe Seq("t1", "t2")
    }

    "fail with AuthenticationError after a second 401, having tried exactly twice" in FakeTokenExchangeServer
      .withServer { fake =>
        val c = client(fake, freshSvid)
        fake.rejectNextApiCalls(2)
        c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
        fake.apiAuthorizations.size shouldBe 2
      }

    "surface a rejected exchange as AuthenticationError without calling the API" in FakeTokenExchangeServer
      .withServer { fake =>
        fake.setSubjectValidator(_ => Left("wrong audience"))
        val c = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "other")))
        c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
        fake.apiAuthorizations shouldBe empty
      }

    "make one exchange for concurrent calls" in FakeTokenExchangeServer.withServer { fake =>
      val c    = client(fake, freshSvid)
      val pool = Executors.newVirtualThreadPerTaskExecutor()
      val calls = (1 to 10).map(_ =>
        (() => c.complete(conversation, CompletionOptions()).fold(_.toString, _ => "ok")): Callable[String]
      )
      pool.invokeAll(calls.asJava).asScala.map(_.get()).toList shouldBe List.fill(10)("ok")
      pool.shutdown()
      fake.issuedTokens shouldBe Seq("t1")
    }

    "present the rotated SVID on the next exchange" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val first = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val file  = svidFile(first)
      val c     = client(fake, file)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      val second = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      Files.writeString(file, second)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.exchanges.map(_("subject_token")) shouldBe Seq(first, second)
    }

    "list models with an exchanged token" in FakeTokenExchangeServer.withServer { fake =>
      val section = sectionOf(authBlock(fake, freshSvid))
      val models: Result[List[org.llm4s.config.DiscoveredModel]] =
        OpenAICompatibleModelLister.listModels(section, Llm4sHttpClient.create())
      models.value.map(_.name.toString) shouldBe List("fake-model")
      fake.apiAuthorizations shouldBe Seq("Bearer t1")
    }
  }
