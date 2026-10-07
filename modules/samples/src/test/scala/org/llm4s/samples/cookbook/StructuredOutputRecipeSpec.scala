package org.llm4s.samples.cookbook

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, ResponseFormat }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the structured-output recipe against scripted replies: the good one, and the ways a reply goes wrong. */
class StructuredOutputRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def replying(text: String): ScriptedClient = new ScriptedClient((_, _) => Right(AssistantMessage(text)))

  private val good = """{"category": "billing", "urgency": 4, "summary": "Charged twice."}"""

  "StructuredOutputRecipe.classify" should "read the reply into a Ticket" in {
    StructuredOutputRecipe.classify(replying(good), "charged twice").value shouldBe
      Ticket("billing", 4, "Charged twice.")
  }

  it should "read a reply that the model wrapped in a code fence" in {
    val fenced = s"Here is the ticket:\n```json\n$good\n```"

    StructuredOutputRecipe.classify(replying(fenced), "charged twice").value.category shouldBe "billing"
  }

  it should "fail with a ValidationError when the reply is not JSON" in {
    StructuredOutputRecipe.classify(replying("Sorry, I cannot help with that."), "x").left.value shouldBe
      a[ValidationError]
  }

  it should "fail when a field has the wrong type" in {
    val wrongType = """{"category": "billing", "urgency": "very", "summary": "Charged twice."}"""

    StructuredOutputRecipe.classify(replying(wrongType), "x").left.value shouldBe a[ValidationError]
  }

  it should "fail when a required field is missing" in {
    val missing = """{"category": "billing", "urgency": 4}"""

    StructuredOutputRecipe.classify(replying(missing), "x").left.value shouldBe a[ValidationError]
  }

  it should "refuse a category outside the enum, which a provider that ignores the schema can still produce" in {
    val unknown = """{"category": "complaint", "urgency": 4, "summary": "Charged twice."}"""

    val error = StructuredOutputRecipe.classify(replying(unknown), "x").left.value

    error shouldBe a[ValidationError]
    error.message should include("category")
  }

  it should "send the schema with the request, with the categories as an enum" in {
    val client = replying(good)

    StructuredOutputRecipe.classify(client, "charged twice").value

    val options = client.calls.head._2
    val format  = options.responseFormat.getOrElse(fail("no response format was sent"))
    format shouldBe a[ResponseFormat.JsonSchema]
    val sentSchema = StructuredOutputRecipe.ticketSchema.toJsonSchema(strict = true)
    sentSchema("properties")("category")("enum").arr.map(_.str).toSeq shouldBe StructuredOutputRecipe.categories
  }

  "StructuredOutputRecipe.demo" should "print the ticket the scripted model produced" in {
    StructuredOutputRecipe.demo(StructuredOutputRecipe.script).value should include("category=billing")
  }
}
