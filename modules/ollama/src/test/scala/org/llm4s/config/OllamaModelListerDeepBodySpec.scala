package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.testutil.SmallStack
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A deeply nested or malformed Ollama model listing is a `Left` or a skipped entry, never an exception
 * ([[https://github.com/llm4s/llm4s/issues/1660 #1660]]).
 *
 * The listing used to be read with the unbounded `HttpResponse.toJson` and then throwing accessors
 * (`json("models").arr`, `.obj` on each entry). `ujson.Value.InvalidData`'s message renders the value
 * recursively, so a deep listing overflowed the stack - a `StackOverflowError`, which `Try` does not
 * catch - and an entry that is not an object threw `InvalidData` out of `listModels`. Each call runs on
 * a 256 KiB thread stack, so an overflow is deterministic and fails the test rather than the suite.
 */
class OllamaModelListerDeepBodySpec extends AnyFlatSpec with Matchers {

  private val StackBytes = 256L * 1024L
  private val Depth      = 100000
  private val deepArray  = "[" * Depth + "]" * Depth
  private val deepObject = """{"a":""" * Depth + "1" + "}" * Depth

  private val config = NamedProviderConfig(
    provider = ProviderId("ollama"),
    model = ModelName("m"),
    baseUrl = Some(BaseUrl("http://localhost:11434")),
    apiKey = None
  )

  private def onSmallStack[A](body: => A): A =
    SmallStack.run(body, stackBytes = StackBytes) match {
      case Right(value) => value
      case Left(thrown) => fail(s"the call died on a small stack with ${thrown.getClass.getName}", thrown)
    }

  private def listModels(body: String) =
    onSmallStack(OllamaModelLister.listModels(config, MockHttpClient(HttpResponse(200, body, Map.empty))))

  private def names(body: String) = listModels(body).map(_.map(_.name.asString))

  "OllamaModelLister.listModels" should "return a Left for a 100,000-deep top-level array or object" in {
    listModels(deepArray).isLeft shouldBe true
    listModels(deepObject).isLeft shouldBe true
  }

  it should "return a Left for a 100,000-deep value as models" in {
    listModels(s"""{"models":$deepArray}""").isLeft shouldBe true
  }

  it should "return a Left for a 100,000-deep entry in models" in {
    listModels(s"""{"models":[$deepArray]}""").isLeft shouldBe true
  }

  it should "return a Left for a shallow top-level value that is not an object" in {
    Seq("[1]", "\"x\"", "1", "null").foreach(body => listModels(body).isLeft shouldBe true)
  }

  it should "return a Left for a models that is not an array" in {
    Seq("""{"models":1}""", """{"models":"x"}""", """{"models":{}}""").foreach { body =>
      listModels(body).isLeft shouldBe true
    }
  }

  it should "skip an entry that is not an object, as it skips one without name" in {
    names("""{"models":[[1]]}""") shouldBe Right(Nil)
    names("""{"models":["x"]}""") shouldBe Right(Nil)
    names("""{"models":[1,null,true]}""") shouldBe Right(Nil)
  }

  it should "still list the object entries of a listing with malformed ones among them" in {
    names("""{"models":[{"name":"a"},[1],"x",{"name":"b"}]}""") shouldBe Right(List("a", "b"))
  }
}
