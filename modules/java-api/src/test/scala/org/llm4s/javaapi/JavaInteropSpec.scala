package org.llm4s.javaapi

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
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
    classOf[JLlmClient]         -> "underlying",
    classOf[Answer]             -> "underlying",
    classOf[JCompletionOptions] -> "underlying"
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
    val log = JavaInteropCheck.quickStart(Llm4s.wrapClient(answering("4"))).asScala.toList
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
    classOf[JCompletionOptions],
    classOf[JCompletionOptions.Builder],
    classOf[JReasoningEffort],
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
    classOf[BreakpointPhase],
    classOf[JAgentResult],
    classOf[JAgentStatus],
    classOf[AgentStatusKind],
    classOf[JMessage],
    classOf[JMessageRole],
    classOf[JToolCall],
    classOf[JUsageSummary],
    classOf[JModelUsage],
    classOf[JCompletion],
    classOf[JTokenUsage],
    classOf[LlmErrorKind],
    classOf[JEmbeddingClient],
    classOf[JEmbeddings],
    classOf[JEmbeddingPurpose]
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
      ("LlmException.error(): the error taxonomy, matched with instanceof for detail; its Java view is LlmException's " +
        "getKind(), isRecoverable(), getRetryAfter() and getStatusCode() (#1487)"),
    "org.llm4s.llmconnect.model.Conversation" ->
      "ConversationBuilder.build(): handed back to JLlmClient.complete, not read",
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

  "the client facade" should "hand Java callers JCompletion from every completion overload, and Java types from LlmException" in {
    def returns(m: java.lang.reflect.Method): List[Class[_]] = mentioned(m.getGenericReturnType)
    val completions = List(
      classOf[JLlmClient].getMethod("completion", classOf[String]),
      classOf[JLlmClient].getMethod("completion", classOf[Conversation]),
      classOf[JLlmClient].getMethod("completion", classOf[Conversation], classOf[JCompletionOptions])
    )
    completions.foreach(m =>
      withClue(m.toString)(returns(m) shouldBe List(classOf[LlmResult[_]], classOf[JCompletion]))
    )
    // one options overload, so a literal null options needs no cast
    classOf[JLlmClient].getMethods.count(m => m.getName == "completion" && m.getParameterCount == 2) shouldBe 1

    returns(classOf[LlmException].getMethod("getKind")) shouldBe List(classOf[LlmErrorKind])
    returns(classOf[LlmException].getMethod("isRecoverable")) shouldBe List(java.lang.Boolean.TYPE)
    returns(classOf[LlmException].getMethod("getRetryAfter")) shouldBe
      List(classOf[java.util.Optional[_]], classOf[java.time.Duration])
    returns(classOf[LlmException].getMethod("getStatusCode")) shouldBe List(classOf[java.util.OptionalInt])
    returns(classOf[JCompletion].getMethod("usage")) shouldBe List(classOf[java.util.Optional[_]], classOf[JTokenUsage])
    returns(classOf[JCompletion].getMethod("toolCalls")) shouldBe List(classOf[java.util.List[_]], classOf[JToolCall])
    // every count a Java int, a cache count unreported by the provider reading as zero like thinkingTokens
    Seq("promptTokens", "completionTokens", "totalTokens", "thinkingTokens", "cachedTokens", "cacheCreationTokens")
      .foreach(name => withClue(name)(classOf[JTokenUsage].getMethod(name).getReturnType shouldBe Integer.TYPE))
  }

  "the embeddings facade" should "hand Java callers JEmbeddings from both embed overloads, its vectors as float arrays" in {
    def returns(m: java.lang.reflect.Method): List[Class[_]] = mentioned(m.getGenericReturnType)
    returns(classOf[JEmbeddingClient].getMethod("embed", classOf[java.util.List[_]])) shouldBe
      List(classOf[LlmResult[_]], classOf[JEmbeddings])
    returns(
      classOf[JEmbeddingClient].getMethod("embed", classOf[java.util.List[_]], classOf[JEmbeddingPurpose])
    ) shouldBe
      List(classOf[LlmResult[_]], classOf[JEmbeddings])
    returns(Class.forName("org.llm4s.javaapi.Llm4s").getMethod("createDefaultEmbeddingClient")) shouldBe
      List(classOf[LlmResult[_]], classOf[JEmbeddingClient])
    classOf[JEmbeddings].getMethod("vectors").getGenericReturnType.getTypeName shouldBe "java.util.List<float[]>"
    classOf[JEmbeddings].getMethod("dimensions").getReturnType shouldBe Integer.TYPE
    classOf[JEmbeddingClient].getMethod("dimensions").getReturnType shouldBe Integer.TYPE

    // the cosine helper is static on JEmbeddings, as Java calls it
    val cosine = classOf[JEmbeddings].getMethod("cosineSimilarity", classOf[Array[Float]], classOf[Array[Float]])
    Modifier.isStatic(cosine.getModifiers) shouldBe true
    cosine.getReturnType shouldBe java.lang.Double.TYPE

    // the client keeps its core client to itself: nothing Java-visible returns one
    classOf[JEmbeddingClient].getMethods.map(_.getReturnType) should not contain classOf[EmbeddingClient]
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

  // --- #1666: the Java-visible surface is the intended one, in bytecode as well as in Scala ---

  /** The value types the facade hands a caller to read: Java classes, built only inside the package. */
  private def valueTypes: List[Class[_]] = List(
    classOf[JAgentResult],
    classOf[JAgentStatus],
    classOf[JMessage],
    classOf[JToolCall],
    classOf[JUsageSummary],
    classOf[JModelUsage],
    classOf[PendingInterrupt],
    classOf[JCompletion],
    classOf[JTokenUsage],
    classOf[JEmbeddings]
  )

  /** A method as `javap` would list it: its name and parameter types. */
  private def signature(name: String, params: Seq[Class[_]]): String =
    params.map(_.getSimpleName).mkString(s"$name(", ",", ")")

  /** Every public static method the facade means Java callers to call, by class. Nothing else may be one. */
  private def intendedStatics: Map[Class[_], Set[String]] = Map(
    Class.forName("org.llm4s.javaapi.Llm4s") -> Set(
      "createDefaultClient()",
      "createClient(ProviderConfig)",
      "createClient(ProviderConfig,MetricsCollector)",
      "wrapClient(LLMClient)",
      "wrapEmbeddingClient(EmbeddingClient,EmbeddingModelConfig)",
      "createDefaultEmbeddingClient()",
      "createAgent(JLlmClient)",
      "createAgent(JLlmClient,ToolRegistry)",
      "createAgent(JLlmClient,ToolRegistry,boolean)",
      "wrapAgent(Agent)"
    ),
    classOf[LlmResult[_]]        -> Set("success(Object)", "failure(LLMError)"),
    classOf[ConversationBuilder] -> Set("create()"),
    classOf[JCompletionOptions]  -> Set("builder()"),
    classOf[Answer] -> Set(
      "approve(String)",
      "reject(String,String)",
      "edit(String,String)",
      "reply(String,String)",
      "proceed(String)"
    ),
    classOf[JAgent]                                 -> Set("pending(JAgentResult)"),
    Class.forName("org.llm4s.javaapi.StreamEvents") -> Set("decode(EventType,StreamEvent)"),
    classOf[JEmbeddings]                            -> Set("cosineSimilarity(float[],float[])")
  )

  /** A method name the compiler made up - an expanded private name or a lambda - rather than one a caller is offered. */
  private def synthetic(name: String): Boolean = name.contains("$")

  private def publicStatics(cls: Class[_]): Set[String] =
    cls.getDeclaredMethods.toList
      .filter(m => Modifier.isPublic(m.getModifiers) && Modifier.isStatic(m.getModifiers) && !synthetic(m.getName))
      .map(m => signature(m.getName, m.getParameterTypes.toList))
      .toSet

  "the facade's value types" should "have no public constructor, no public static factory and no Scala companion" in {
    for (cls <- valueTypes) withClue(cls.getName) {
      cls.getConstructors shouldBe empty
      publicStatics(cls) shouldBe intendedStatics.getOrElse(cls, Set.empty)
      Modifier.isFinal(cls.getModifiers) shouldBe true
      // no `JAgentResult$` whose public `of(AgentResult)` Java could call instead
      an[ClassNotFoundException] should be thrownBy Class.forName(cls.getName + "$")
    }
  }

  they should "be impossible to construct from Java code in another package, as javac reports" in {
    import javax.tools.{ DiagnosticCollector, JavaFileObject, SimpleJavaFileObject, ToolProvider }
    // one statement per line, each calling a value type's constructor with arguments of its parameter types, then
    // the `of` factories the Scala companions had
    def argument(t: Class[_]): String =
      if (t == java.lang.Boolean.TYPE) "false" else if (t.isPrimitive) "0" else s"(${t.getCanonicalName}) null"
    val constructions = valueTypes.map { cls =>
      val params = cls.getDeclaredConstructors.head.getParameterTypes.toList
      s"Object ${cls.getSimpleName.toLowerCase}New = new ${cls.getName}(${params.map(argument).mkString(", ")});"
    }
    val factories = List(
      "Object agentResultOf = org.llm4s.javaapi.JAgentResult.of(null);",
      "Object toolCallOf = org.llm4s.javaapi.JToolCall.of(null);",
      "Object completionOf = org.llm4s.javaapi.JCompletion.of(null);"
    )
    val statements = constructions ++ factories
    val source     = statements.mkString("package user;\nclass Forger {\n  void forge() {\n", "\n", "\n  }\n}\n")
    val file = new SimpleJavaFileObject(java.net.URI.create("string:///user/Forger.java"), JavaFileObject.Kind.SOURCE) {
      override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }
    val out = java.nio.file.Files.createTempDirectory("forger")
    val classes =
      java.nio.file.Paths.get(classOf[JAgentResult].getProtectionDomain.getCodeSource.getLocation.toURI).toString
    val diagnostics = new DiagnosticCollector[JavaFileObject]
    val compiled = ToolProvider.getSystemJavaCompiler
      .getTask(
        null,
        null,
        diagnostics,
        java.util.List.of("-proc:none", "-cp", classes, "-d", out.toString),
        null,
        java.util.List.of(file)
      )
      .call()
    compiled shouldBe false
    val errors = diagnostics.getDiagnostics.asScala.toList.filter(_.getKind == javax.tools.Diagnostic.Kind.ERROR)
    // the statements start on line 4: every one is refused, a constructor for being package-private
    errors.map(_.getLineNumber.toInt).distinct.sorted shouldBe statements.indices.map(_ + 4).toList
    errors
      .filter(_.getLineNumber <= constructions.size + 3)
      .foreach(e => e.getMessage(java.util.Locale.ROOT) should include("is not public in"))
  }

  /** The facade's Scala classes whose constructors only the package may call: each takes the package's token first. */
  private def tokenGuarded: List[Class[_]] = List(
    classOf[LlmResult[_]],
    classOf[JLlmClient],
    classOf[JEmbeddingClient],
    classOf[JCompletionOptions],
    classOf[JCompletionOptions.Builder],
    classOf[JAgent],
    classOf[AgentStream],
    classOf[ConversationBuilder],
    classOf[Answer]
  )

  "the facade's other classes" should "take a token only the package can name first in every public constructor" in {
    val token = Class.forName("org.llm4s.javaapi.FacadeToken")
    Modifier.isPublic(token.getModifiers) shouldBe false
    for (cls <- tokenGuarded) withClue(cls.getName) {
      cls.getConstructors should not be empty
      cls.getConstructors.foreach(c => c.getParameterTypes.head shouldBe token)
      consumer.FacadeSurfaceCheck.firstParameterUnnamable(cls) shouldBe true
    }
  }

  they should "refuse a constructor call forged from another package, null for the token" in {
    for (cls <- tokenGuarded) withClue(cls.getName) {
      consumer.FacadeSurfaceCheck.forge(cls).asScala.toList shouldBe List("UnsupportedOperationException")
    }
    for (cls <- valueTypes) withClue(cls.getName)(consumer.FacadeSurfaceCheck.forge(cls).asScala shouldBe empty)
  }

  "every facade class" should "have only intended public constructors and public static methods, its companion too" in {
    // LlmException is meant to be built: a caller may throw or wrap one around its own LLMError
    val intendedConstructors = Map[Class[_], Set[String]](classOf[LlmException] -> Set("LlmException(LLMError)"))
    for (cls <- facade if !cls.isInterface && !cls.isEnum) withClue(cls.getName) {
      val constructors = cls.getConstructors.toList
        .filterNot(_ => tokenGuarded.contains(cls))
        .map(c => signature(cls.getSimpleName, c.getParameterTypes.toList))
        .toSet
      constructors shouldBe intendedConstructors.getOrElse(cls, Set.empty)
      publicStatics(cls) shouldBe intendedStatics.getOrElse(cls, Set.empty)
      // the companion's own public methods: `LlmResult$.MODULE$.from(either)` was callable too
      scala.util.Try(Class.forName(cls.getName + "$")).foreach { companion =>
        val offered = companion.getDeclaredMethods.toList
          .filter(m => Modifier.isPublic(m.getModifiers) && !Modifier.isStatic(m.getModifiers) && !synthetic(m.getName))
          .map(m => signature(m.getName, m.getParameterTypes.toList))
          .toSet
        offered shouldBe intendedStatics.getOrElse(cls, Set.empty)
      }
    }
    for (cls <- facade if cls.isEnum) withClue(cls.getName) {
      publicStatics(cls) shouldBe Set("values()", "valueOf(String)")
    }
  }

  /**
   * Public top-level classes of the package that are not facade: internal by design and documented as such. Scala's
   * `private[llm4s]` is public in bytecode, so these are visible to Java; nothing else may be.
   */
  private def internalClasses: Set[String] = Set(
    "org.llm4s.javaapi.FacadeInternals", // reads core's Scala values into the facade's types
    "org.llm4s.javaapi.LlmErrorKinds"    // how LlmException reads an LLMError: its kind, status and retry delay
  )

  /** Every public top-level class the compiled package holds, a Scala object's `X$` counted as `X`. */
  private def publicTopLevelClasses: Set[String] = {
    val root  = java.nio.file.Paths.get(classOf[JAgentResult].getProtectionDomain.getCodeSource.getLocation.toURI)
    val names = javaApiClassNames(root)
    names
      .map(n => Class.forName(n, false, getClass.getClassLoader))
      .filter(c => c.getEnclosingClass == null && Modifier.isPublic(c.getModifiers) && !c.isSynthetic)
      .map(_.getName.stripSuffix("$"))
      .toSet
  }

  /** The binary names of the classes directly in `org/llm4s/javaapi` under a classes directory or jar. */
  private def javaApiClassNames(root: java.nio.file.Path): List[String] = {
    val prefix = "org/llm4s/javaapi/"
    def named(entries: List[String]): List[String] =
      entries
        .filter(e => e.startsWith(prefix) && e.endsWith(".class") && !e.stripPrefix(prefix).contains("/"))
        .map(_.stripSuffix(".class").replace('/', '.'))
    if (java.nio.file.Files.isDirectory(root)) {
      val dir = root.resolve(prefix)
      named(
        java.nio.file.Files.list(dir).iterator().asScala.toList.map(p => prefix + p.getFileName.toString)
      )
    } else {
      val jar = new java.util.jar.JarFile(root.toFile)
      val all = jar.entries().asScala.map(_.getName).toList
      jar.close()
      named(all)
    }
  }

  "every public top-level class of the package" should "be a reviewed facade class or a listed internal" in {
    val reviewed = facade.map(_.getName).toSet
    val found    = publicTopLevelClasses
    // found the facade itself, so the scan saw the compiled package and not an empty directory
    (found should contain).allOf(
      "org.llm4s.javaapi.Llm4s",
      "org.llm4s.javaapi.JAgentResult",
      "org.llm4s.javaapi.LlmResult"
    )
    (found -- reviewed -- internalClasses) shouldBe empty
    // and every listed internal still exists, so the list cannot carry a stale name that would hide a new class
    internalClasses.foreach(n => found should contain(n))
  }

  "a Java caller in another package" should "read every value type the facade hands it" in {
    consumer.FacadeSurfaceCheck.readValues(answering("4")).asScala.toList shouldBe List(
      "completion:4:m:0",
      "status:COMPLETED:4:0",
      "USER:What is 2+2?",
      "ASSISTANT:4",
      "usage:1:0:0",
      "model:m:1:0",
      "same:true:true"
    )
  }
}

object JavaInteropSpec {

  /** A facade-like type whose leak is one step away, for the reachability guard's own test. */
  final class Holder { def inner: Inner = new Inner(None) }

  /** A case class: a Scala `Option` field, and `Product` members, both leaks. */
  final case class Inner(value: Option[String])
}
