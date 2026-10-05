package org.llm4s.testkit

import org.llm4s.error.CancelledError
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{
  ContextWindowResolver,
  EmbeddingModelConfig,
  EmbeddingProviderConfig,
  ProviderConfig
}
import org.llm4s.llmconnect.model.{ Conversation, EmbeddingRequest, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{
  EmbeddingProviderDescriptor,
  Llm4sProviderModule,
  ProviderDescriptor,
  ProviderModuleReport,
  ProviderRegistry
}
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalactic.source.Position
import org.scalatest.exceptions.{ StackDepthException, TestFailedException }
import org.scalatest.{ Assertion, Assertions }

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.collection.mutable.ListBuffer

/**
 * The checks every provider module's `Llm4s<Name>ModuleSpec` makes, as assertions.
 *
 * A provider module is a dependency, not an edit to llm4s: its `Llm4sProviderModule` is found
 * through `META-INF/services`, and a mistake there - a typo in the services file, an `object`
 * where a `class` is needed, a second module claiming the same id, a `reference.conf` binding the
 * wrong variable - fails at a user's runtime, not at your compile time. These checks catch each of
 * those in your own build. They are the ones every provider module in the llm4s repository runs.
 *
 * Mix the trait into a spec of any ScalaTest style, or call the companion object's methods. A
 * failed check fails the test at the call site with a message saying what was wrong:
 *
 * {{{
 * class Llm4sAcmeModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:
 *
 *   "llm4s-acme" should {
 *     "register itself through META-INF/services" in assertModule(new Llm4sAcmeModule)
 *
 *     "build a client from a section" in {
 *       assertBuildsClient(AcmeProvider, AcmeProvider.section(...))
 *       assertRefusesForeignConfig(AcmeProvider)
 *     }
 *
 *     "bind ACME_API_KEY" in assertCredentialBindings(AcmeProvider)
 *   }
 * }}}
 *
 * Chat providers also run [[assertCancelsWhenInterrupted]] and [[assertCancelsStreamWhenInterrupted]]
 * against [[LocalProviderTestServer.holdOpen]] and [[LocalProviderTestServer.streamThenHold]].
 *
 * Modules are compared by class and descriptors by equality (normally reference equality on an
 * `object`), so pass the same descriptor instances your module lists.
 */
trait ProviderModuleChecks extends Assertions:

  // ---- registration ----

  /**
   * `module` is found by `ProviderRegistry.discover()`, as a user's application finds it, and
   * contributes what it lists: each chat and embedding descriptor resolves by its id and by each
   * of its aliases, and discovery recorded no failure for the module.
   *
   * Fails when the services entry is missing or misspelled, names an `object` or a class with no
   * public no-arg constructor, or when another module's descriptor wins one of the ids.
   */
  def assertDiscovered(module: Llm4sProviderModule)(using pos: Position): Assertion =
    val registry  = ProviderRegistry.discover()
    val className = module.getClass.getName
    val report    = registry.report
    if !report.modules.exists(_.moduleClass == className) then
      failAt(
        s"$className was not discovered. Discovery found:\n${report.describe}\n" +
          s"Name it on its own line in META-INF/services/${classOf[Llm4sProviderModule].getName}, " +
          "as a class with a public no-arg constructor."
      )
    report.failures.find(_.detail.contains(className)).foreach { failure =>
      failAt(s"Discovery reported a failure for $className: ${failure.detail}")
    }
    assertContributes(registry, module, "discovered")

  /**
   * No module but `module` supplies any of its chat or embedding ids on the test classpath, and
   * discovery recorded no collision on them.
   *
   * Every id has exactly one owner: two modules registering `acme` means one silently wins.
   */
  def assertSoleSupplier(module: Llm4sProviderModule)(using pos: Position): Assertion =
    val report    = ProviderRegistry.discover().report
    val className = module.getClass.getName
    def suppliers(kind: String, ids: Seq[String], of: ProviderModuleReport => Seq[String]): Unit =
      ids.foreach { id =>
        val owners = report.modules.filter(m => of(m).contains(id)).map(_.moduleClass)
        if owners != Seq(className) then
          failAt(s"$kind id '$id' should be supplied by $className alone, but is supplied by ${owners.mkString(", ")}")
      }
    suppliers("chat", module.chatProviders.map(_.id.asString), _.providerIds)
    suppliers("embedding", module.embeddingProviders.map(_.id.asString), _.embeddingProviderIds)
    val ids = (module.chatProviders.map(_.id.asString) ++ module.embeddingProviders.map(_.id.asString)).toSet
    report.collisions.find(c => ids.contains(c.id)).foreach { c =>
      failAt(
        s"${c.kind} id '${c.id}' collides: ${c.droppedModule.getOrElse("an earlier registration")} and ${c.keptModule}"
      )
    }
    succeed

  /**
   * `ProviderRegistry.ofModules(module)` - explicit registration, for where discovery cannot run
   * (a shaded jar that drops `META-INF/services`, a GraalVM native image) - resolves each of the
   * module's descriptors by id and alias.
   */
  def assertRegistrableWith(module: Llm4sProviderModule)(using pos: Position): Assertion =
    assertContributes(ProviderRegistry.ofModules(module), module, "registered with ProviderRegistry.ofModules")

  /**
   * [[assertDiscovered]], [[assertSoleSupplier]] and [[assertRegistrableWith]] together: the
   * registration half of a module spec in one line. A module that lists no provider at all fails.
   */
  def assertModule(module: Llm4sProviderModule)(using pos: Position): Assertion =
    if module.chatProviders.isEmpty && module.embeddingProviders.isEmpty then
      failAt(s"${module.getClass.getName} lists no chat or embedding provider")
    assertDiscovered(module)
    assertSoleSupplier(module)
    assertRegistrableWith(module)

  private def assertContributes(registry: ProviderRegistry, module: Llm4sProviderModule, how: String)(using
    Position
  ): Assertion =
    val className = module.getClass.getName
    module.chatProviders.foreach { descriptor =>
      val id = descriptor.id.asString
      registry.get(descriptor.id) match
        case Right(found) if found == descriptor => ()
        case Right(other) => failAt(s"chat id '$id' resolves to $other, not $className's $descriptor, when $how")
        case Left(error)  => failAt(s"chat id '$id' from $className does not resolve when $how: ${error.message}")
      descriptor.aliases.foreach { alias =>
        if registry.canonicalId(alias) != descriptor.id then
          failAt(s"chat alias '$alias' does not resolve to '$id' when $how")
      }
    }
    module.embeddingProviders.foreach { descriptor =>
      val id = descriptor.id.asString
      registry.resolveEmbedding(descriptor.id) match
        case Right(found) if found == descriptor => ()
        case Right(other) => failAt(s"embedding id '$id' resolves to $other, not $className's $descriptor, when $how")
        case Left(error)  => failAt(s"embedding id '$id' from $className does not resolve when $how: ${error.message}")
      descriptor.aliases.foreach { alias =>
        if registry.canonicalEmbeddingId(alias) != descriptor.id then
          failAt(s"embedding alias '$alias' does not resolve to '$id' when $how")
      }
    }
    succeed

  // ---- config to client ----

  /**
   * Builds a client the way `LLMConnect.getClient` does: `descriptor.buildConfig` on `section`,
   * then `descriptor.buildClient` on the config it returned.
   *
   * @param descriptor    the chat descriptor under test
   * @param section       a section as validation would leave it; build one in code, or load one
   *                      from HOCON with [[ProviderTestConfig.loadSection]]
   * @param options       client options; the defaults suit a test
   * @param modelRegistry the model registry the client and its context-window resolution see
   */
  def buildClient(
    descriptor: ProviderDescriptor,
    section: NamedProviderConfig,
    options: LlmClientOptions = LlmClientOptions.default,
    modelRegistry: ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
  ): Result[LLMClient] =
    given ModelRegistryService  = modelRegistry
    given ContextWindowResolver = ContextWindowResolver(modelRegistry)
    descriptor
      .buildConfig("provider-testkit", section)
      .flatMap(descriptor.buildClient(_, options))

  /**
   * [[buildClient]], failing the test on a `Left`. The config-to-client round trip.
   *
   * @return the client, for further checks such as [[assertStreams]]
   */
  def assertBuildsClient(
    descriptor: ProviderDescriptor,
    section: NamedProviderConfig,
    options: LlmClientOptions = LlmClientOptions.default,
    modelRegistry: ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
  )(using pos: Position): LLMClient =
    buildClient(descriptor, section, options, modelRegistry) match
      case Right(client) => client
      case Left(error)   => failAt(s"${descriptor.id.asString} failed to build a client: ${error.message}")

  /**
   * `descriptor.buildClient` returns a `Left` - rather than throwing, or building a client - when
   * handed a `ProviderConfig` some other provider built. `ProviderDescriptor.expectConfig` is the
   * usual way to get this right.
   */
  def assertRefusesForeignConfig(
    descriptor: ProviderDescriptor,
    modelRegistry: ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
  )(using pos: Position): Assertion =
    given ModelRegistryService = modelRegistry
    descriptor.buildClient(ProviderModuleChecks.ForeignConfig, LlmClientOptions.default) match
      case Left(_) => succeed
      case Right(client) =>
        failAt(s"${descriptor.id.asString} accepted another provider's config and built $client")

  /**
   * `client.streamComplete` delivers at least one chunk for a one-message conversation and
   * succeeds - so a client whose descriptor declares `features.streaming` really streams rather
   * than silently falling back to `complete()`. Point the client at a stub server first;
   * [[LocalProviderTestServer]] is one.
   */
  def assertStreams(client: LLMClient)(using pos: Position): Assertion =
    val chunks = ListBuffer.empty[StreamedChunk]
    client.streamComplete(Conversation(Seq(UserMessage("Hello"))), onChunk = chunks += _) match
      case Left(error)                => failAt(s"streamComplete failed: ${error.message}")
      case Right(_) if chunks.isEmpty => failAt("streamComplete succeeded without delivering a single chunk")
      case Right(_)                   => succeed

  /**
   * `client.complete` honours interruption: run on a virtual thread (as the agent runtime runs
   * calls) against a server that never answers - [[LocalProviderTestServer.holdOpen]] - and then
   * interrupted, it returns `Left(CancelledError)` promptly with the thread's interrupt flag still
   * set. Every chat provider must pass; see `docs/guide/writing-a-provider.md`.
   */
  def assertCancelsWhenInterrupted(client: LLMClient)(using pos: Position): Assertion =
    assertCallCancelsWhenInterrupted("complete")(client.complete(Conversation(Seq(UserMessage("Hello")))))

  /**
   * `embed` honours interruption, as [[assertCancelsWhenInterrupted]] does for `complete`: against
   * a server that never answers - [[LocalProviderTestServer.holdOpen]] - and interrupted, it returns
   * `Left(CancelledError)` promptly with the thread's interrupt flag still set. Every embedding
   * provider must pass.
   */
  def assertEmbeddingCancelsWhenInterrupted(provider: EmbeddingProvider)(using pos: Position): Assertion =
    assertCallCancelsWhenInterrupted("embed")(
      provider.embed(EmbeddingRequest(Seq("Hello"), EmbeddingModelConfig("test-model", 8)))
    )

  /**
   * Any client call that returns a `Result` honours interruption: `call` runs on a virtual thread
   * (as the agent runtime runs calls) against a server that never answers, is interrupted, and
   * returns `Left(CancelledError)` within 10 seconds with the thread's interrupt flag still set.
   *
   * For a call the specific checks do not cover - a reranker, a speech or image client, an MCP
   * client. Point the client at [[LocalProviderTestServer.holdOpen]].
   *
   * @param what names the call in the failure message
   */
  def assertCallCancelsWhenInterrupted(what: String)(call: => Result[?])(using pos: Position): Assertion =
    val started = new CountDownLatch(1)
    interrupted(what) {
      started.countDown()
      call
    }(started.await(5, TimeUnit.SECONDS): Unit)

  /**
   * As [[assertCallCancelsWhenInterrupted]], but the interrupt waits until `ready` returns, instead of
   * a fixed 100 ms after the call starts: for a call whose blocked state can be observed, such as one that
   * starts a program and waits for it, where an interrupt that lands before the program has run would
   * test nothing. `ready` runs on the calling thread and should fail (not return) if the state never comes.
   *
   * @param what  names the call in the failure message
   * @param call  the call; it runs on a virtual thread
   * @param ready returns once `call` is blocked
   */
  def assertCallCancelsOnceReady(what: String)(call: => Result[?])(ready: => Unit)(using pos: Position): Assertion =
    interrupted(what)(call)(ready)

  /**
   * `client.streamComplete` honours interruption mid-stream: against a server that sends one
   * event and then stalls - [[LocalProviderTestServer.streamThenHold]] - it delivers that chunk,
   * and, interrupted, returns `Left(CancelledError)` promptly with the interrupt flag set.
   */
  def assertCancelsStreamWhenInterrupted(client: LLMClient)(using pos: Position): Assertion =
    val firstChunk = new CountDownLatch(1)
    interrupted("streamComplete") {
      client.streamComplete(Conversation(Seq(UserMessage("Hello"))), onChunk = _ => firstChunk.countDown())
    } {
      if !firstChunk.await(10, TimeUnit.SECONDS) then
        failAt("streamComplete delivered no chunk before the server stalled")
    }

  /**
   * Runs `call` on a virtual thread, waits with `ready`, interrupts it, and checks it returns
   * `Left(CancelledError)` within 10 seconds with its interrupt flag set.
   */
  private def interrupted(what: String)(call: => Result[?])(ready: => Unit)(using pos: Position): Assertion =
    @volatile var outcome: Option[(Result[?], Boolean)] = None
    val worker = Thread.ofVirtual().start { () =>
      val result = call
      outcome = Some(result -> Thread.currentThread().isInterrupted)
    }
    ready
    Thread.sleep(100) // let the call block on the socket; any earlier interrupt must also cancel
    worker.interrupt()
    worker.join(10_000)
    if worker.isAlive then failAt(s"$what was still running 10 seconds after its thread was interrupted")
    outcome match
      case Some((Left(_: CancelledError), true)) => succeed
      case Some((Left(_: CancelledError), false)) =>
        failAt(
          s"$what returned CancelledError but cleared the thread's interrupt flag; restore it with Thread.currentThread().interrupt()"
        )
      case Some((Left(other), _)) =>
        failAt(
          s"$what returned Left(${other.getClass.getSimpleName}: ${other.message}) when its thread was interrupted; expected Left(CancelledError)"
        )
      case Some((Right(value), _)) =>
        failAt(s"$what returned Right($value) when its thread was interrupted; expected Left(CancelledError)")
      case None => failAt(s"$what threw instead of returning a Result when its thread was interrupted")

  /**
   * Builds an embedding provider the way `EmbeddingClient` does: resolve the descriptor for the
   * selected id in `registry`, then `build` it from `config`.
   *
   * Load `config` from HOCON with [[ProviderTestConfig.loadEmbeddings]], or construct one.
   */
  def assertBuildsEmbeddingProvider(
    descriptor: EmbeddingProviderDescriptor,
    config: EmbeddingProviderConfig
  )(using pos: Position): EmbeddingProvider =
    descriptor.build(config) match
      case Right(provider) => provider
      case Left(error) => failAt(s"${descriptor.id.asString} failed to build an embedding provider: ${error.message}")

  // ---- reference.conf ----

  /**
   * For each variable `descriptor.configSpec.apiKeyEnv` names, a chat section with no `apiKey`
   * of its own, loaded with only that variable set, gets that variable's value - so your
   * `reference.conf` binds it to `llm4s.credentials.<id>.apiKey`, and `apiKeyEnv` (which the
   * missing-key error quotes to users) names what is really bound. Passes trivially when
   * `apiKeyEnv` is empty.
   *
   * @param descriptor  the chat descriptor
   * @param extraFields HOCON for any required extras, e.g. `endpoint = "https://..."`
   * @param registry    resolves the section's provider; discovery by default
   */
  def assertCredentialBindings(
    descriptor: ProviderDescriptor,
    extraFields: String = "",
    registry: ProviderRegistry = ProviderRegistry.default
  )(using pos: Position): Assertion =
    given ProviderRegistry = registry
    CredentialsRoundTrip.chatBindings(descriptor, extraFields).foreach { (variable, key) =>
      if key != Right(Some(s"key-from-$variable")) then
        failAt(
          s"${descriptor.id.asString} declares $variable in apiKeyEnv, but with only it set a section's key is " +
            s"${describe(key)}. Bind it in reference.conf: " +
            s"llm4s.credentials.${descriptor.id.asString}.apiKey = $${?$variable}"
        )
    }
    succeed

  /**
   * [[assertCredentialBindings]] for an embedding descriptor, through an
   * `llm4s.embeddings.model = "<id>/<model>"` selection.
   *
   * @param model any model name the descriptor accepts
   */
  def assertEmbeddingCredentialBindings(
    descriptor: EmbeddingProviderDescriptor,
    model: String,
    registry: ProviderRegistry = ProviderRegistry.default
  )(using pos: Position): Assertion =
    given ProviderRegistry = registry
    CredentialsRoundTrip.embeddingBindings(descriptor, model).foreach { (variable, key) =>
      if key != Right(s"key-from-$variable") then
        failAt(
          s"${descriptor.id.asString} declares $variable in apiKeyEnv, but with only it set the embeddings key is " +
            s"${describe(key)}. Bind it in reference.conf: " +
            s"llm4s.credentials.${descriptor.id.asString}.apiKey = $${?$variable}"
        )
    }
    succeed

  /**
   * ScalaTest's `fail`, but at the caller's position (`fail` is a macro that captures its own):
   * every check takes a `Position`, so a failure points at the line in your spec rather than at
   * this file.
   */
  private[testkit] def failAt(message: String)(using pos: Position): Nothing =
    throw new TestFailedException((_: StackDepthException) => Some(message), None, pos)

  private def describe(result: Result[?]): String =
    result.fold(error => s"an error (${error.message})", value => value.toString)

/** The checks, for a spec that would rather call them than mix them in. */
object ProviderModuleChecks extends ProviderModuleChecks:

  /**
   * The model registry the checks use when given none: llm4s's bundled model metadata, with no
   * application overrides. Loaded once.
   */
  lazy val defaultModelRegistry: ModelRegistryService =
    ModelRegistryService
      .default()
      .fold(error => failAt(s"llm4s model registry failed to load: ${error.message}"), identity)

  /** A config no real provider builds, to prove a descriptor refuses what is not its own. */
  private object ForeignConfig extends ProviderConfig:
    val providerId: ProviderId                   = ProviderId("provider-testkit-foreign")
    val model: String                            = "foreign-model"
    val contextWindow: Int                       = 8192
    val reserveCompletion: Int                   = 1024
    val endpointUrl: Option[String]              = None
    def withModel(model: String): ProviderConfig = this
