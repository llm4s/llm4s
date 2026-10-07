package org.llm4s.context.tokens

import org.llm4s.identity.TokenizerId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for [[TokenizerMapping]]: which tokenizer a model name selects, and how exact that choice is.
 *
 * The `case` guards in `getTokenizerId` are order-sensitive, so the tests are grouped around the
 * boundaries between guards (`gpt-4` vs `gpt-4o`, `gpt-3` vs `gpt-3.5`, `o1-`, the provider
 * prefixes). Most families map to `cl100k_base`, which is also the fallback, so a family's tokenizer
 * is only checked against a name the fallback would get wrong where one exists, and the accuracy
 * level is checked alongside to tell the guards apart.
 */
class TokenizerMappingSpec extends AnyFlatSpec with Matchers {

  private def expectTokenizer(expected: TokenizerId)(models: String*): Unit =
    models.foreach { model =>
      withClue(s"tokenizer for '$model': ")(TokenizerMapping.getTokenizerId(model) shouldBe expected)
    }

  private def expectExact(models: String*): Unit =
    models.foreach { model =>
      withClue(s"accuracy for '$model': ") {
        TokenizerMapping.getAccuracyInfo(model) match {
          case exact: TokenizerAccuracy.Exact =>
            exact.isExact shouldBe true
            exact.description should not be empty
          case other => fail(s"expected Exact, got $other")
        }
      }
    }

  private def expectApproximate(expectedAccuracy: Double)(models: String*): Unit =
    models.foreach { model =>
      withClue(s"accuracy for '$model': ") {
        TokenizerMapping.getAccuracyInfo(model) match {
          case approximate: TokenizerAccuracy.Approximate =>
            approximate.isExact shouldBe false
            approximate.accuracy shouldBe expectedAccuracy +- 1e-9
            approximate.description should not be empty
          case other => fail(s"expected Approximate($expectedAccuracy), got $other")
        }
      }
    }

  private def expectUnknown(models: String*): Unit =
    models.foreach { model =>
      withClue(s"accuracy for '$model': ") {
        TokenizerMapping.getAccuracyInfo(model) match {
          case unknown: TokenizerAccuracy.Unknown =>
            unknown.isExact shouldBe false
            unknown.description should not be empty
          case other => fail(s"expected Unknown, got $other")
        }
      }
    }

  // ============ getTokenizerId ============

  "TokenizerMapping.getTokenizerId" should "map gpt-4o and o1 models to o200k_base" in {
    expectTokenizer(TokenizerId.O200K_BASE)("gpt-4o", "gpt-4o-mini", "gpt-4o-2024-08-06", "o1-mini", "o1-preview")
  }

  it should "map gpt-4 models that are not gpt-4o to cl100k_base" in {
    // `gpt-4o` contains `gpt-4`: the o200k guard has to win for it, and only for it
    TokenizerMapping.getTokenizerId("gpt-4o") should not be TokenizerId.CL100K_BASE
    expectTokenizer(TokenizerId.CL100K_BASE)("gpt-4", "gpt-4-turbo", "gpt-4-32k")
    TokenizerMapping.getTokenizerId("gpt-4") should not be TokenizerId.O200K_BASE
  }

  it should "map gpt-3.5 models to cl100k_base, not to the gpt-3 tokenizer" in {
    // `gpt-3.5-turbo` contains `gpt-3`: the gpt-3.5 guard has to run before the legacy one
    expectTokenizer(TokenizerId.CL100K_BASE)("gpt-3.5-turbo", "gpt-3.5-turbo-16k")
    TokenizerMapping.getTokenizerId("gpt-3.5-turbo") should not be TokenizerId.R50K_BASE
  }

  it should "map legacy gpt-3 names to r50k_base" in {
    expectTokenizer(TokenizerId.R50K_BASE)("gpt-3", "gpt-3-davinci")
  }

  it should "map Claude models, with or without the anthropic prefix, to cl100k_base" in {
    expectTokenizer(TokenizerId.CL100K_BASE)(
      "claude-3-sonnet",
      "anthropic/claude-3-sonnet",
      "anthropic/some-future-model"
    )
  }

  it should "map ollama models to cl100k_base" in {
    expectTokenizer(TokenizerId.CL100K_BASE)("ollama/llama2", "ollama/mistral:7b")
  }

  it should "fall back to cl100k_base for an unknown, empty or blank name" in {
    expectTokenizer(TokenizerId.CL100K_BASE)("mistral-large", "totally-made-up-model", "", "   ")
  }

  it should "ignore case" in {
    expectTokenizer(TokenizerId.O200K_BASE)("GPT-4O", "Gpt-4o-Mini", "O1-Mini", "OpenAI/GPT-4o")
    expectTokenizer(TokenizerId.R50K_BASE)("GPT-3", "OPENAI/GPT-3-DAVINCI")
  }

  it should "accept a provider prefix on an OpenAI model name" in {
    expectTokenizer(TokenizerId.O200K_BASE)("openai/gpt-4o", "openai/gpt-4o-mini", "openai/o1-preview")
    expectTokenizer(TokenizerId.R50K_BASE)("openai/gpt-3")
    expectTokenizer(TokenizerId.CL100K_BASE)("openai/gpt-4", "openai/gpt-3.5-turbo")
  }

  it should "take the model out of an azure deployment name when it contains a known model" in {
    // These reach the OpenAI guards before the azure/ prefix guard does
    expectTokenizer(TokenizerId.O200K_BASE)("azure/my-gpt-4o-deployment", "azure/o1-preview-prod")
    expectTokenizer(TokenizerId.R50K_BASE)("azure/legacy-gpt-3")
    expectTokenizer(TokenizerId.CL100K_BASE)("azure/prod-gpt-4-turbo", "azure/chat-gpt-3.5-turbo")
  }

  it should "map an azure deployment name with no recognised model text to cl100k_base" in {
    expectTokenizer(TokenizerId.CL100K_BASE)("azure/my-deployment", "azure/")
  }

  it should "map every example in the class documentation to the tokenizer it implies" in {
    expectTokenizer(TokenizerId.O200K_BASE)("gpt-4o", "openai/gpt-4o", "azure/my-gpt-4o-deployment")
    expectTokenizer(TokenizerId.CL100K_BASE)("claude-3-sonnet", "anthropic/claude-3-sonnet", "ollama/llama2")
  }

  it should "match an azure deployment only on the hyphenated OpenAI spelling of the model, as documented" in {
    // `gpt4o` has no hyphen, so the o200k guard does not see it; the class documentation says so
    expectTokenizer(TokenizerId.CL100K_BASE)("azure/my-gpt4o-deployment", "azure/gpt4-prod", "azure/GPT4O")
    expectExact("azure/my-gpt4o-deployment")
  }

  it should "give an azure deployment the tokenizer of the first family its name matches" in {
    // A name that fits several families takes the first guard that matches, in the order of the
    // `case` list: the OpenAI families, then Claude, then the azure/ prefix, then ollama
    val expected = Seq(
      "azure/o1-mini"              -> TokenizerId.O200K_BASE,
      "azure/gpt-3-x"              -> TokenizerId.R50K_BASE,
      "azure/legacy-gpt-3"         -> TokenizerId.R50K_BASE,
      "azure/gpt-3.5-x"            -> TokenizerId.CL100K_BASE,
      "azure/my-gpt-4o-deployment" -> TokenizerId.O200K_BASE,
      "azure/ollama/x"             -> TokenizerId.CL100K_BASE,
      "azure/"                     -> TokenizerId.CL100K_BASE
    )
    expected.foreach { case (model, tokenizer) =>
      withClue(s"tokenizer for '$model': ")(TokenizerMapping.getTokenizerId(model) shouldBe tokenizer)
    }
  }

  // ============ getAccuracyInfo ============

  "TokenizerMapping.getAccuracyInfo" should "report OpenAI gpt-4o, gpt-4 and gpt-3.5 models as exact" in {
    expectExact(
      "gpt-4o",
      "gpt-4o-mini",
      "o1-mini",
      "gpt-4",
      "gpt-4-turbo",
      "gpt-3.5-turbo",
      "openai/gpt-4o",
      "OpenAI/GPT-4"
    )
  }

  it should "report an azure deployment as exact, whether or not it names a model" in {
    expectExact("azure/my-deployment", "azure/my-gpt-4o-deployment", "AZURE/Anything")
  }

  it should "report Claude models as approximate with accuracy 0.75" in {
    expectApproximate(0.75)("claude-3-sonnet", "anthropic/claude-3-sonnet", "anthropic/some-future-model", "CLAUDE-3")
  }

  it should "report ollama models as approximate with accuracy 0.80" in {
    expectApproximate(0.80)("ollama/llama2", "OLLAMA/Mistral")
  }

  it should "report an unknown, empty or blank model as unknown" in {
    expectUnknown("mistral-large", "totally-made-up-model", "", "   ")
  }

  it should "report legacy gpt-3 models as exact, as the class documentation's table says" in {
    // Table row: `gpt-3 (legacy) | r50k_base | Exact`
    Seq("gpt-3", "gpt-3-davinci", "openai/gpt-3", "GPT-3-DAVINCI").foreach { model =>
      withClue(s"model '$model': ") {
        TokenizerMapping.getTokenizerId(model) shouldBe TokenizerId.R50K_BASE
        expectExact(model)
      }
    }
  }

  // ============ isExactMapping ============

  "TokenizerMapping.isExactMapping" should "be true for OpenAI and azure models" in {
    Seq(
      "gpt-4o",
      "o1-mini",
      "gpt-4-turbo",
      "gpt-3.5-turbo",
      "gpt-3",
      "gpt-3-davinci",
      "openai/gpt-4o",
      "azure/my-deployment"
    )
      .foreach(model => withClue(s"model '$model': ")(TokenizerMapping.isExactMapping(model) shouldBe true))
  }

  it should "be false for Claude, ollama and unknown models" in {
    Seq("claude-3-sonnet", "anthropic/claude-3-sonnet", "ollama/llama2", "mistral-large", "").foreach { model =>
      withClue(s"model '$model': ")(TokenizerMapping.isExactMapping(model) shouldBe false)
    }
  }

  it should "agree with the isExact flag of getAccuracyInfo for every kind of model" in {
    val models = Seq(
      "gpt-4o",
      "gpt-4",
      "gpt-3.5-turbo",
      "gpt-3",
      "azure/my-deployment",
      "claude-3-sonnet",
      "ollama/llama2",
      "mistral-large",
      ""
    )
    models.foreach { model =>
      withClue(s"model '$model': ") {
        TokenizerMapping.isExactMapping(model) shouldBe TokenizerMapping.getAccuracyInfo(model).isExact
      }
    }
  }

  // ============ TokenizerAccuracy ============

  "TokenizerAccuracy" should "be exact only for the Exact variant" in {
    TokenizerAccuracy.Exact("native").isExact shouldBe true
    TokenizerAccuracy.Approximate("close", accuracy = 0.9).isExact shouldBe false
    TokenizerAccuracy.Unknown("who knows").isExact shouldBe false
  }
}
