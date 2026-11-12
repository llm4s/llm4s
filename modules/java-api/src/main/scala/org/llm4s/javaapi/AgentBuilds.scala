package org.llm4s.javaapi

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ Checkpointer, GraphRuntime }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, ContextWindowMiddleware, GuardrailMiddleware }
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

import scala.jdk.CollectionConverters.*

/** What [[JAgentBuilder]] keeps and what its `build()` does with it (#2476). Internal to the facade. */
private[javaapi] object AgentBuilds {

  /** The settings so far; each is a failed result when its setter was given something it refuses. */
  final case class Spec(
    id: String,
    client: JLlmClient,
    systemPrompt: Result[Option[String]] = Right(None),
    tools: Result[Vector[JTool]] = Right(Vector.empty),
    input: Result[Vector[JGuardrail]] = Right(Vector.empty),
    output: Result[Vector[JOutputGuardrail]] = Right(Vector.empty),
    contextWindow: Result[Option[org.llm4s.agent.ContextWindowConfig]] = Right(None),
    streaming: Boolean = false,
    maxSteps: Option[Int] = None,
    checkpointer: Result[Option[Checkpointer]] = Right(None),
    tracing: Result[Option[Tracing]] = Right(None)
  )

  /** A copy of `list`, or a failure when it, or one of its elements, is `null`. */
  def elements[A](list: java.util.List[? <: A], field: String, element: String): Result[Vector[A]] =
    Option(list).toRight(ValidationError.required(field)).flatMap { l =>
      val all: Vector[A] = l.asScala.toVector
      Either.cond(!all.contains(null), all, ValidationError(field, s"$element is null"))
    }

  private def all[A, B](as: Vector[A])(f: A => Result[B]): Result[Vector[B]] =
    as.foldLeft[Result[Vector[B]]](Right(Vector.empty))((acc, a) => acc.flatMap(done => f(a).map(done :+ _)))

  def build(spec: Spec): Result[JAgent] =
    for {
      id     <- Option(spec.id).toRight(ValidationError.required("id"))
      client <- Option(spec.client).toRight(ValidationError.required("client"))
      prompt <- spec.systemPrompt
      tools  <- spec.tools
      _ <- tools
        .groupBy(_.name())
        .collectFirst { case (n, same) if same.size > 1 => n }
        .toLeft(())
        .left
        .map(n => ValidationError("tools", s"two tools are named '$n'"))
      input  <- spec.input.flatMap(all(_)(_.input))
      output <- spec.output.flatMap(all(_)(_.output))
      window <- spec.contextWindow
      saved  <- spec.checkpointer
      traced <- spec.tracing
      middleware = Vector[Option[AgentMiddleware]](
        Option.when(input.nonEmpty || output.nonEmpty)(new GuardrailMiddleware(input, output)),
        window.map(new ContextWindowMiddleware(_))
      ).flatten
      base     = Agent.builder(id, client.underlying).withTools(new ToolRegistry(tools.map(_.function)))
      prompted = prompt.fold(base)(base.withSystemPrompt)
      streamed = if (spec.streaming) prompted.withStreaming() else prompted
      stepped  = spec.maxSteps.fold(streamed)(streamed.withMaxSteps)
      stored   = saved.fold(stepped)(c => stepped.withRuntime(new GraphRuntime(c)))
      observed = traced.fold(stored)(stored.withTracing)
      agent <- observed.withMiddleware(middleware*).build()
    } yield new JAgent(FacadeToken.INSTANCE, Right(agent))
}
