package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Success

class StabilityAIClientSpec extends AnyFunSpec with Matchers with MockFactory with EitherValues with ScalaFutures {

  val apiKey     = "test-api-key"
  val config     = StabilityAIConfig(apiKey = apiKey)
  val httpClient = stub[HttpClient]
  val client     = new StabilityAIClient(config, httpClient)

  describe("StabilityAIClient") {

    describe("generateImage") {
      it("should successfully generate an image") {
        val prompt = "a futuristic city"
        val responseJson =
          """
            |{
            |  "image": "base64encodedimage..."
            |}
            |""".stripMargin.trim

        val response = requests.Response(
          url = "https://api.stability.ai/v2beta/stable-image/generate/ultra",
          statusCode = 200,
          statusMessage = "OK",
          data = new geny.Bytes(responseJson.getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.postMultipart _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImage(prompt)

        result.value.data shouldBe "base64encodedimage..."
        result.value.prompt shouldBe prompt
      }

      it("should handle error response (401 Unauthorized)") {
        val prompt = "test"
        val response = requests.Response(
          url = "https://api.stability.ai/v2beta/stable-image/generate/ultra",
          statusCode = 401,
          statusMessage = "Unauthorized",
          data = new geny.Bytes("""{"message": "Invalid API key"}""".getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.postMultipart _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImage(prompt)

        result.left.value shouldBe a[AuthenticationError]
        result.left.value.message shouldBe "Invalid or missing Stability AI API key"
      }

      it("should handle error response (500 Server Error)") {
        val prompt = "test"
        val response = requests.Response(
          url = "https://api.stability.ai/v2beta/stable-image/generate/ultra",
          statusCode = 500,
          statusMessage = "Internal Server Error",
          data = new geny.Bytes("""{"message": "Something went wrong"}""".getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.postMultipart _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImage(prompt)

        result.left.value shouldBe a[ServiceError]
        result.left.value.asInstanceOf[ServiceError].code shouldBe 500
      }
    }

    describe("generateImages") {
      it("should successfully generate multiple images sequentially") {
        val prompt = "a futuristic city"
        val count  = 2
        val responseJson =
          """
            |{
            |  "image": "base64encodedimage..."
            |}
            |""".stripMargin.trim

        val response = requests.Response(
          url = "https://api.stability.ai/v2beta/stable-image/generate/ultra",
          statusCode = 200,
          statusMessage = "OK",
          data = new geny.Bytes(responseJson.getBytes),
          headers = Map.empty,
          history = None
        )

        // It should be called twice for count = 2
        (httpClient.postMultipart _).when(*, *, *, *).returns(Success(response)).twice()

        val result = client.generateImages(prompt, count)

        (result.value should have).length(2)
      }

      it("should fail fast if one request fails") {
        val prompt = "a futuristic city"
        val count  = 2

        val errorResponse = requests.Response(
          url = "https://api.stability.ai/v2beta/stable-image/generate/ultra",
          statusCode = 500,
          statusMessage = "Error",
          data = new geny.Bytes("{}".getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.postMultipart _).when(*, *, *, *).returns(Success(errorResponse))

        val result = client.generateImages(prompt, count)
        result.left.value shouldBe a[ServiceError]
      }
    }

    describe("health") {
      it("should return Healthy when API responds with 200") {
        val response = requests.Response(
          url = "https://api.stability.ai/v1/user/account",
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

      it("should return Degraded when API responds with 401") {
        val response = requests.Response(
          url = "https://api.stability.ai/v1/user/account",
          statusCode = 401,
          statusMessage = "Unauthorized",
          data = new geny.Bytes("{}".getBytes),
          headers = Map.empty,
          history = None
        )
        (httpClient.get _).when(*, *, *).returns(Success(response))

        val result = client.health()
        result.value.status shouldBe HealthStatus.Degraded
      }
    }
  }
}
