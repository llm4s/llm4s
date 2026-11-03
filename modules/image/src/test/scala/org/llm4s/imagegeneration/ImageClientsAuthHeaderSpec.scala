package org.llm4s.imagegeneration

import org.llm4s.http.{ HttpRawResponse, HttpResponse, MultipartPart }
import org.llm4s.imagegeneration.provider.*
import org.llm4s.security.{ KnownSecrets, Secret }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Try }

/** The configured `Secret` key, not its masked `***`, is what each image-generation client sends. */
class ImageClientsAuthHeaderSpec extends AnyFlatSpec with Matchers {

  private val apiKeyValue = "img-key-0123456789abcdef"

  private object Recording extends HttpClient {
    val seen = new ConcurrentLinkedQueue[Map[String, String]]()
    private def record[A](headers: Map[String, String]): Try[A] = {
      seen.add(headers)
      Failure(new RuntimeException("stop"))
    }
    def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration): Try[HttpResponse] =
      record(headers)
    def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = record(headers)
    def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = record(headers)
    def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] = record(headers)
    def postRaw(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpRawResponse] = record(headers)
  }

  "each image-generation client" should "send the real key in Authorization" in KnownSecrets.isolated {
    val s = Secret(apiKeyValue)
    Seq[ImageGenerationClient](
      new OpenAIImageClient(OpenAIConfig(apiKey = s), Recording),
      new StabilityAIClient(StabilityAIConfig(apiKey = s), Recording),
      new HuggingFaceClient(HuggingFaceConfig(apiKey = s), Recording),
      new StableDiffusionClient(StableDiffusionConfig(apiKey = Some(s)), Recording)
    ).foreach { client =>
      Recording.seen.clear()
      client.generateImage("a cat"): Unit
      val auth = Recording.seen.asScala.flatMap(_.get("Authorization")).toSeq
      withClue(client.getClass.getSimpleName + ": ")(auth should contain(s"Bearer $apiKeyValue"))
    }
  }
}
