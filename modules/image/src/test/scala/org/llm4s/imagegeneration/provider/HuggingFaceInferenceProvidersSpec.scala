package org.llm4s.imagegeneration.provider

import org.llm4s.http.{ HttpRawResponse, HttpResponse, MultipartPart }
import org.llm4s.imagegeneration._
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try }

/**
 * The request `HuggingFaceClient` sends, against Hugging Face's documented Inference Providers format
 * (https://huggingface.co/docs/inference-providers/tasks/text-to-image): a POST to
 * `https://router.huggingface.co/hf-inference/models/<model>` with `inputs` and `parameters`, answered with the image's
 * raw bytes; and the health check against the Hub API's provider mapping
 * (https://huggingface.co/docs/inference-providers/hub-api#get-model-providers).
 */
class HuggingFaceInferenceProvidersSpec extends AnyFlatSpec with Matchers with EitherValues {

  final private case class Call(method: String, url: String, headers: Map[String, String], body: String)

  final private class Recording(rawResponse: HttpRawResponse, getResponse: HttpResponse) extends HttpClient {
    val calls: mutable.Buffer[Call] = mutable.Buffer.empty

    def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration): Try[HttpResponse] =
      Failure(new UnsupportedOperationException("post"))
    def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = Failure(new UnsupportedOperationException("postBytes"))
    def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = Failure(new UnsupportedOperationException("postMultipart"))
    def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] = {
      calls += Call("GET", url, headers, "")
      Success(getResponse)
    }
    def postRaw(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpRawResponse] = {
      calls += Call("POST", url, headers, data)
      Success(rawResponse)
    }
  }

  private val pngBytes = Array[Byte](0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte)

  private def recording(get: HttpResponse = HttpResponse(200, "{}")): Recording =
    new Recording(HttpRawResponse(200, pngBytes), get)

  "HuggingFaceConfig" should "default to the hf-inference route on the router and a model hf-inference serves" in {
    val config = HuggingFaceConfig(apiKey = Secret("hf_test"))
    config.baseUrl shouldBe "https://router.huggingface.co/hf-inference"
    config.model shouldBe "stabilityai/stable-diffusion-3-medium-diffusers"
  }

  "HuggingFaceClient.generateImage" should "POST to the router's hf-inference route for the model" in {
    val http   = recording()
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("hf_test")), http)

    client.generateImage("a red fox").isRight shouldBe true

    val call = http.calls.loneElement
    call.method shouldBe "POST"
    call.url shouldBe "https://router.huggingface.co/hf-inference/models/stabilityai/stable-diffusion-3-medium-diffusers"
    call.headers("Authorization") shouldBe "Bearer hf_test"
    call.headers("Content-Type") shouldBe "application/json"
    (call.url should not).include("api-inference.huggingface.co")
  }

  it should "send the documented text-to-image body: inputs and the parameters' documented names" in {
    val http   = recording()
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("hf_test")), http)
    val options = ImageGenerationOptions(
      size = ImageSize.Square1024,
      seed = Some(7L),
      guidanceScale = 6.5,
      inferenceSteps = 28,
      negativePrompt = Some("blurry")
    )

    client.generateImage("a red fox", options).isRight shouldBe true

    ujson.read(http.calls.loneElement.body) shouldBe ujson.Obj(
      "inputs" -> "a red fox",
      "parameters" -> ujson.Obj(
        "guidance_scale"      -> 6.5,
        "num_inference_steps" -> 28,
        "negative_prompt"     -> "blurry",
        "width"               -> 1024,
        "height"              -> 1024,
        "seed"                -> 7
      )
    )
  }

  it should "leave absent optional parameters out of the body instead of sending null" in {
    val http   = recording()
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("hf_test")), http)

    client.generateImage("a red fox", ImageGenerationOptions(size = ImageSize.Auto)).isRight shouldBe true

    val parameters = ujson.read(http.calls.loneElement.body)("parameters").obj
    parameters.keySet shouldBe Set("guidance_scale", "num_inference_steps")
  }

  it should "return the response's raw bytes as the image, base64-encoded" in {
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("hf_test")), recording())

    val image = client.generateImage("a red fox").value
    java.util.Base64.getDecoder.decode(image.data) shouldBe pngBytes
  }

  it should "use a configured base URL, with or without a trailing slash" in {
    Seq("http://localhost:8080/hf-inference", "http://localhost:8080/hf-inference/").foreach { base =>
      val http = recording()
      val config =
        HuggingFaceConfig(apiKey = Secret("hf_test"), model = "acme/diffusion", baseUrl = base)
      new HuggingFaceClient(config, http).generateImage("p").isRight shouldBe true
      http.calls.loneElement.url shouldBe "http://localhost:8080/hf-inference/models/acme/diffusion"
    }
  }

  "HuggingFaceClient.health" should "read the model's provider mapping from the Hub API" in {
    val http   = recording()
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("hf_test")), http)

    client.health(): Unit

    val call = http.calls.loneElement
    call.method shouldBe "GET"
    call.url shouldBe
      "https://huggingface.co/api/models/stabilityai/stable-diffusion-3-medium-diffusers?expand[]=inferenceProviderMapping"
    call.headers("Authorization") shouldBe "Bearer hf_test"
  }

  it should "be healthy when hf-inference serves the model live" in {
    val body =
      """{"id":"m","inferenceProviderMapping":{"hf-inference":{"status":"live","providerId":"m","task":"text-to-image"}}}"""
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("k")), recording(HttpResponse(200, body)))

    client.health().value.status shouldBe HealthStatus.Healthy
  }

  it should "read the array form of the mapping as well" in {
    val body =
      """{"id":"m","inferenceProviderMapping":[{"provider":"hf-inference","status":"live","task":"text-to-image"}]}"""
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("k")), recording(HttpResponse(200, body)))

    client.health().value.status shouldBe HealthStatus.Healthy
  }

  it should "be degraded when only a third-party provider serves the model" in {
    val body   = """{"id":"m","inferenceProviderMapping":{"fal-ai":{"status":"live","task":"text-to-image"}}}"""
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("k")), recording(HttpResponse(200, body)))

    val status = client.health().value
    status.status shouldBe HealthStatus.Degraded
    status.message should include("does not serve")
  }

  it should "be degraded when hf-inference lists the model as staging" in {
    val body   = """{"id":"m","inferenceProviderMapping":{"hf-inference":{"status":"staging"}}}"""
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("k")), recording(HttpResponse(200, body)))

    client.health().value.status shouldBe HealthStatus.Degraded
  }

  it should "be degraded when the Hub answers with an error status" in {
    val client = new HuggingFaceClient(
      HuggingFaceConfig(apiKey = Secret("k")),
      recording(HttpResponse(404, """{"error":"Repository not found"}"""))
    )

    client.health().value.status shouldBe HealthStatus.Degraded
  }

  it should "be degraded when the Hub's body is not JSON" in {
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret("k")), recording(HttpResponse(200, "<html>")))

    client.health().value.status shouldBe HealthStatus.Degraded
  }

  extension [A](buffer: mutable.Buffer[A]) {
    private def loneElement: A = {
      buffer should have size 1
      buffer.head
    }
  }
}
