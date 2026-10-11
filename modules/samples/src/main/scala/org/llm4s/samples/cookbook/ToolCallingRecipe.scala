package org.llm4s.samples.cookbook

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, ToolCall }
import org.llm4s.samples.util.AgentResults
import org.llm4s.toolapi.builtin.core.CalculatorTool
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import upickle.default.ReadWriter

final case class ExchangeRate(from: String, to: String, rate: Double) derives ReadWriter

/** Recipe: an agent that calls two tools, one of its own and the built-in calculator, to answer one question. */
object ToolCallingRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "tool-calling",
    title = "An agent that calls two tools",
    summary = "Give an agent an exchange-rate tool and the calculator, and let the model chain them.",
    mainClass = "org.llm4s.samples.cookbook.ToolCallingRecipe"
  )

  val question: String = "How much is 120 US dollars in euros?"

  val rates: Map[(String, String), Double] = Map(("USD", "EUR") -> 0.92, ("EUR", "USD") -> 1.09)

  val exchangeRate: Result[ToolFunction[Map[String, Any], ExchangeRate]] =
    ToolBuilder[Map[String, Any], ExchangeRate](
      "exchange_rate",
      "Today's exchange rate from one currency to another",
      Schema
        .`object`[Map[String, Any]]("A currency pair")
        .withRequiredField("from", Schema.string("ISO 4217 code of the currency to convert from, e.g. USD"))
        .withRequiredField("to", Schema.string("ISO 4217 code of the currency to convert to, e.g. EUR"))
    ).withHandler { params =>
      for {
        from <- params.getString("from").map(_.toUpperCase)
        to   <- params.getString("to").map(_.toUpperCase)
        rate <- rates.get((from, to)).toRight(s"no rate from $from to $to")
      } yield ExchangeRate(from, to, rate)
    }.buildSafe()

  def run(client: LLMClient, question: String): Result[AgentResult] =
    for {
      rateTool   <- exchangeRate
      calculator <- CalculatorTool.toolSafe
      agent  <- Agent.builder("currency-agent", client).withTools(new ToolRegistry(Seq(rateTool, calculator))).build()
      result <- agent.run(question)
    } yield result

  /** Asks for the rate, then for the calculator with that rate, then answers with the calculator's result. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val outputs = conversation.messages.filter(_.role == MessageRole.Tool).map(m => ujson.read(m.content))
    def call(name: String, args: ujson.Obj) = AssistantMessage(None, Seq(ToolCall(s"call-$name", name, args)))
    Right(outputs.size match {
      case 0 => call("exchange_rate", ujson.Obj("from" -> "USD", "to" -> "EUR"))
      case 1 => call("calculator", ujson.Obj("operation" -> "multiply", "a" -> 120, "b" -> outputs(0)("rate").num))
      case _ => AssistantMessage(s"120 US dollars is ${outputs(1)("formatted").str} euros at today's rate.")
    })
  })

  def demo(client: LLMClient): Result[String] =
    run(client, question).flatMap(AgentResults.requireCompleted)
}
