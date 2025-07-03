package org.llm4s.samples.tokens

import org.llm4s.identity.TokenizerId.{ CL100K_BASE, O200K_BASE, R50K_BASE }
import org.llm4s.tokens.Tokenizer

object TokenSample {
  def main(args: Array[String]): Unit =
    for (tokenizerId <- List(R50K_BASE, CL100K_BASE, O200K_BASE)) {
      val tokenizer = Tokenizer.lookupStringTokenizer(tokenizerId).get
      val message = "Hello Scala!"
      val tokens = tokenizer.encode(message)
      println(s"`$message` / $tokenizerId -> $tokens")
    }
}
