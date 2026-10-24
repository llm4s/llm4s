package org.llm4s.error

import org.llm4s.error.TroubleshootingLinks.Entry
import org.llm4s.llmconnect.provider.HttpErrorMapper
import org.llm4s.testutil.TroubleshootingPage
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * The links from error messages to the First-Run Troubleshooting page (#2285): every link id used in code has a
 * matching anchor on the page, so renaming a heading cannot break a link, and the runtime errors say their next
 * step and link the entry whose symptom shows that exact text.
 */
class TroubleshootingLinksSpec extends AnyWordSpec with Matchers:

  private val Page = TroubleshootingLinks.PageUrl

  "Every troubleshooting entry used in code" should {
    "have a matching {#id} anchor on the page" in {
      val missing = Entry.values.map(_.id).filterNot(TroubleshootingPage.anchors.contains)
      withClue(s"${TroubleshootingLinks.PageSource} has no {#id} heading for: ${missing.mkString(", ")}\n") {
        missing shouldBe empty
      }
    }

    "be shown with its own link in that entry's symptom" in
      Entry.values.foreach { entry =>
        withClue(s"entry ${entry.id}: ") {
          TroubleshootingPage.section(entry) should include(TroubleshootingLinks.forId(entry))
        }
      }

    "have a distinct id" in {
      Entry.values.map(_.id).distinct should have size Entry.values.length.toLong
    }
  }

  "The page URL" should {
    "be the published address of the page source, with no trailing slash or fragment" in {
      val path = TroubleshootingLinks.PageSource.stripPrefix("docs").stripSuffix(".md")
      Page shouldBe s"https://llm4s.org$path"
    }

    "be written in no main source but TroubleshootingLinks, so a docs move is a one-line change" in {
      val modules = TroubleshootingPage.root.resolve("modules")
      val offenders = Using.resource(Files.walk(modules)) { paths =>
        paths.iterator.asScala
          .filter(p => Files.isRegularFile(p) && isMainSource(p))
          .filterNot(_.getFileName.toString == "TroubleshootingLinks.scala")
          .filter(p =>
            new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("getting-started/troubleshooting")
          )
          .map(p => TroubleshootingPage.root.relativize(p).toString)
          .toList
      }
      offenders shouldBe empty
    }
  }

  "A configuration error's message" should {
    "end with the entry's link and the doctor's command from docs/reference/doctor.md" in {
      val message = TroubleshootingLinks.configuration("No default provider", Entry.NoDefaultProvider)
      message shouldBe
        s"No default provider\nSee $Page#no-default-provider\n${TroubleshootingLinks.DoctorLine}"
      val doctorDoc = new String(
        Files.readAllBytes(TroubleshootingPage.root.resolve("docs/reference/doctor.md")),
        StandardCharsets.UTF_8
      )
      doctorDoc should include(TroubleshootingLinks.DoctorCommand.stripSuffix("\""))
    }

    "lose only the doctor line when the doctor shows it" in {
      val message = TroubleshootingLinks.configuration("first\nsecond", Entry.ProviderNotFound)
      TroubleshootingLinks.withoutDoctorLine(message) shouldBe s"first\nsecond\nSee $Page#provider-not-found"
    }
  }

  "A provider's HTTP error" should {
    def mapped(status: Int, body: String, headers: Map[String, Seq[String]] = Map.empty): LLMError =
      HttpErrorMapper.mapHttpError(status, body, "openai", headers).left.toOption.get

    "on a 401 or 403, say to check the key's project or organisation, and link the entry" in
      Seq(401, 403).foreach { status =>
        val error = mapped(status, """{"error":{"message":"Incorrect API key provided"}}""")
        error shouldBe an[AuthenticationError]
        error.message shouldBe
          "Authentication failed for openai: Incorrect API key provided. " +
          s"${TroubleshootingLinks.AuthenticationAction} See $Page#authentication-failed"
      }

    "on a 429, say to lower concurrency or use ReliableClient, keeping the Retry-After" in {
      val error = mapped(429, "{}", Map("Retry-After" -> Seq("20")))
      error shouldBe a[RateLimitError]
      error.asInstanceOf[RateLimitError].retryAfter shouldBe Some(20.seconds)
      error.message shouldBe
        s"Rate limited by openai. Retry after 20s. ${TroubleshootingLinks.RateLimitAction} See $Page#rate-limited"
      mapped(429, "{}").message shouldBe
        s"Rate limited by openai. ${TroubleshootingLinks.RateLimitAction} See $Page#rate-limited"
    }

    "on a 404, say to check the model and list the provider's models" in {
      val error = mapped(404, """{"error":{"message":"The model `nope` does not exist"}}""")
      error shouldBe a[ServiceError]
      error.message shouldBe
        "Service error from openai: The model `nope` does not exist (HTTP 404). " +
        s"${TroubleshootingLinks.ModelNotFoundAction} See $Page#model-not-found"
    }

    "on any other status, link nothing" in
      Seq(400, 500, 503).foreach(status => (mapped(status, "{}").message should not).include(Page))
  }

  "annotate" should {
    "add the next step once, however often it is applied" in {
      val once = TroubleshootingLinks.annotate(AuthenticationError("anthropic", "invalid x-api-key"))
      TroubleshootingLinks.annotate(once) shouldBe once
      once.message.split(java.util.regex.Pattern.quote(Page), -1).length shouldBe 2
    }

    "treat a ServiceError with 401 or 403 as an authentication failure" in {
      TroubleshootingLinks.annotate(ServiceError(403, "anthropic", "forbidden")).message should include(
        s"${TroubleshootingLinks.AuthenticationAction} See $Page#authentication-failed"
      )
    }

    "leave a local throttle and other errors alone" in {
      val local = RateLimitError.local("p")
      TroubleshootingLinks.annotate(local) shouldBe local
      val validation = ValidationError("request", "bad")
      TroubleshootingLinks.annotate(validation) shouldBe validation
    }

    "keep each error's type and fields" in {
      val auth = TroubleshootingLinks.annotate(AuthenticationError("openai", "nope", "401"))
      auth.code shouldBe Some("401")
      val service = TroubleshootingLinks.annotate(ServiceError(404, "openai", "missing"))
      service.asInstanceOf[ServiceError].httpStatus shouldBe 404
      LLMError.isRecoverable(service) shouldBe LLMError.isRecoverable(ServiceError(404, "openai", "missing"))
    }
  }

  "The page's symptoms" should {
    "show each runtime error's next step as the code words it" in {
      TroubleshootingPage.section(Entry.AuthenticationFailed) should include(TroubleshootingLinks.AuthenticationAction)
      TroubleshootingPage.section(Entry.RateLimited) should include(TroubleshootingLinks.RateLimitAction)
      TroubleshootingPage.section(Entry.ModelNotFound) should include(TroubleshootingLinks.ModelNotFoundAction)
      TroubleshootingPage.section(Entry.OllamaNotRunning) should include(TroubleshootingLinks.OllamaNotRunningAction)
      TroubleshootingPage.section(Entry.OllamaModelMissing) should include(
        TroubleshootingLinks.ollamaModelMissingAction("llama3")
      )
    }

    "show the doctor line under each configuration entry" in
      Seq(
        Entry.MissingApiKey,
        Entry.NoDefaultProvider,
        Entry.ProviderNotFound,
        Entry.ProviderNotRegistered,
        Entry.ApplicationConf
      ).foreach(entry => TroubleshootingPage.section(entry) should include(TroubleshootingLinks.DoctorLine))
  }

  private def isMainSource(path: Path): Boolean =
    val text = path.toString.replace('\\', '/')
    text.contains("/src/main/") && (text.endsWith(".scala") || text.endsWith(".java") || text.endsWith(".kt"))
