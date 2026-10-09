package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.testutil.SmallStack
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A deeply nested or malformed Anthropic model listing is a `Left` or a skipped entry, never an exception
 * ([[https://github.com/llm4s/llm4s/issues/1660 #1660]]).
 *
 * The listing used to be read with the unbounded `HttpResponse.toJson` and then throwing accessors
 * (`json("data").arr`, `.obj` on each entry). `ujson.Value.InvalidData`'s message renders the value
 * recursively, so a deep listing overflowed the stack - a `StackOverflowError`, which `Try` does not
 * catch - and an entry that is not an object threw `InvalidData` out of `listModels`. Each call runs on
 * a 256 KiB thread stack, so an overflow is deterministic and fails the test rather than the suite.
 */
class AnthropicModelListerDeepBodySpec extends AnyFlatSpec with Matchers {

  private val StackBytes = 256L * 1024L
  private val Depth      = 100000
  private val deepArray  = "[" * Depth + "]" * Depth
  private val deepObject = """{"a":""" * Depth + "1" + "}" * Depth

  private val config = NamedProviderConfig(
    provider = ProviderId("anthropic"),
    model = ModelName("m"),
    baseUrl = None,
    apiKey = Some(ApiKey("k"))
  )

  private def onSmallStack[A](body: => A): A =
    SmallStack.run(body, stackBytes = StackBytes) match {
      case Right(value) => value
      case Left(thrown) => fail(s"the call died on a small stack with ${thrown.getClass.getName}", thrown)
    }

  private def listModels(body: String) =
    onSmallStack(AnthropicModelLister.listModels(config, MockHttpClient(HttpResponse(200, body, Map.empty))))

  private def names(body: String) = listModels(body).map(_.map(_.name.asString))

  "AnthropicModelLister.listModels" should "return a Left for a 100,000-deep top-level array or object" in {
    listModels(deepArray).isLeft shouldBe true
    listModels(deepObject).isLeft shouldBe true
  }

  it should "return a Left for a 100,000-deep value as data" in {
    listModels(s"""{"data":$deepArray}""").isLeft shouldBe true
  }

  it should "return a Left for a 100,000-deep entry in data" in {
    listModels(s"""{"data":[$deepArray]}""").isLeft shouldBe true
  }

  it should "return a Left for a shallow top-level value that is not an object" in {
    Seq("[1]", "\"x\"", "1", "null").foreach(body => listModels(body).isLeft shouldBe true)
  }

  it should "return a Left for a data that is not an array" in {
    Seq("""{"data":1}""", """{"data":"x"}""", """{"data":{}}""").foreach { body =>
      listModels(body).isLeft shouldBe true
    }
  }

  it should "skip an entry that is not an object, as it skips one without id" in {
    names("""{"data":[[1]]}""") shouldBe Right(Nil)
    names("""{"data":["x"]}""") shouldBe Right(Nil)
    names("""{"data":[1,null,true]}""") shouldBe Right(Nil)
  }

  it should "still list the object entries of a listing with malformed ones among them" in {
    names("""{"data":[{"id":"a"},[1],"x",{"id":"b"}]}""") shouldBe Right(List("a", "b"))
  }
}
