package org.llm4s.samples.cookbook

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, ResponseFormat }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the email-extraction recipe against scripted replies: the good one, and the ways a reply goes wrong. */
class EmailExtractionRecipeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def replying(text: String): ScriptedClient = new ScriptedClient((_, _) => Right(AssistantMessage(text)))

  private val email = EmailExtractionRecipe.email

  "EmailExtractionRecipe.extract" should "read the reply into an OrderRequest with every line item" in {
    EmailExtractionRecipe.extract(EmailExtractionRecipe.script, email).value shouldBe OrderRequest(
      customer = "Harbour Cafe",
      orderNumber = "HC-2291",
      items = Seq(LineItem("house blend", 12), LineItem("oat milk", 3)),
      deliverBy = "2026-11-14"
    )
  }

  it should "send the email, an instruction and the schema with its nested item list" in {
    val client = EmailExtractionRecipe.script

    EmailExtractionRecipe.extract(client, email).value

    val (conversation, options) = client.calls.head
    conversation.messages.map(_.content) should contain(email)
    options.responseFormat.value shouldBe a[ResponseFormat.JsonSchema]
    val items = EmailExtractionRecipe.orderSchema.toJsonSchema(strict = true)("properties")("items")
    items("type").str shouldBe "array"
    items("items")("properties").obj.keySet shouldBe Set("product", "quantity")
  }

  it should "refuse an order number that is not in the email, which a model can invent" in {
    val invented = """{"customer": "Harbour Cafe", "orderNumber": "HC-9999", "deliverBy": "",
                     |"items": [{"product": "house blend", "quantity": 12}]}""".stripMargin

    val error = EmailExtractionRecipe.extract(replying(invented), email).left.value

    error shouldBe a[ValidationError]
    error.message should include("HC-9999")
  }

  it should "refuse an extraction with no line items" in {
    val empty = """{"customer": "Harbour Cafe", "orderNumber": "HC-2291", "deliverBy": "", "items": []}"""

    EmailExtractionRecipe.extract(replying(empty), email).left.value.message should include("items")
  }

  it should "fail with a ValidationError when a line item has the wrong shape" in {
    val wrong = """{"customer": "Harbour Cafe", "orderNumber": "HC-2291", "deliverBy": "",
                  |"items": [{"product": "house blend", "quantity": "twelve"}]}""".stripMargin

    EmailExtractionRecipe.extract(replying(wrong), email).left.value shouldBe a[ValidationError]
  }

  "EmailExtractionRecipe.demo" should "print the order" in {
    EmailExtractionRecipe.demo(EmailExtractionRecipe.script).value should include("12 x house blend, 3 x oat milk")
  }
}
