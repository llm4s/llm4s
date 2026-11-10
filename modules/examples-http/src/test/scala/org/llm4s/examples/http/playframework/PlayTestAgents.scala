package org.llm4s.examples.http.playframework

import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Source
import org.llm4s.agent.{ AgentResult, AgentRun }
import org.llm4s.agent.graph.{ EventHandlers, EventType, InterruptId, RunConfig, ThreadId }
import org.llm4s.examples.http.ChatAgent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.pekko.{ AgentPekko, AgentStreamItem, LLMClientPekko }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

/** Applications and agents the Play app's specs share. */
object PlayTestAgents {

  /** The app's agent over `client`, as [[AgentPekkoProvider]] builds it. */
  def agentOver(client: LLMClient): AgentPekko =
    LLMClientPekko(client)
      .agent("chat")(ChatAgent.configure)
      .fold(e => sys.error(s"could not build the agent: $e"), identity)

  /**
   * The app's application in test mode, serving `agent`; the fake client is configured, so no provider section is
   * loaded. `heartbeat` is `examples.chat.heartbeat`.
   */
  def application(agent: AgentPekko, heartbeat: FiniteDuration = 15.seconds): Application =
    GuiceApplicationBuilder()
      .configure("examples.chat.fake" -> true, "examples.chat.heartbeat" -> s"${heartbeat.toMillis} ms")
      .overrides(bind[AgentPekko].toInstance(agent))
      .build()

  /** An `AgentPekko` that fails with a plain exception, as no `llm4s-pekko` stream or `Future` does. */
  object Failing extends Unsupported {
    override def run(query: String, config: RunConfig)(using ec: ExecutionContext): Future[AgentResult] =
      Future.failed(new RuntimeException("boom"))
    override def stream(
      threadId: ThreadId,
      query: String,
      config: RunConfig,
      bufferSize: Int
    ): Source[AgentStreamItem, Future[Done]] = failed(new IllegalStateException())
  }

  /** An `AgentPekko` whose stream is quiet for a while, then ends with a real result. */
  object Slow extends Unsupported {
    private lazy val agent = agentOver(ScriptedLLMClient.always(Reply.text("late")))
    override def stream(
      threadId: ThreadId,
      query: String,
      config: RunConfig,
      bufferSize: Int
    ): Source[AgentStreamItem, Future[Done]] =
      agent.stream(threadId, query, config, bufferSize).initialDelay(300.millis)
  }

  trait Unsupported extends AgentPekko {
    private def no[A]: Future[A] = Future.failed(new UnsupportedOperationException)
    protected def failed[A](error: Throwable): Source[A, Future[Done]] =
      Source.failed[A](error).mapMaterializedValue(_ => Future.successful(Done))

    def run(query: String, config: RunConfig)(using ec: ExecutionContext): Future[AgentResult] = no
    def continueConversation(previous: AgentResult, query: String, config: RunConfig)(using
      ec: ExecutionContext
    ): Future[AgentResult] = no
    def recover(threadId: ThreadId, config: RunConfig)(using ec: ExecutionContext): Future[AgentResult] = no
    def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig)(using
      ec: ExecutionContext
    ): Future[AgentResult] = no
    def stream(threadId: ThreadId, query: String, config: RunConfig, bufferSize: Int) =
      failed[AgentStreamItem](new UnsupportedOperationException)
    def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig, bufferSize: Int) =
      failed[AgentStreamItem](new UnsupportedOperationException)
    def streamRecover(threadId: ThreadId, config: RunConfig, bufferSize: Int) =
      failed[AgentStreamItem](new UnsupportedOperationException)
    def events[A](run: AgentRun, eventType: EventType[A]): Source[A, Future[Done]] =
      failed(new UnsupportedOperationException)
    def events[A](run: AgentRun, eventType: EventType[A], bufferSize: Int): Source[A, Future[Done]] =
      failed(new UnsupportedOperationException)
    def events[R](run: AgentRun, handlers: EventHandlers[R], bufferSize: Int): Source[R, Future[Done]] =
      failed(new UnsupportedOperationException)
    def nestedEvents[R](run: AgentRun, handlers: EventHandlers[R], bufferSize: Int): Source[R, Future[Done]] =
      failed(new UnsupportedOperationException)
  }
}
