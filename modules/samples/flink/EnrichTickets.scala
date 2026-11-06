import java.util.Collections
import java.util.concurrent.{ CompletableFuture, ExecutorService, Executors, TimeUnit }

import scala.concurrent.duration.*

import org.apache.flink.api.common.functions.OpenContext
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.datastream.{ AsyncDataStream, DataStream }
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.streaming.api.functions.async.{ ResultFuture, RichAsyncFunction }
import org.apache.flink.util.{ Collector, OutputTag }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.reliability.{ RateLimitConfig, ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result

final case class Ticket(id: Int, text: String)

/** What the async operator emits for each ticket: exactly one of these, never an exception. */
sealed trait Outcome
final case class Enriched(id: Int, text: String, category: String) extends Outcome
final case class DeadLetter(ticket: Ticket, reason: String)        extends Outcome

/**
 * Classifies a ticket with one LLM call, off the task thread. The function is serialised and shipped to every
 * parallel instance, so it holds only `newClient`, a serialisable factory; the client, which holds an HTTP client
 * and is not serialisable, is built in `open()` on the task manager and closed in `close()`.
 */
final class ClassifyTicket(newClient: () => Result[LLMClient], threads: Int)
    extends RichAsyncFunction[Ticket, Outcome] {

  @transient private var client: LLMClient         = scala.compiletime.uninitialized
  @transient private var executor: ExecutorService = scala.compiletime.uninitialized

  override def open(context: OpenContext): Unit = {
    // A configuration error (no key, no provider module) fails the job at start, not every record one by one.
    client = newClient().fold(e => throw new IllegalStateException(s"no LLM client: ${e.message}"), identity)
    // `complete` blocks, so it runs here, never on the task thread. One thread per request the operator may
    // have in flight (its capacity): fewer would queue requests, and the queueing would count against the timeout.
    executor = Executors.newFixedThreadPool(threads)
  }

  override def asyncInvoke(ticket: Ticket, result: ResultFuture[Outcome]): Unit =
    CompletableFuture
      .supplyAsync(() => classify(ticket), executor)
      .whenComplete { (outcome, thrown) =>
        val emitted = if (thrown == null) outcome else DeadLetter(ticket, s"call threw: $thrown")
        result.complete(Collections.singletonList(emitted))
      }

  // Called by Flink when a request outlives the operator's timeout. The default fails the job; this dead-letters
  // the record instead. A late answer for the same record is ignored: a ResultFuture completes once.
  override def timeout(ticket: Ticket, result: ResultFuture[Outcome]): Unit =
    result.complete(Collections.singletonList(DeadLetter(ticket, "timed out")))

  override def close(): Unit = {
    if (executor != null) executor.shutdownNow()
    if (client != null) client.close()
  }

  private def classify(ticket: Ticket): Outcome = {
    val conversation = Conversation(
      Seq(
        SystemMessage("Classify the support ticket. Answer with one word: billing, bug or account."),
        UserMessage(ticket.text)
      )
    )
    client.complete(conversation, CompletionOptions().withMaxTokens(5)) match {
      case Left(error) => DeadLetter(ticket, error.message)
      case Right(completion) =>
        completion.content.trim.toLowerCase match {
          case category @ ("billing" | "bug" | "account") => Enriched(ticket.id, ticket.text, category)
          case other                                      => DeadLetter(ticket, s"unexpected answer: $other")
        }
    }
  }
}

/** Sends enriched tickets on, and dead letters to a side output. */
final class RouteOutcomes extends ProcessFunction[Outcome, Enriched] {
  override def processElement(
    outcome: Outcome,
    context: ProcessFunction[Outcome, Enriched]#Context,
    out: Collector[Enriched]
  ): Unit =
    outcome match {
      case enriched: Enriched => out.collect(enriched)
      case dead: DeadLetter   => context.output(EnrichTickets.DeadLetters, dead)
    }
}

object EnrichTickets {

  val DeadLetters: OutputTag[DeadLetter] = new OutputTag("dead-letters", TypeInformation.of(classOf[DeadLetter]))

  /** Requests one parallel instance may have in flight; with `threads = Capacity`, also its concurrent calls. */
  val Capacity = 4

  /** Flink's per-request timeout: longer than the client's own deadline (below), so the client's retries finish first. */
  val Timeout: FiniteDuration = 40.seconds

  /** The job: tickets in, enriched tickets out, dead letters on the `DeadLetters` side output. */
  def enrich(
    tickets: DataStream[Ticket],
    newClient: () => Result[LLMClient],
    timeout: FiniteDuration = Timeout
  ): (DataStream[Enriched], DataStream[DeadLetter]) = {
    val outcomes = AsyncDataStream.unorderedWait(
      tickets,
      new ClassifyTicket(newClient, threads = Capacity),
      timeout.toMillis,
      TimeUnit.MILLISECONDS,
      Capacity
    )
    val routed = outcomes.process(new RouteOutcomes, TypeInformation.of(classOf[Enriched]))
    (routed, routed.getSideOutput(DeadLetters))
  }

  /** The client from `application.conf` (llm4s.providers.provider), with retries, a deadline and a rate limit. */
  def configuredClient(): Result[LLMClient] = {
    val registry = ProviderRegistry.default
    for {
      models <- Llm4sConfig.modelRegistryService()
      config <- Llm4sConfig.defaultProvider()(using registry)
      client <- LLMConnect.getClient(config)(using models, registry)
    } yield new ReliableClient(
      client,
      "flink-example",
      ReliabilityConfig(
        retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 500.millis),
        rateLimit = RateLimitConfig(enabled = true, requestsPerMinute = 60, burstCapacity = Capacity),
        deadline = Some(30.seconds)
      )
    )
  }

  /** A scripted model for running with no key: one category per keyword, and a failure for "boom". */
  def scriptedClient(): Result[LLMClient] =
    Right(ScriptedLLMClient.respondingTo { conversation =>
      val text = conversation.messages.last.content
      if (text.contains("boom"))
        Reply.failure(org.llm4s.error.ServiceError(500, "scripted", "the scripted model failed"))
      else if (text.contains("charged")) Reply.text("billing")
      else if (text.contains("crash")) Reply.text("bug")
      else if (text.contains("password")) Reply.text("account")
      else Reply.text("no idea")
    })

  val SampleTickets: Seq[Ticket] = Seq(
    Ticket(1, "I was charged twice this month"),
    Ticket(2, "The app crashes when I open settings"),
    Ticket(3, "boom: the scripted model fails on this one"),
    Ticket(4, "I forgot my password"),
    Ticket(5, "What is the meaning of life?")
  )

  def main(args: Array[String]): Unit = {
    val newClient: () => Result[LLMClient] =
      if (args.contains("--scripted")) () => scriptedClient() else () => configuredClient()
    val env                     = StreamExecutionEnvironment.getExecutionEnvironment
    val tickets                 = env.fromData(TypeInformation.of(classOf[Ticket]), SampleTickets*)
    val (enriched, deadLetters) = enrich(tickets, newClient)
    enriched.print("enriched")
    deadLetters.print("dead-letter")
    env.execute("llm4s-enrich-tickets")
  }
}
