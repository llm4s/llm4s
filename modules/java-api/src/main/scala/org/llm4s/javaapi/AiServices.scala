package org.llm4s.javaapi

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ RunConfig, ThreadId }
import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ProcessingError, ValidationError }
import org.llm4s.javaapi.services.{ ChatMemoryStore, ChatTurn, MemoryId, SystemMessage, UserMessage, V }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  Message,
  SystemMessage as SystemMsg,
  UserMessage as UserMsg
}
import org.llm4s.toolapi.{ ObjectSchema, Schema, ToolRegistry }
import org.llm4s.types.Result

import java.lang.reflect.{ InvocationHandler, Method, Modifier, Parameter, ParameterizedType, Proxy, Type }
import java.util.UUID
import java.util.concurrent.{ CompletableFuture, Flow }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * What `AiServiceBuilder` keeps and what its `build()` makes (#2277): a plan per interface method, checked once, and
 * the `java.lang.reflect.Proxy` handler that runs a call from its plan. Internal to the facade.
 */
private[javaapi] object AiServices {

  /** The builder's settings so far. */
  final case class Spec[T](
    iface: Class[T],
    client: Option[JLlmClient] = None,
    tools: Vector[AnyRef] = Vector.empty,
    store: ChatMemoryStore = ChatMemoryStore.inMemory()
  ) {
    def withClient(c: JLlmClient): Spec[T]          = copy(client = Some(c))
    def withTools(objects: Array[AnyRef]): Spec[T]  = copy(tools = objects.toVector)
    def withStore(memory: ChatMemoryStore): Spec[T] = copy(store = memory)
  }

  /** What `AiServiceBuilder.build()` returns: the service, or the refusal thrown. */
  def built[T](spec: Spec[T]): T = build(spec).fold(e => throw new LlmException(e), identity)

  /** How a method hands its answer back. */
  private enum Wrapper:
    case Plain, InResult, InFuture, Streamed

  /** What a method's answer is: the text, the whole completion, or a record or enum read from a JSON answer. */
  private enum Shape:
    case Text, Whole
    case Structured(schema: ObjectSchema[ujson.Value], decode: ujson.Value => Either[String, AnyRef])

  private enum UserSource:
    case Template(text: String)
    case Argument(index: Int)

  private enum Role:
    case User, Memory
    case Var(name: String)

  /** One method, checked: where each part of its prompt comes from, and what it returns. */
  final private case class Plan(
    name: String,
    system: Option[String],
    user: UserSource,
    vars: Map[String, Int],
    memory: Option[Int],
    wrapper: Wrapper,
    shape: Shape
  )

  /** A call's prompt, rendered from its arguments. */
  final private case class Prompt(system: Option[String], user: String, memory: Option[AnyRef])

  private val Placeholder = """\{\{\s*([^{}\s]+)\s*\}\}""".r

  /** A JSON answer wrapped in a markdown fence, which models often add even when told not to. */
  private val Fence = """(?s)^\s*```(?:json)?\s*(.*?)\s*```\s*$"""

  private val AgentId = "ai-service"

  def build[T](spec: Spec[T]): Result[T] = {
    val iface = spec.iface
    for {
      _      <- Either.cond(iface.isInterface, (), ValidationError(iface.getName, "is not an interface"))
      client <- spec.client.toRight(ValidationError.required("client"))
      tools  <- resolved(spec.tools)
      // the agent a call with tools builds, built once here so that tools that cannot be offered together fail now
      _     <- AgentBuilds.build(AgentBuilds.Spec(AgentId, client, tools = Right(tools)))
      plans <- planned(iface, tools.nonEmpty)
    } yield iface.cast(
      Proxy.newProxyInstance(iface.getClassLoader, Array(iface), new Handler(iface, plans, client, tools, spec.store))
    )
  }

  /** Each argument of `tools(...)`: a [[JTool]] as it is, any other object as its `@Tool` methods. */
  private def resolved(objects: Vector[AnyRef]): Result[Vector[JTool]] =
    objects.foldLeft[Result[Vector[JTool]]](Right(Vector.empty)) { (acc, target) =>
      acc.flatMap { done =>
        target match {
          case tool: JTool => Right(done :+ tool)
          case other =>
            val read = JTools.fromAnnotated(other)
            Either.cond(read.isSuccess, done ++ read.get().asScala, read.getError().error)
        }
      }
    }

  private def planned(iface: Class[?], tools: Boolean): Result[Map[Method, Plan]] = {
    val methods = iface.getMethods.toList
      .filterNot(m => m.isDefault || Modifier.isStatic(m.getModifiers))
      .sortBy(_.getName)
    val plans = methods.map(m => m -> plan(iface, m, tools))
    plans.flatMap(_._2.left.toSeq.flatten) match {
      case Nil      => Right(plans.collect { case (m, Right(p)) => m -> p }.toMap)
      case problems => Left(ValidationError(iface.getName, problems.mkString("; ")))
    }
  }

  private def plan(iface: Class[?], m: Method, tools: Boolean): Either[List[String], Plan] = {
    val name   = s"${iface.getSimpleName}.${m.getName}"
    val params = m.getParameters.toList
    val roles  = params.map(rolesOf)
    val arity = params.zip(roles).zipWithIndex.collect {
      case ((p, found), i) if found.size != 1 =>
        s"$name: parameter ${i + 1} (${p.getType.getSimpleName}) needs exactly one of @UserMessage, @V and @MemoryId"
    }
    val single = roles.zipWithIndex.collect { case (List(role), i) => (role, i) }
    val vars   = single.collect { case (Role.Var(v), i) => (v, i) }
    val duplicates = vars.groupBy(_._1).toList.sortBy(_._1).collect {
      case (v, uses) if uses.size > 1 => s"$name: @V(\"$v\") names more than one parameter"
    }
    val memory        = single.collect { case (Role.Memory, i) => i }
    val memoryProblem = Option.when(memory.size > 1)(s"$name: has more than one @MemoryId parameter").toList
    val system = Option(m.getAnnotation(classOf[SystemMessage]))
      .orElse(Option(iface.getAnnotation(classOf[SystemMessage])))
      .map(_.value)
    val template = Option(m.getAnnotation(classOf[UserMessage])).map(_.value)
    val user: Either[String, UserSource] = (template, single.collect { case (Role.User, i) => i }) match {
      case (Some(t), Nil) if t.trim.isEmpty => Left(s"$name: its @UserMessage template is blank")
      case (Some(t), Nil)                   => Right(UserSource.Template(t))
      case (None, List(i))                  => Right(UserSource.Argument(i))
      case (None, Nil) => Left(s"$name: has no user message: annotate the method or one parameter @UserMessage")
      case _ =>
        Left(s"$name: has more than one user message: annotate the method or one parameter @UserMessage, not both")
    }
    val named = vars.map(_._1).toSet
    val unknown = (system.toList ++ template.toList)
      .flatMap(t => Placeholder.findAllMatchIn(t).map(_.group(1)))
      .distinct
      .filterNot(named)
      .map(p => s"$name: {{$p}} is named by no @V parameter")
    val returns = returnsOf(m.getGenericReturnType).left.map(reason => s"$name: $reason")
    val conflicts = returns.toSeq.flatMap { (wrapper, shape) =>
      List(
        Option.when(wrapper == Wrapper.Streamed && tools)(
          s"$name: a Flow.Publisher method cannot use tools, as streaming asks the model once"
        ),
        Option.when(wrapper == Wrapper.Streamed && memory.nonEmpty)(
          s"$name: a Flow.Publisher method cannot have a @MemoryId parameter"
        ),
        Option.when(shape == Shape.Whole && tools)(
          s"$name: a JCompletion method cannot use tools, as an agent turn makes more than one completion"
        )
      ).flatten
    }
    val problems = arity ++ duplicates ++ memoryProblem ++ user.left.toSeq ++ unknown ++ returns.left.toSeq ++ conflicts
    (user, returns) match {
      case (Right(u), Right((wrapper, shape))) if problems.isEmpty =>
        Right(Plan(name, system, u, vars.toMap, memory.headOption, wrapper, shape))
      case _ => Left(problems)
    }
  }

  private def rolesOf(p: Parameter): List[Role] =
    List(
      Option(p.getAnnotation(classOf[UserMessage])).map(_ => Role.User),
      Option(p.getAnnotation(classOf[V])).map(v => Role.Var(v.value)),
      Option(p.getAnnotation(classOf[MemoryId])).map(_ => Role.Memory)
    ).flatten

  private def returnsOf(t: Type): Either[String, (Wrapper, Shape)] = {
    val found = t match {
      case p: ParameterizedType =>
        val arg = p.getActualTypeArguments.head
        p.getRawType match {
          case raw if raw == classOf[LlmResult[?]]         => shapeOf(arg).map(_.map((Wrapper.InResult, _)))
          case raw if raw == classOf[CompletableFuture[?]] => shapeOf(arg).map(_.map((Wrapper.InFuture, _)))
          case raw if raw == classOf[Flow.Publisher[?]] && arg == classOf[String] =>
            Some(Right((Wrapper.Streamed, Shape.Text)))
          case _ => None
        }
      case other => shapeOf(other).map(_.map((Wrapper.Plain, _)))
    }
    found.getOrElse(
      Left(
        s"returns ${t.getTypeName}, which an AI service method cannot: return String, a record, an enum, JCompletion, " +
          "LlmResult<T> or CompletableFuture<T> of one of these, or Flow.Publisher<String>"
      )
    )
  }

  /** The shape of a class a method may return, or `None` for any other type. */
  private def shapeOf(t: Type): Option[Either[String, Shape]] = t match {
    case c: Class[?] if c == classOf[String]      => Some(Right(Shape.Text))
    case c: Class[?] if c == classOf[JCompletion] => Some(Right(Shape.Whole))
    case c: Class[?] if c.isEnum                  => Some(Right(enumShape(c)))
    case c: Class[?] if c.isRecord =>
      Some(
        JavaRecords
          .schema(c)
          .map(schema => Shape.Structured(schema, json => JavaRecords.read(c, json).map(_.asInstanceOf[AnyRef])))
      )
    case _ => None
  }

  /** An enum answer: structured output allows only an object at the root, so the constant is its field `value`. */
  private def enumShape(c: Class[?]): Shape = {
    val names = c.getEnumConstants.toList.map(_.asInstanceOf[Enum[?]].name)
    val schema = Schema
      .`object`[ujson.Value](c.getSimpleName)
      .withProperty(Schema.property("value", Schema.string(c.getSimpleName).withEnum(names)))
    Shape.Structured(
      schema,
      json =>
        json.objOpt
          .flatMap(_.get("value"))
          .toRight("the answer has no field 'value'")
          .flatMap(v => JavaRecords.read(c, v).map(_.asInstanceOf[AnyRef]))
    )
  }

  final private class Handler(
    iface: Class[?],
    plans: Map[Method, Plan],
    client: JLlmClient,
    tools: Vector[JTool],
    store: ChatMemoryStore
  ) extends InvocationHandler {

    override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = {
      val arguments = Option(args).fold(Vector.empty[AnyRef])(_.toVector)
      plans.get(method) match {
        case Some(plan)               => call(plan, arguments)
        case None if method.isDefault => InvocationHandler.invokeDefault(proxy, method, arguments*)
        case None =>
          method.getName match {
            case "equals"   => java.lang.Boolean.valueOf(proxy.eq(arguments.head))
            case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
            case _          => s"AiService(${iface.getName})"
          }
      }
    }

    private def call(plan: Plan, args: Vector[AnyRef]): AnyRef = plan.wrapper match {
      case Wrapper.Plain    => answer(plan, args).fold(e => throw new LlmException(e), identity)
      case Wrapper.InResult => FacadeInternals.result(answer(plan, args))
      case Wrapper.InFuture =>
        FacadeAsync.run(client.asyncExecutor, plan.name)(FacadeInternals.result(answer(plan, args)))
      case Wrapper.Streamed =>
        new CompletionPublisher(
          prompt(plan, args).map(p => (client.underlying, conversation(p, Nil), CompletionOptions())),
          CompletionPublisher.BufferSize
        )
    }

    /** The answer of one call, never thrown: an exception of the client or the store is a failed result. */
    private def answer(plan: Plan, args: Vector[AnyRef]): Result[AnyRef] =
      CancelledError.attempt(plan.name)(
        Safety
          .safely {
            for {
              p <- prompt(plan, args)
              history = p.memory.toList.flatMap(id =>
                store.load(id).asScala.toList.flatMap(t => List(UserMsg(t.userMessage), AssistantMessage(t.answer)))
              )
              reply <- if (tools.isEmpty) model(plan, p, history) else agentTurn(plan, p, history)
              _ = p.memory.foreach(id => store.append(id, new ChatTurn(p.user, reply._1)))
            } yield reply._2
          }
          .flatMap(identity)
      )

    private def prompt(plan: Plan, args: Vector[AnyRef]): Result[Prompt] =
      args.indexOf(null) match {
        case -1 =>
          val values = plan.vars.map((v, i) => v -> String.valueOf(args(i)))
          val user = plan.user match {
            case UserSource.Template(t) => render(t, values)
            case UserSource.Argument(i) => String.valueOf(args(i))
          }
          Right(Prompt(plan.system.map(render(_, values)), user, plan.memory.map(args)))
        case i => Left(ValidationError(plan.name, s"argument ${i + 1} is null"))
      }

    /** `{{name}}` replaced by its value, in one pass: a value that holds `{{...}}` is not expanded again. */
    private def render(template: String, values: Map[String, String]): String =
      Placeholder.replaceAllIn(template, m => scala.util.matching.Regex.quoteReplacement(values(m.group(1))))

    private def conversation(p: Prompt, history: List[Message]): Conversation =
      Conversation(p.system.map(SystemMsg(_)).toList ++ history :+ UserMsg(p.user))

    /** One model call; the reply as (what memory keeps, what the method returns). */
    private def model(plan: Plan, p: Prompt, history: List[Message]): Result[(String, AnyRef)] = {
      val sent = conversation(p, history)
      plan.shape match {
        case Shape.Structured(schema, decode) =>
          for {
            json  <- client.underlying.completeStructured[ujson.Value](sent, schema)
            value <- decode(json).left.map(ValidationError("structured_output", _))
          } yield (json.render(), value)
        case shape =>
          client.underlying
            .complete(sent, CompletionOptions())
            .map(c => (c.content, if (shape == Shape.Text) c.content else FacadeInternals.completion(c)))
      }
    }

    /**
     * One agent turn, which runs the tools the model asks for, on a new agent and so a new in-memory runtime: nothing
     * outlives the call but what the memory store keeps. A structured answer is asked for in the system prompt, since
     * the agent's model calls are plain completions.
     */
    private def agentTurn(plan: Plan, p: Prompt, history: List[Message]): Result[(String, AnyRef)] = {
      val structured = plan.shape match {
        case s: Shape.Structured => Some(s)
        case _                   => None
      }
      val system = (p.system.toList ++ structured.map(s => jsonInstruction(s.schema))).mkString("\n\n")
      val base   = Agent.builder(AgentId, client.underlying).withTools(new ToolRegistry(tools.map(_.function)))
      for {
        agent  <- (if (system.isEmpty) base else base.withSystemPrompt(system)).build()
        result <- agent.run(ThreadId(UUID.randomUUID().toString), p.user, RunConfig(), history)
        text <- result.answer.toRight(
          ProcessingError(plan.name, s"the agent turn ended without an answer: ${result.status}")
        )
        reply <- structured.fold[Result[(String, AnyRef)]](Right((text, text)))(s => parsed(s, text))
      } yield reply
    }

    private def parsed(s: Shape.Structured, text: String): Result[(String, AnyRef)] =
      for {
        json <- Try(ujson.read(text.replaceAll(Fence, "$1"))).toEither.left.map(e =>
          ValidationError("structured_output", s"the answer is not JSON: ${e.getMessage}")
        )
        value <- s.decode(json).left.map(ValidationError("structured_output", _))
      } yield (json.render(), value)

    private def jsonInstruction(schema: ObjectSchema[ujson.Value]): String =
      "Give your final answer as only a JSON object, with no other text, that matches this JSON Schema: " +
        schema.toJsonSchema(strict = true).render()
  }
}
