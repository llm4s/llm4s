package org.llm4s.javaapi

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ GenericArrayType, Method, Modifier, ParameterizedType, Type, TypeVariable, WildcardType }
import scala.jdk.CollectionConverters._

/**
 * Exercises the public API from real Java source (`src/test/java/.../JavaInteropCheck.java`) and
 * guards the shape of the Java-visible signatures, so a Scala type leaking into the Java surface
 * is a test failure rather than a surprise for the first Java user.
 */
class JavaInteropSpec extends AnyFlatSpec with Matchers {

  // Scala `private[javaapi]` is public in bytecode, so these are knowingly Java-visible. They are
  // internal (documented as such) and allowlisted by declaring class and name, so any NEW Scala type
  // in a signature fails here - a `from` or `underlying` on another class included.
  private val internalByDesign: Set[(Class[?], String)] = Set(
    classOf[LlmResult[?]] -> "from",
    classOf[JLlmClient]   -> "underlying",
    classOf[Answer]       -> "underlying"
  )

  private def isInternal(m: Method): Boolean = internalByDesign(m.getDeclaringClass -> m.getName)

  private def answering(answer: String): LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, answer, "m", AssistantMessage(answer)))
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  "the documented Java quick-start" should "compile from Java and run end to end" in {
    val log = JavaInteropCheck.quickStart(new JLlmClient(answering("4"))).asScala.toList
    log shouldBe List("ok:4", "get:4", "len:1", "opt:4", "cf:4", "conv:4")
  }

  "the Java failure path" should "work with an LLMError implemented in Java" in {
    JavaInteropCheck.failurePath().asScala.toList shouldBe List(
      "isFailure:true",
      "orNull:null",
      "opt:false",
      "err:boom",
      "cb:boom",
      "caught:boom",
      "cf:true"
    )
  }

  "a streamed agent turn" should "compile from Java, with a lambda listener, and run end to end" in {
    val streaming = StreamFixtures.Scripted(
      onChunk => {
        onChunk(StreamedChunk(id = "c", content = Some("4")))
        Right(Completion("id", 0L, "4", "m", AssistantMessage("4")))
      },
      () => Right(Completion("id", 0L, "4", "m", AssistantMessage("4")))
    )
    val log = JavaInteropCheck
      .streaming(StreamFixtures.jAgentOf(streaming)(_.withStreaming()), new java.util.concurrent.CountDownLatch(0))
      .asScala
      .toList
    log should contain("durable")
    log should contain("delta:4")
    log.takeRight(3) shouldBe List("answer:4", "refused:true", "resume-refused:true")
  }

  it should "report a LiveGap to a Java listener" in {
    // a gap is matched with instanceof; the flood outruns a listener that waits for the run to complete
    val store = StreamFixtures.SignalsCompletion()
    val flooding = StreamFixtures.Scripted(
      onChunk => {
        (0 until 3000).foreach(i => onChunk(StreamFixtures.chunk(i)))
        Right(StreamFixtures.completion("4"))
      },
      () => Right(StreamFixtures.completion("4"))
    )
    val agent = StreamFixtures.jAgentOf(flooding)(
      _.withRuntime(org.llm4s.agent.graph.GraphRuntime(store)).withStreaming()
    )
    val log = JavaInteropCheck.streaming(agent, store.completed).asScala.toList
    log.exists(_.startsWith("gap:")) shouldBe true
    log.takeRight(3) shouldBe List("answer:4", "refused:true", "resume-refused:true")
  }

  "a suspended turn" should "be read and answered from Java: an approval round trip" in {
    import SuspensionFixtures.*
    val agent =
      agentOver(scripted(Right(calling(call("c1", "deploy", "prod"))), Right(StreamFixtures.completion("shipped"))))
    JavaInteropCheck.answering(agent, "deploy", "{}").asScala.toList shouldBe
      List("""approve:deploy:{"text":"prod"}:deploying prod""", "answer:shipped")
  }

  it should "be read and answered from Java: a question round trip" in {
    import SuspensionFixtures.*
    val agent =
      agentOver(scripted(Right(calling(call("c1", "confirm", "go"))), Right(StreamFixtures.completion("yes"))))
    JavaInteropCheck.answering(agent, "ask", """{"ok":true}""").asScala.toList shouldBe
      List("""reply:confirm:{"prompt":"really go?"}""", "answer:yes")
  }

  it should "be read and answered from Java: approvals and questions together" in {
    import SuspensionFixtures.*
    val agent = agentOver(
      scripted(
        Right(calling(call("c1", "confirm", "go"), call("c2", "deploy", "prod"))),
        Right(StreamFixtures.completion("both"))
      )
    )
    JavaInteropCheck.answering(agent, "both", """{"ok":true}""").asScala.toList shouldBe List(
      """approve:deploy:{"text":"prod"}:deploying prod""",
      """reply:confirm:{"prompt":"really go?"}""",
      "answer:both"
    )
  }

  "a failed turn" should "be recovered from Java" in {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val down  = org.llm4s.error.NetworkError("down", None, "http://x")
    def reply(): Result[Completion] =
      if (calls.getAndIncrement() == 0) Left(down) else Right(StreamFixtures.completion("back"))
    val agent = StreamFixtures.jAgentOf(new StreamFixtures.Scripted(_ => reply(), () => reply()))()
    agent.stream("java-recover", "hi", _ => ()).get().await().isFailure shouldBe true
    JavaInteropCheck.recovering(agent, "java-recover").asScala.toList shouldBe
      List("answer:back", "again-refused:true", "resume-refused:true")
  }

  /** Every class a type mentions: itself, its type arguments, bounds and array components, recursively. */
  private def mentioned(t: Type): List[Class[_]] = t match {
    case c: Class[_]          => if (c.isArray) mentioned(c.getComponentType) else List(c)
    case p: ParameterizedType => mentioned(p.getRawType) ++ p.getActualTypeArguments.toList.flatMap(mentioned)
    case w: WildcardType      => (w.getUpperBounds ++ w.getLowerBounds).toList.flatMap(mentioned)
    case g: GenericArrayType  => mentioned(g.getGenericComponentType)
    case _: TypeVariable[_]   => Nil
    case _                    => Nil
  }

  /** A Scala library or ujson type: what the facade keeps out of Java signatures. */
  private def scalaOnly(c: Class[_]): Boolean = c.getName.startsWith("scala.") || c.getName.startsWith("ujson.")

  "the interop guard" should "see a Scala type inside a generic signature" in {
    val m = classOf[JavaInteropSpec].getDeclaredMethod("leaky", classOf[java.util.List[_]])
    m.getGenericParameterTypes.toList.flatMap(mentioned).exists(scalaOnly) shouldBe true
  }

  def leaky(xs: java.util.List[(String, ujson.Value)]): Int = xs.size

  /** The facade's Java-visible classes: every one a Java caller calls or implements. */
  private def facade: List[Class[_]] = List(
    classOf[LlmResult[_]],
    classOf[JLlmClient],
    classOf[JAgent],
    Class.forName("org.llm4s.javaapi.Llm4s"),
    classOf[ConversationBuilder],
    classOf[LlmException],
    classOf[AgentStream],
    classOf[AgentStreamListener],
    Class.forName("org.llm4s.javaapi.StreamEvents"),
    classOf[Answer],
    classOf[PendingInterrupt],
    classOf[InterruptKind],
    classOf[JAgentResult],
    classOf[JAgentStatus],
    classOf[AgentStatusKind],
    classOf[JMessage],
    classOf[JMessageRole],
    classOf[JToolCall],
    classOf[JUsageSummary],
    classOf[JModelUsage]
  )

  "the public Java-visible surface" should "not expose scala.* types outside the allowlisted internals" in {
    val offenders = for {
      cls <- facade
      m   <- cls.getMethods.toList if Modifier.isPublic(m.getModifiers) && m.getDeclaringClass == cls
      if !isInternal(m)
      t <- (m.getGenericReturnType :: m.getGenericParameterTypes.toList).flatMap(mentioned)
      if scalaOnly(t)
    } yield s"${cls.getSimpleName}.${m.getName}: ${t.getName}"
    offenders shouldBe Nil
  }

  /**
   * Types a Java caller is handed but does not read through the facade, where the walk below stops,
   * each with why. Anything else of llm4s's that a facade method returns is walked.
   */
  private def boundary: Map[String, String] = Map(
    "org.llm4s.error.LLMError" ->
      "LlmException.error(): the error taxonomy, matched with instanceof; its Java view is #1487",
    "org.llm4s.llmconnect.model.Conversation" ->
      "ConversationBuilder.build(): handed back to JLlmClient.complete, not read; the client's inputs are #1488",
    "org.llm4s.agent.graph.StreamEvent" ->
      "AgentStreamListener.onEvent: read with StreamEvents.decode and instanceof, as the streaming guide shows"
  )

  /** Interfaces a Java caller implements: the arguments of their methods are values handed to it. */
  private def callbacks: Set[Class[_]] = Set(classOf[AgentStreamListener])

  /**
   * Walks every type a Java caller can be handed from `roots` - each public method's return type, and
   * a callback's arguments - into llm4s's own types, returning each `scala.*` or `ujson.*` type met
   * with the path to it. Stops at [[boundary]] and at the `private[javaapi]` internals.
   */
  private def reachableLeaks(roots: List[Class[_]], boundary: Set[String]): List[String] = {
    def handed(cls: Class[_]): List[(String, Class[_])] =
      for {
        m <- cls.getMethods.toList if Modifier.isPublic(m.getModifiers) && !isInternal(m)
        types = m.getGenericReturnType :: (if (callbacks(cls)) m.getGenericParameterTypes.toList else Nil)
        t <- types.flatMap(mentioned)
      } yield s"${cls.getSimpleName}.${m.getName}" -> t

    @scala.annotation.tailrec
    def walk(todo: List[(String, Class[_])], seen: Set[Class[_]], leaks: List[String]): List[String] = todo match {
      case Nil => leaks.reverse
      case (path, cls) :: rest =>
        if (scalaOnly(cls)) walk(rest, seen, s"$path: ${cls.getName}" :: leaks)
        else if (seen(cls) || !cls.getName.startsWith("org.llm4s.") || boundary(cls.getName)) walk(rest, seen, leaks)
        else walk(rest ++ handed(cls).map((m, t) => s"$path -> $m" -> t), seen + cls, leaks)
    }
    walk(roots.map(c => c.getSimpleName -> c), Set.empty, Nil)
  }

  "the reachability guard" should "find a Scala type reached through a type a facade method returns" in {
    reachableLeaks(List(classOf[JavaInteropSpec.Holder]), Set.empty) should contain(
      "Holder -> Holder.inner -> Inner.value: scala.Option"
    )
    reachableLeaks(List(classOf[JavaInteropSpec.Holder]), Set(classOf[JavaInteropSpec.Inner].getName)) shouldBe Nil
  }

  it should "find one in a callback's argument, and in a Scala case class's own members" in {
    reachableLeaks(List(classOf[AgentStreamListener]), Set.empty).exists(_.contains("onEvent")) shouldBe true
    reachableLeaks(List(classOf[JavaInteropSpec.Inner]), Set.empty) should not be empty
  }

  "no scala.* or ujson.* type" should "be reachable from any value the Java facade hands a caller" in {
    reachableLeaks(facade, boundary.keySet) shouldBe Nil
  }

  "the agent facade" should "hand Java callers JAgentResult from every turn: run, continue, resume, recover, await, onComplete" in {
    def returns(m: java.lang.reflect.Method): List[Class[_]] = mentioned(m.getGenericReturnType)
    val turns = List(
      classOf[JAgent].getMethod("run", classOf[String]),
      classOf[JAgent].getMethod("continueConversation", classOf[JAgentResult], classOf[String]),
      classOf[JAgent].getMethod("resume", classOf[String], classOf[java.util.List[_]]),
      classOf[JAgent].getMethod("recover", classOf[String]),
      classOf[AgentStream].getMethod("await")
    )
    turns.foreach(m => withClue(m.toString)(returns(m) shouldBe List(classOf[LlmResult[_]], classOf[JAgentResult])))
    classOf[AgentStreamListener].getMethod("onComplete", classOf[JAgentResult]) should not be null
    classOf[JAgent].getMethod("forget", classOf[JAgentResult]) should not be null
    classOf[JAgent].getMethod("forget", classOf[String]) should not be null
    classOf[JAgent].getMethod("pending", classOf[JAgentResult]) should not be null
  }
}

object JavaInteropSpec {

  /** A facade-like type whose leak is one step away, for the reachability guard's own test. */
  final class Holder { def inner: Inner = new Inner(None) }

  /** A case class: a Scala `Option` field, and `Product` members, both leaks. */
  final case class Inner(value: Option[String])
}
