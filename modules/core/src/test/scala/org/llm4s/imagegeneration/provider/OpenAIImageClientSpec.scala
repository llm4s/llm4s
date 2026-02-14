package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Success
import scala.concurrent.ExecutionContext

class OpenAIImageClientSpec extends AnyFunSpec with Matchers with MockFactory with EitherValues with ScalaFutures {

  val apiKey     = "test-api-key"
  val config     = OpenAIConfig(apiKey = apiKey)
  val httpClient = stub[HttpClient]
  val client     = new OpenAIImageClient(config, httpClient)

  describe("OpenAIImageClient") {

    describe("generateImage") {
      it("should successfully generate an image") {
        val prompt = "a white siamese cat"
        val responseJson =
          """
            |{
            |  "created": 1589478378,
            |  "data": [
            |    {
            |      "b64_json": "base64encodedimage..."
            |    }
            |  ]
            |}
            |""".stripMargin.trim

        val response = requests.Response(
          url = "https://api.openai.com/v1/images/generations",
          statusCode = 200,
          statusMessage = "OK",
          data = new geny.Bytes(responseJson.getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.post _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImage(prompt)

        result.value.data shouldBe "base64encodedimage..."
        result.value.prompt shouldBe prompt
      }

      it("should handle error response (401 Unauthorized)") {
        val prompt = "test"
        val response = requests.Response(
          url = "https://api.openai.com/v1/images/generations",
          statusCode = 401,
          statusMessage = "Unauthorized",
          data = new geny.Bytes("""{"error": {"message": "Invalid API key"}}""".getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.post _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImage(prompt)

        result.left.value shouldBe a[AuthenticationError]
        result.left.value.message shouldBe "Invalid API key"
      }

      it("should handle error response (429 Rate Limit)") {
        val prompt = "test"
        val response = requests.Response(
          url = "https://api.openai.com/v1/images/generations",
          statusCode = 429,
          statusMessage = "Too Many Requests",
          data = new geny.Bytes("""{"error": {"message": "Rate limit exceeded"}}""".getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.post _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImage(prompt)

        result.left.value shouldBe a[RateLimitError]
      }
    }

    describe("generateImages") {
      it("should successfully generate multiple images") {
        val prompt = "a white siamese cat"
        val count  = 2
        val responseJson =
          """
            |{
            |  "created": 1589478378,
            |  "data": [
            |    { "b64_json": "img1..." },
            |    { "b64_json": "img2..." }
            |  ]
            |}
            |""".stripMargin.trim

        val response = requests.Response(
          url = "https://api.openai.com/v1/images/generations",
          statusCode = 200,
          statusMessage = "OK",
          data = new geny.Bytes(responseJson.getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.post _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImages(prompt, count)

        (result.value should have).length(2)
        result.value.head.data shouldBe "img1..."
        result.value(1).data shouldBe "img2..."
      }

      it("should validate count limits") {
        val result = client.generateImages("test", 11)
        result.left.value shouldBe a[ValidationError]
      }
    }

    describe("health") {
      it("should return Healthy when API responds with 200") {
        val response = requests.Response(
          url = "https://api.openai.com/v1/models",
          statusCode = 200,
          statusMessage = "OK",
          data = new geny.Bytes("{}".getBytes),
          headers = Map.empty,
          history = None
        )
        (httpClient.get _).when(*, *, *).returns(Success(response))

        val result = client.health()
        result.value.status shouldBe HealthStatus.Healthy
      }

      it("should return Degraded when API responds with 429") {
        val response = requests.Response(
          url = "https://api.openai.com/v1/models",
          statusCode = 429,
          statusMessage = "Too Many Requests",
          data = new geny.Bytes("{}".getBytes),
          headers = Map.empty,
          history = None
        )
        (httpClient.get _).when(*, *, *).returns(Success(response))

        val result = client.health()
        result.value.status shouldBe HealthStatus.Degraded
      }
    }

    describe("generateImageAsync") {
      it("should return a Future with the result") {
        val prompt = "async test"
        val responseJson =
          """
            |{
            |  "created": 1589478378,
            |  "data": [ { "b64_json": "async_img..." } ]
            |}
            |""".stripMargin.trim

        val response = requests.Response(
          url = "https://api.openai.com/v1/images/generations",
          statusCode = 200,
          statusMessage = "OK",
          data = new geny.Bytes(responseJson.getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.post _).when(*, *, *, *).returns(Success(response))

        implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
        val futureResult                  = client.generateImageAsync(prompt)

        whenReady(futureResult)(result => result.value.data shouldBe "async_img...")
      }
    }
  }
}
