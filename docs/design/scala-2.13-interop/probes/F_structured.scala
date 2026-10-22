// Probe F: completeStructured[A], which takes an implicit upickle Reader[A]. upickle's macroRW is a Scala 3
// macro, so the Reader is written by hand from Scala 2.13.
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{Conversation, UserMessage}
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.toolapi.Schema

final case class Capital(country: String, city: String)

object Probe extends App {
  implicit val capitalReader: upickle.default.Reader[Capital] =
    upickle.default.reader[ujson.Value].map(json => Capital(json("country").str, json("city").str))

  val schema = Schema
    .`object`[Capital]("A capital city")
    .withProperty(Schema.property("country", Schema.string("the country")))
    .withProperty(Schema.property("city", Schema.string("its capital")))

  FakeOllama.withServer("""{"country":"France","city":"Paris"}""") { port =>
    FakeOllama.configure(port)
    val registry = ProviderRegistry.default
    val reply = for {
      models  <- Llm4sConfig.modelRegistryService()
      config  <- Llm4sConfig.defaultProvider()(registry)
      client  <- LLMConnect.getClient(config)(models, registry)
      capital <- client.completeStructured[Capital](Conversation(Seq(UserMessage("Capital of France?"))), schema)
    } yield capital
    reply match {
      case Right(c)    => println(s"PASS: $c")
      case Left(error) => println(s"FAIL: ${error.message}")
    }
  }
}
