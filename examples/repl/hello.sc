// A Scala CLI script with llm4s-repl, against an offline session: no API key, no network.
//
//   scala-cli run hello.sc --dep org.llm4s::llm4s-repl:<version>
//
// For a real model, add the provider module (--dep org.llm4s::llm4s-openai:<version>), put an application.conf in a
// resource directory (--resource-dir resources) and replace Session.offline(...) with Session.fromConfig().
// CI runs this file with scripts/check-repl-scripts.sh against the library built from the branch.
//> using scala 3.7.1
//> using jvm 21

import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.repl.*
import org.llm4s.schema.SchemaOf
import org.llm4s.testkit.Reply
import upickle.default.ReadWriter

case class Invoice(vendor: String, amount: Double, currency: String) derives SchemaOf, ReadWriter

given Session = Session.offline(
  Reply.text("Covariance lets a List[Cat] be used where a List[Animal] is expected.").withUsage(TokenUsage(12, 14, 26)),
  Reply.text("""{"vendor":"ACME","amount":12.5,"currency":"EUR"}"""),
  Reply.text("Contravariance is the opposite.")
)

ask("Explain covariance in one sentence")
println(s"invoice: ${extract[Invoice]("ACME, 12.50 EUR, two widgets")}")
println(s"usage: ${lastUsage.map(_.model).getOrElse("none")}")
strict.ask("And contravariance?") match {
  case Right(_)    => ()
  case Left(_)     => sys.exit(1)
}
println(s"strict failure: ${strict.ask("One more?").isLeft}")
println("history:")
history.show
