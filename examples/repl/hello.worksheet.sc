// A Metals worksheet with llm4s-repl, against an offline session: no API key, no network. Metals shows each value
// beside its line; `scala-cli run hello.worksheet.sc --dep org.llm4s::llm4s-repl:<version>` runs it as a script.
// CI runs this file with scripts/check-repl-scripts.sh against the library built from the branch.
//> using scala 3.7.1
//> using jvm 21

import org.llm4s.error.AuthenticationError
import org.llm4s.repl.*
import org.llm4s.testkit.Reply

given Session = Session.offline(
  Reply.text("A monad is a type with flatMap and pure that obey three laws."),
  Reply.failure(AuthenticationError("openai", "Invalid API key"))
)

val answer = ask("What is a monad, in one sentence?")
val failed = ask("And a functor?") // prints the error and its hint; no stack trace
println(s"answer: ${answer.getOrElse("")}")
println(s"failed: ${failed.isFailure}")
println(s"messages: ${history.size}")
