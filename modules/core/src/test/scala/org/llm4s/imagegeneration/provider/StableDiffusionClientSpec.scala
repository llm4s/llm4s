package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Success

class StableDiffusionClientSpec extends AnyFunSpec with Matchers with MockFactory with EitherValues with ScalaFutures {

  val baseUrl    = "http://localhost:7860"
  val config     = StableDiffusionConfig(baseUrl = baseUrl)
  val httpClient = stub[HttpClient]
  val client     = new StableDiffusionClient(config, httpClient)

  describe("StableDiffusionClient") {

    describe("generateImage") {
      it("should successfully generate an image") {
        val prompt = "a beautiful landscape"
        val responseJson =
          """
            |{
            |  "images": [
            |    "base64encodedimage..."
            |  ]
            |}
            |""".stripMargin.trim

        val response = requests.Response(
          url = "http://localhost:7860/sdapi/v1/txt2img",
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

      it("should handle error response (500 Server Error)") {
        val prompt = "test"
        val response = requests.Response(
          url = "http://localhost:7860/sdapi/v1/txt2img",
          statusCode = 500,
          statusMessage = "Internal Server Error",
          data = new geny.Bytes("""{"error": "Something went wrong"}""".getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.post _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImage(prompt)

        result.left.value shouldBe a[ServiceError]
        result.left.value.asInstanceOf[ServiceError].code shouldBe 500
      }
    }

    describe("generateImages") {
      it("should successfully generate multiple images") {
        val prompt = "a beautiful landscape"
        val count  = 2
        val responseJson =
          """
            |{
            |  "images": [
            |    "base64encodedimage1...",
            |    "base64encodedimage2..."
            |  ]
            |}
            |""".stripMargin.trim

        val response = requests.Response(
          url = "http://localhost:7860/sdapi/v1/txt2img",
          statusCode = 200,
          statusMessage = "OK",
          data = new geny.Bytes(responseJson.getBytes),
          headers = Map.empty,
          history = None
        )

        (httpClient.post _).when(*, *, *, *).returns(Success(response))

        val result = client.generateImages(prompt, count)
        (result.value should have).length(2)
        result.value.head.data shouldBe "base64encodedimage1..."
        result.value(1).data shouldBe "base64encodedimage2..."
      }
    }

    describe("health") {
      it("should return Healthy when API responds with 200") {
        val response = requests.Response(
          url = "http://localhost:7860/sdapi/v1/options",
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

      it("should return Degraded when API responds with 500") {
        val response = requests.Response(
          url = "http://localhost:7860/sdapi/v1/options",
          statusCode = 500,
          statusMessage = "Internal Server Error",
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
