package org.llm4s.llmconnect.provider

import org.llm4s.context.tokens.TokenizerId
import org.llm4s.error.TokenizerError
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpenAITokenizersSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def tokenizer(model: String) = OpenAITokenizers.tokenizerFor(model)

  "OpenAITokenizers.tokenizerFor" should "map the o200k_base models" in {
    Seq(
      "gpt-4o",
      "gpt-4o-mini",
      "chatgpt-4o-latest",
      "gpt-4.1",
      "gpt-4.1-mini",
      "gpt-4.5-preview",
      "gpt-5",
      "gpt-5-mini",
      "o1",
      "o1-mini",
      "o3",
      "o3-mini",
      "o4-mini"
    ).foreach(model => withClue(model)(tokenizer(model) shouldBe Some(TokenizerId.O200K_BASE)))
  }

  it should "map the cl100k_base models" in {
    Seq("gpt-4", "gpt-4-turbo", "gpt-4-0613", "gpt-3.5-turbo", "gpt-35-turbo", "text-embedding-3-small")
      .foreach(model => withClue(model)(tokenizer(model) shouldBe Some(TokenizerId.CL100K_BASE)))
  }

  it should "map a legacy gpt-3 name to r50k_base" in {
    tokenizer("gpt-3") shouldBe Some(TokenizerId.R50K_BASE)
  }

  it should "accept the openai/ and azure/ prefixes, in any case" in {
    tokenizer("openai/gpt-4o") shouldBe Some(TokenizerId.O200K_BASE)
    tokenizer("OpenAI/GPT-4") shouldBe Some(TokenizerId.CL100K_BASE)
    tokenizer("azure/o3-mini") shouldBe Some(TokenizerId.O200K_BASE)
  }

  it should "match an Azure deployment on the model name it contains" in {
    tokenizer("azure/my-gpt-4o-deployment") shouldBe Some(TokenizerId.O200K_BASE)
    tokenizer("azure/prod-gpt-4-turbo") shouldBe Some(TokenizerId.CL100K_BASE)
    tokenizer("azure/team-o3-east") shouldBe Some(TokenizerId.O200K_BASE)
    tokenizer("azure/my-deployment") shouldBe None
  }

  it should "not read an o-series model into a word that merely contains its letters" in {
    tokenizer("azure/foo1-bar") shouldBe None
    tokenizer("azure/turbo3") shouldBe None
  }

  it should "recognise no model of another vendor" in {
    Seq("claude-sonnet-4-5", "anthropic/claude-3-opus", "ollama/llama3", "ollama/gpt-4o", "gemini-2.5-pro", "")
      .foreach(model => withClue(model)(tokenizer(model) shouldBe None))
  }

  "OpenAITokenizers.counterFor" should "count with the model's vocabulary" in {
    val conversation = Conversation(Seq(UserMessage("Hello, tokenizer!")))
    val counter      = OpenAITokenizers.counterFor("gpt-4o").value
    counter.countConversation(conversation) should be > 0
  }

  it should "fail with a TokenizerError naming a model it does not recognise" in {
    val error = OpenAITokenizers.counterFor("claude-sonnet-4-5").left.value
    error shouldBe a[TokenizerError]
    error.message should include("claude-sonnet-4-5")
  }
}
