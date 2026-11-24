package org.llm4s.llmconnect.provider

import org.llm4s.context.ConversationTokenCounter
import org.llm4s.context.tokens.TokenizerId
import org.llm4s.error.TokenizerError
import org.llm4s.types.Result

import java.util.Locale

/**
 * Which tokenizer vocabulary an OpenAI model uses, so its tokens can be counted exactly without a
 * call to the API.
 *
 * {{{
 * val counter = OpenAITokenizers.counterFor("gpt-4o")   // o200k_base
 * }}}
 *
 * The name may carry the `openai/` or `azure/` prefix. An Azure deployment is matched on the OpenAI
 * model name its text contains, spelled as OpenAI spells it: `azure/my-gpt-4o-deployment` is
 * `o200k_base`, while a deployment whose name does not contain a model name is not recognised.
 *
 * Models of other vendors have no public tokenizer here. Count them with
 * `ConversationTokenCounter.cl100k()`, an approximation that can be 20-30% off, or with your own
 * `StringTokenizer` through `ConversationTokenCounter.of`.
 */
object OpenAITokenizers {

  /**
   * The vocabulary of an OpenAI model, or `None` when `model` is not recognised as one: a name under
   * another provider's prefix, or one that contains no OpenAI model name.
   */
  def tokenizerFor(model: String): Option[TokenizerId] = {
    val name = model.trim.toLowerCase(Locale.ROOT)
    val bare = name.indexOf('/') match {
      case -1 => Some(name)
      case i  => Option.when(Prefixes(name.take(i)))(name.drop(i + 1))
    }
    bare.flatMap { n =>
      if (isO200k(n)) Some(TokenizerId.O200K_BASE)
      else if (Cl100k.exists(n.contains)) Some(TokenizerId.CL100K_BASE)
      else if (n.contains("gpt-3")) Some(TokenizerId.R50K_BASE)
      else None
    }
  }

  /**
   * A token counter over the vocabulary of an OpenAI model, or a `TokenizerError` when `model` is not
   * recognised as one (see [[tokenizerFor]]).
   */
  def counterFor(model: String): Result[ConversationTokenCounter] =
    tokenizerFor(model).toRight(TokenizerError.notAvailable(model)).flatMap(ConversationTokenCounter.create)

  private val Prefixes = Set("openai", "azure")

  // Checked before `Cl100k`, whose "gpt-4" these names contain.
  private val O200k  = Seq("gpt-4o", "gpt-4.1", "gpt-4.5", "gpt-5")
  private val Cl100k = Seq("gpt-4", "gpt-3.5", "gpt-35", "text-embedding-")

  // o1, o3, o4-mini, ...: the letter and its digit as a whole word, so `turbo3` and `foo1` are not one.
  private val OSeries = """(?:^|[^a-z0-9])o[1-9](?:[^a-z0-9]|$)""".r

  private def isO200k(name: String): Boolean =
    O200k.exists(name.contains) || OSeries.findFirstIn(name).isDefined
}
