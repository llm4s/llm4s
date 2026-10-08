package org.llm4s.javaapi

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ GenericArrayType, Modifier, ParameterizedType, Type, TypeVariable, WildcardType }
import scala.jdk.CollectionConverters._

/**
 * Exercises the public API from real Java source (`src/test/java/.../JavaInteropCheck.java`) and
 * guards the shape of the Java-visible signatures, so a Scala type leaking into the Java surface
 * is a test failure rather than a surprise for the first Java user.
 */
class JavaInteropSpec extends AnyFlatSpec with Matchers {

  // Scala `private[javaapi]` is public in bytecode, so these are knowingly Java-visible. They are
  // internal (documented as such) and allowlisted so any NEW Scala type in a signature fails here.
  private val internalByDesign = Set("from", "underlying")

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
    agent.stream(org.llm4s.agent.graph.ThreadId("java-recover"), "hi", _ => ()).get().await().isFailure shouldBe true
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

  "the public Java-visible surface" should "not expose scala.* types outside the allowlisted internals" in {
    val classes = List(
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
      classOf[InterruptKind]
    )
    val offenders = for {
      cls <- classes
      m   <- cls.getMethods.toList if Modifier.isPublic(m.getModifiers) && m.getDeclaringClass == cls
      if !internalByDesign(m.getName)
      t <- (m.getGenericReturnType :: m.getGenericParameterTypes.toList).flatMap(mentioned)
      if scalaOnly(t)
    } yield s"${cls.getSimpleName}.${m.getName}: ${t.getName}"
    offenders shouldBe Nil
  }
}
