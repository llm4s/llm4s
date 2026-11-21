package org.llm4s.repl

import org.llm4s.annotation.Experimental
import org.llm4s.config.Llm4sConfig
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Message }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId, ProviderName }
import org.llm4s.types.Result

import java.io.{ BufferedReader, PrintStream }

/**
 * What the helpers of `org.llm4s.repl` talk to: a client, its completion options, the conversation so far and the
 * statistics of the last call. Set it once as a context parameter and every helper finds it:
 *
 * {{{
 * import org.llm4s.repl.*
 *
 * given Session = Session.fromConfig()       // the `llm4s.providers.provider` section of application.conf
 * ask("Explain variance in Scala 3 in two sentences")
 * }}}
 *
 * A session whose client cannot be built (no `application.conf`, a missing API key, an unregistered provider) is
 * still a session: every helper prints the configuration error with its troubleshooting hint and returns a failure,
 * so a worksheet never stops on a stack trace. [[failure]] says whether that is the case.
 *
 * A session is for a REPL, a worksheet or a script, not for production code: it keeps the conversation in memory,
 * prints to the console, and its helpers are not meant to be called concurrently. Each `with*` method returns a new
 * session with the same client and an empty history.
 */
@Experimental
final class Session private (
  connection: Result[LLMClient],
  val description: String,
  val options: CompletionOptions,
  val systemPrompt: Option[String],
  val maxToolSteps: Int,
  input: Option[BufferedReader],
  output: Option[PrintStream],
  private[repl] val nanoTime: () => Long
) {

  private val lock                      = new Object
  private var messages: Vector[Message] = Vector.empty
  private var last: Option[CallStats]   = None

  /** The client, wrapped so that every call it makes is timed for [[lastUsage]]. */
  private[repl] lazy val client: Result[LLMClient] = connection.map(new RecordingClient(_, this))

  /** The error that kept this session's client from being built, if there was one. */
  def failure: Option[LLMError] = connection.left.toOption

  /** A new session that sends these completion options with every call. */
  def withOptions(options: CompletionOptions): Session = copy(options = options)

  /** A new session that starts every conversation with this system message. */
  def withSystemPrompt(prompt: String): Session = copy(systemPrompt = Some(prompt))

  /** A new session that runs at most this many model calls (at least 1) for one `ask` that calls tools. */
  def withMaxToolSteps(steps: Int): Session = copy(maxToolSteps = math.max(1, steps))

  /** A new session that prints to `out` instead of the console. */
  def withOutput(out: PrintStream): Session = copy(output = Some(out))

  /** A new session whose `chat()` reads lines from `in` instead of the console. */
  def withInput(in: BufferedReader): Session = copy(input = Some(in))

  /** Forgets the conversation so far and the last call's statistics. */
  def reset(): Unit = lock.synchronized {
    messages = Vector.empty
    last = None
  }

  /** Closes the client. */
  def close(): Unit = connection.foreach(_.close())

  override def toString: String =
    failure match {
      case Some(error) => s"Session($description, not connected: ${Render.errorKind(error)})"
      case None =>
        val count = lock.synchronized(messages.size)
        s"Session($description, $count ${if (count == 1) "message" else "messages"})"
    }

  private[repl] def out: PrintStream = output.getOrElse(Console.out)

  private[repl] def in: BufferedReader = input.getOrElse(Console.in)

  private[repl] def conversationSoFar: Vector[Message] = lock.synchronized(messages)

  private[repl] def append(turn: Seq[Message]): Unit = lock.synchronized { messages = messages ++ turn }

  private[repl] def lastCall: Option[CallStats] = lock.synchronized(last)

  private[repl] def record(stats: CallStats): Unit = lock.synchronized { last = Some(stats) }

  private[repl] def withClock(clock: () => Long): Session = copy(nanoTime = clock)

  private def copy(
    options: CompletionOptions = options,
    systemPrompt: Option[String] = systemPrompt,
    maxToolSteps: Int = maxToolSteps,
    input: Option[BufferedReader] = input,
    output: Option[PrintStream] = output,
    nanoTime: () => Long = nanoTime
  ): Session =
    new Session(connection, description, options, systemPrompt, maxToolSteps, input, output, nanoTime)
}

object Session {

  /** The number of model calls one `ask` with tools makes at most, unless [[Session.withMaxToolSteps]] changes it. */
  val DefaultMaxToolSteps: Int = 8

  /**
   * A session on the default provider: the section that `llm4s.providers.provider` names in `application.conf`, with
   * the provider modules on the classpath. A configuration that cannot be loaded gives a session whose helpers
   * print the error ([[Session.failure]]).
   */
  def fromConfig(): Session = fromProvider("default provider", Llm4sConfig.defaultProvider())

  /** A session on the named section `llm4s.providers.<name>` of `application.conf`. */
  def named(name: String): Session = fromProvider(name, Llm4sConfig.provider(name))

  /**
   * A session on a local Ollama server at its default address, `http://localhost:11434`, with no `application.conf`
   * section to write. It needs `llm4s-ollama` on the classpath (`//> using dep org.llm4s::llm4s-ollama:<version>`);
   * without it, the session's [[Session.failure]] says the provider is not registered.
   *
   * {{{
   * given Session = Session.ollama("llama3")    // after `ollama pull llama3`
   * }}}
   */
  def ollama(model: String): Session = ollama(model, None)

  /** A session on the Ollama server at `baseUrl`, for example `http://gpu-box:11434`. */
  def ollama(model: String, baseUrl: String): Session = ollama(model, Some(baseUrl))

  private[repl] def ollama(model: String, baseUrl: Option[String])(using ProviderRegistry): Session = {
    val name    = ProviderName("repl-ollama")
    val section = NamedProviderConfig(ProviderId("ollama"), ModelName(model), baseUrl.map(BaseUrl(_)), apiKey = None)
    val (errors, configs) = Llm4sConfig.providerConfigs(Map(name -> section))
    val config = configs
      .get(name)
      .toRight(errors.getOrElse(name, ConfigurationError(s"Could not build an Ollama provider for '$model'")))
    fromProvider("ollama", config)
  }

  /** A session on a client built elsewhere. */
  def of(client: LLMClient): Session = connect("client", Right(client))

  /**
   * A session that needs no key and no network: the first call is answered with the first reply, the second with
   * the second, and so on (the testkit's [[org.llm4s.testkit.ScriptedLLMClient.sequence]]). A call past the last
   * reply fails with an error that says so.
   *
   * {{{
   * given Session = Session.offline(Reply.text("Covariance lets a List[Cat] be a List[Animal]."))
   * }}}
   */
  def offline(replies: Reply*): Session =
    connect(
      s"offline, ${replies.size} scripted ${if (replies.size == 1) "reply" else "replies"}",
      Right(
        ScriptedLLMClient.sequence(replies*)
      )
    )

  /** A session on a scripted client, for replies that depend on the conversation. */
  def offline(client: ScriptedLLMClient): Session = connect("offline", Right(client))

  private def fromProvider(label: String, config: Result[ProviderConfig])(using ProviderRegistry): Session =
    config match {
      case Left(error) => connect(label, Left(error))
      case Right(loaded) =>
        val client = Llm4sConfig.modelRegistryService().flatMap { registry =>
          given ModelRegistryService = registry
          LLMConnect.getClient(loaded)
        }
        connect(s"$label, ${loaded.model}", client)
    }

  private def connect(description: String, client: Result[LLMClient]): Session =
    new Session(
      client,
      description,
      CompletionOptions(),
      None,
      DefaultMaxToolSteps,
      None,
      None,
      () => System.nanoTime()
    )
}
