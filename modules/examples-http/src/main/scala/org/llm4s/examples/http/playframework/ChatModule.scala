package org.llm4s.examples.http.playframework

import org.apache.pekko.actor.ActorSystem
import org.llm4s.config.Llm4sConfig
import org.llm4s.examples.http.{ ChatAgent, ServerSettings }
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.pekko.{ AgentPekko, LLMClientPekko, LLMException }
import org.llm4s.types.Result
import play.api.{ Configuration, Environment }
import play.api.inject.{ ApplicationLifecycle, Binding, Module }
import play.api.libs.concurrent.CustomExecutionContext

import javax.inject.{ Inject, Provider, Singleton }
import scala.concurrent.Future
import scala.util.Try

/**
 * The Play app's Guice bindings, enabled by `play.modules.enabled` in `application.conf`: the `LLMClient`, closed when
 * the application stops, and the agent every request runs over it. Both are eager singletons, so a provider section
 * that cannot be loaded stops the application from starting instead of failing its first request.
 */
final class ChatModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[?]] =
    Seq(
      bind[LLMClient].toProvider[LLMClientProvider].eagerly(),
      bind[AgentPekko].toProvider[AgentPekkoProvider].eagerly()
    )
}

object ChatModule {

  /** Where the app's own settings are: `fake`, `heartbeat` and the `blocking-dispatcher`. */
  val ConfigPath = "examples.chat"

  /**
   * The client the app serves: the fake one when `examples.chat.fake` is set (`--fake`), else the provider section
   * `llm4s.providers.provider` names. That `llm4s` block is read from Play's own configuration (`Llm4sConfig.from`), so
   * it lives in Play's `application.conf`; JVM system properties still override it, and each llm4s module's
   * `reference.conf` sits beneath it.
   */
  def client(configuration: Configuration): Result[LLMClient] =
    if (configuration.get[Boolean](s"$ConfigPath.fake")) Right(ServerSettings.fakeClient)
    else
      for {
        settings <- Llm4sConfig.from(configuration.underlying)
        registry <- settings.modelRegistryService()
        provider <- settings.defaultProvider()
        client   <- LLMConnect.getClient(provider)(using registry)
      } yield client

  /** `client`, closed by a stop hook of `lifecycle`: when the application stops, after the server stops serving. */
  def closedOnStop(client: LLMClient, lifecycle: ApplicationLifecycle): LLMClient = {
    lifecycle.addStopHook(() => Future.fromTry(Try(client.close())))
    client
  }
}

/** The `LLMClient` of [[ChatModule.client]], closed by a stop hook when the application stops. */
@Singleton
final class LLMClientProvider @Inject() (configuration: Configuration, lifecycle: ApplicationLifecycle)
    extends Provider[LLMClient] {

  // Guice reports a provider's failure by its exception: the application does not start, with the error's message.
  private lazy val client: LLMClient =
    ChatModule.closedOnStop(
      ChatModule.client(configuration).fold(error => throw new LLMException(error), identity),
      lifecycle
    )

  override def get(): LLMClient = client
}

/** The agent of [[org.llm4s.examples.http.ChatAgent.configure ChatAgent.configure]] over the bound `LLMClient`. */
@Singleton
final class AgentPekkoProvider @Inject() (client: LLMClient) extends Provider[AgentPekko] {

  private lazy val agent: AgentPekko =
    LLMClientPekko(client).agent("chat")(ChatAgent.configure).fold(error => throw new LLMException(error), identity)

  override def get(): AgentPekko = agent
}

/**
 * The dispatcher `POST /chat` waits for its run on (`examples.chat.blocking-dispatcher` in `application.conf`), so a
 * provider call that blocks never holds a thread of Play's default dispatcher, which serves every request.
 */
@Singleton
final class ChatExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, s"${ChatModule.ConfigPath}.blocking-dispatcher")
