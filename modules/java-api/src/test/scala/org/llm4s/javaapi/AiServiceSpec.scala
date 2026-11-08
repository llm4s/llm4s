package org.llm4s.javaapi

import org.llm4s.javaapi.services.AiServiceFixtures.*
import org.llm4s.javaapi.services.{ ChatMemoryStore, ChatTurn }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.{ CompletableFuture, ExecutionException, Flow, TimeUnit }
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** AI services (#2277): an annotated interface that `Llm4s.aiService` implements. */
class AiServiceSpec extends AnyWordSpec with TestTimeLimit with Matchers {

  /** A client answering call `n` with `script(n)`, recording every conversation it was sent. */
  final private class Recording(script: Int => AssistantMessage) extends LLMClient {
    private val sent = ArrayBuffer.empty[Conversation]

    def conversations: List[Conversation] = sent.synchronized(sent.toList)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val n       = sent.synchronized { sent += conversation; sent.size - 1 }
      val message = script(n)
      Right(Completion(s"r$n", Instant.EPOCH, message.content, "recording", message))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096
  }

  private def replying(replies: String*): Recording = new Recording(n => AssistantMessage(replies(n)))

  private def toolCall(id: String): AssistantMessage =
    AssistantMessage(toolCalls = Seq(ToolCall(s"call-$id", "orderStatus", ujson.Obj("id" -> id))))

  private def support(client: JLlmClient): Support = Llm4s.aiService(classOf[Support]).client(client).build()

  private def failure(body: => Any): LlmException = intercept[LlmException](body)

  private def messages(c: Conversation): List[(String, String)] =
    c.messages.toList.map(m => (m.role.toString, m.content))

  /** What a publisher sends, collected until it ends: the pieces, or the error it fails with. */
  private def collected(publisher: Flow.Publisher[String]): Either[Throwable, List[String]] = {
    val done   = new CompletableFuture[Either[Throwable, List[String]]]
    val pieces = ArrayBuffer.empty[String]
    publisher.subscribe(new Flow.Subscriber[String] {
      def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
      def onNext(item: String): Unit              = pieces.synchronized(pieces += item)
      def onError(t: Throwable): Unit             = done.complete(Left(t))
      def onComplete(): Unit                      = done.complete(Right(pieces.synchronized(pieces.toList)))
    })
    done.get(10, TimeUnit.SECONDS)
  }

  "A method returning String" should {
    "render the interface's system template and send the @UserMessage argument as it is" in {
      val client = replying("Restart it.")
      support(Llm4s.wrapClient(client)).answer("How do I fix {{this}}?", "Widget") shouldBe "Restart it."
      messages(client.conversations.head) shouldBe List(
        "system" -> "You are a support agent for Widget. Answer briefly.",
        "user"   -> "How do I fix {{this}}?"
      )
    }

    "render a method's own templates once, with any argument type, and not expand a value's placeholders" in {
      val client = replying("Good day.")
      support(Llm4s.wrapClient(client)).greet("{{style}}", 42, "formal") shouldBe "Good day."
      messages(client.conversations.head) shouldBe List(
        "system" -> "Write a formal greeting.",
        "user"   -> "Greet {{style}}, who is 42 years old, in formal style."
      )
    }

    "send no system message when neither the method nor the interface has one" in {
      val client = replying("Hello.")
      Llm4s.aiService(classOf[Chat]).client(Llm4s.wrapClient(client)).build().once("Hi") shouldBe "Hello."
      messages(client.conversations.head) shouldBe List("user" -> "Hi")
    }

    "throw an LlmException of the provider's kind when the call fails" in {
      val fake = FakeLlmClient.builder().failWith(LlmErrorKind.RATE_LIMIT, "slow down").build()
      failure(support(fake.client()).answer("Hi", "Widget")).getKind shouldBe LlmErrorKind.RATE_LIMIT
    }

    "throw an LlmException of kind VALIDATION for a null argument, without calling the model" in {
      val fake = FakeLlmClient.replying("unused")
      val e    = failure(support(fake.client()).answer("Hi", null))
      e.getKind shouldBe LlmErrorKind.VALIDATION
      e.getMessage should include("argument 2 is null")
      fake.callCount() shouldBe 0
    }
  }

  "A method returning an enum" should {
    "read the constant the model chose" in {
      val fake = FakeLlmClient.replying("""{"value":"POSITIVE"}""")
      support(fake.client()).classify("I love it") shouldBe Sentiment.POSITIVE
      fake.lastPrompt().get() shouldBe "Classify the sentiment of: I love it"
    }

    "fail with kind VALIDATION when the answer has no constant" in {
      val fake = FakeLlmClient.replying("{}", """{"value":"ANGRY"}""")
      val s    = support(fake.client())
      failure(s.classify("meh")).getMessage should include("no field 'value'")
      failure(s.classify("meh")).getKind shouldBe LlmErrorKind.VALIDATION
    }
  }

  "A method returning a record" should {
    "read the record from the structured answer" in {
      val fake = FakeLlmClient.replying("""{"title":"Login broken","priority":1}""")
      support(fake.client()).ticket("I cannot log in") shouldBe new Ticket("Login broken", 1)
    }

    "fail with kind VALIDATION when the answer does not fit the record" in {
      val fake = FakeLlmClient.replying("""{"title":"Login broken"}""")
      failure(support(fake.client()).ticket("I cannot log in")).getKind shouldBe LlmErrorKind.VALIDATION
    }
  }

  "A method returning LlmResult" should {
    "return a success holding the value" in {
      val fake = FakeLlmClient.replying("""{"title":"Refund","priority":2}""", "Yes.")
      val s    = support(fake.client())
      s.draftTicket("Refund me").get() shouldBe new Ticket("Refund", 2)
      s.tryAnswer("Can I?").get() shouldBe "Yes."
    }

    "return a failure instead of throwing" in {
      val fake   = FakeLlmClient.builder().failWith(LlmErrorKind.AUTHENTICATION, "bad key").build()
      val result = support(fake.client()).tryAnswer("Hi")
      result.isFailure shouldBe true
      result.getError().getKind shouldBe LlmErrorKind.AUTHENTICATION
      support(FakeLlmClient.replying("{}").client()).draftTicket("x").getError().getKind shouldBe
        LlmErrorKind.VALIDATION
    }
  }

  "A method returning JCompletion" should {
    "return the whole reply" in {
      val completion = support(FakeLlmClient.replying("All of it.").client()).completion("Everything?")
      completion.content() shouldBe "All of it."
      completion.model() shouldBe "fake"
    }
  }

  "A method returning CompletableFuture" should {
    "complete with the value, on another thread" in {
      val s = support(FakeLlmClient.replying("Later.", """{"value":"NEUTRAL"}""").client())
      s.answerLater("When?").get(10, TimeUnit.SECONDS) shouldBe "Later."
      s.classifyLater("ok").get(10, TimeUnit.SECONDS) shouldBe Sentiment.NEUTRAL
    }

    "complete exceptionally with the LlmException" in {
      val fake = FakeLlmClient.builder().failWith(LlmErrorKind.TIMEOUT, "too slow").build()
      val e    = intercept[ExecutionException](support(fake.client()).answerLater("When?").get(10, TimeUnit.SECONDS))
      e.getCause shouldBe a[LlmException]
      e.getCause.asInstanceOf[LlmException].getKind shouldBe LlmErrorKind.TIMEOUT
    }
  }

  "A method returning Flow.Publisher<String>" should {
    "stream the answer" in {
      collected(support(FakeLlmClient.replying("Streamed.").client()).stream("Go")) shouldBe Right(
        List("Streamed.")
      )
    }

    "fail the stream with kind VALIDATION for a null argument" in {
      val error = collected(support(FakeLlmClient.replying("unused").client()).stream(null)).left.toOption.get
      error.asInstanceOf[LlmException].getKind shouldBe LlmErrorKind.VALIDATION
    }
  }

  "The proxy" should {
    "run a default method as written, and answer equals, hashCode and toString for itself" in {
      val s = support(FakeLlmClient.replying("quiet").client())
      s.shout("Hi") shouldBe "QUIET"
      Support.version() shouldBe "1"
      s.equals(s) shouldBe true
      s.equals(support(FakeLlmClient.replying().client())) shouldBe false
      s.hashCode shouldBe System.identityHashCode(s)
      s.toString shouldBe s"AiService(${classOf[Support].getName})"
    }

    "implement an interface with no methods" in {
      Llm4s.aiService(classOf[Empty]).client(FakeLlmClient.replying().client()).build().toString should include(
        "Empty"
      )
    }
  }

  "A @MemoryId method" should {
    "keep one conversation per id, and none for a method without one" in {
      val client = replying("Hello Alice.", "Hello Bob.", "You are Alice.", "No memory.")
      val store  = ChatMemoryStore.inMemory()
      val chat   = Llm4s.aiService(classOf[Chat]).client(Llm4s.wrapClient(client)).memoryStore(store).build()
      chat.chat("alice", "I am Alice") shouldBe "Hello Alice."
      chat.chat("bob", "I am Bob") shouldBe "Hello Bob."
      chat.chat("alice", "Who am I?") shouldBe "You are Alice."
      chat.once("Who am I?") shouldBe "No memory."
      val sent = client.conversations.map(messages)
      sent(1) shouldBe List("user" -> "I am Bob")
      sent(2) shouldBe List("user" -> "I am Alice", "assistant" -> "Hello Alice.", "user" -> "Who am I?")
      sent(3) shouldBe List("user" -> "Who am I?")
      store.load("alice").asScala.toList shouldBe List(
        new ChatTurn("I am Alice", "Hello Alice."),
        new ChatTurn("Who am I?", "You are Alice.")
      )
      store.load("carol").isEmpty shouldBe true
    }

    "store nothing for a failed call" in {
      val store = ChatMemoryStore.inMemory()
      val fake  = FakeLlmClient.builder().failWith(LlmErrorKind.SERVICE, "down").build()
      val chat  = Llm4s.aiService(classOf[Chat]).client(fake.client()).memoryStore(store).build()
      failure(chat.chat("alice", "Hi")).getKind shouldBe LlmErrorKind.SERVICE
      store.load("alice").isEmpty shouldBe true
      failure(chat.chat(null, "Hi")).getKind shouldBe LlmErrorKind.VALIDATION
    }

    "fail with kind OTHER, the exception as cause, when the store throws" in {
      val broken = new IllegalStateException("store down")
      val store = new ChatMemoryStore {
        def load(memoryId: Object): java.util.List[ChatTurn] = throw broken
        def append(memoryId: Object, turn: ChatTurn): Unit   = ()
        def clear(memoryId: Object): Unit                    = ()
      }
      val chat =
        Llm4s.aiService(classOf[Chat]).client(FakeLlmClient.replying("x").client()).memoryStore(store).build()
      val e = failure(chat.chat("alice", "Hi"))
      e.getKind shouldBe LlmErrorKind.OTHER
      e.getCause shouldBe broken
    }
  }

  "ChatMemoryStore.inMemory" should {
    "keep the newest turns, forget an id on clear, and refuse a bound below 1" in {
      val store = ChatMemoryStore.inMemory(2)
      (1 to 3).foreach(i => store.append("id", new ChatTurn(s"q$i", s"a$i")))
      store.load("id").asScala.map(_.userMessage).toList shouldBe List("q2", "q3")
      store.toString shouldBe "ChatMemoryStore.inMemory(1 conversations)"
      store.clear("id")
      store.load("id").isEmpty shouldBe true
      intercept[IllegalArgumentException](ChatMemoryStore.inMemory(0))
      intercept[NullPointerException](new ChatTurn(null, "a"))
      intercept[NullPointerException](new ChatTurn("q", null))
      intercept[NullPointerException](store.append("id", null))
    }
  }

  "A service with tools" should {
    "answer each call with an agent turn that runs the tools the model asks for" in {
      val client  = new Recording(n => if (n == 0) toolCall("42") else AssistantMessage("Order 42 has shipped."))
      val tool    = JTool.fromAnnotated(new OrderTools).get().get(0)
      val agentic = Llm4s.aiService(classOf[Agentic]).client(Llm4s.wrapClient(client)).tools(tool).build()
      agentic.ask("Where is order 42?") shouldBe "Order 42 has shipped."
      val toolResults = client.conversations(1).messages.collect { case t: ToolMessage => t.content }
      toolResults.mkString should include("order 42 shipped")
    }

    "ask for a structured answer in the system prompt and read it, fenced or not" in {
      val fenced = "```json\n{\"title\":\"Late order\",\"priority\":3}\n```"
      val client = new Recording(n => if (n == 0) toolCall("7") else AssistantMessage(fenced))
      val agentic =
        Llm4s.aiService(classOf[Agentic]).client(Llm4s.wrapClient(client)).tools(new OrderTools).build()
      agentic.ticket("Order 7 is late") shouldBe new Ticket("Late order", 3)
      val system = client.conversations.head.messages.collect { case s: SystemMessage => s.content }.mkString
      system should include("Look orders up with the tools.")
      system should include("JSON Schema")
    }

    "fail with kind VALIDATION when the structured answer is not JSON or does not fit" in {
      val notJson = Llm4s.aiService(classOf[Agentic]).client(Llm4s.wrapClient(replying("happy"))).tools(new OrderTools)
      val result  = notJson.build().mood("How do I feel?")
      result.getError().getKind shouldBe LlmErrorKind.VALIDATION
      result.getError().getMessage should include("not JSON")
      val unfit = Llm4s
        .aiService(classOf[Agentic])
        .client(Llm4s.wrapClient(replying("""{"value":"ANGRY"}""")))
        .tools(new OrderTools)
        .build()
      unfit.mood("How do I feel?").getError().getMessage should include("ANGRY")
    }

    "keep a @MemoryId conversation across agent turns" in {
      val client = replying("Noted.", "You said hello.")
      val agentic =
        Llm4s.aiService(classOf[Agentic]).client(Llm4s.wrapClient(client)).tools(new OrderTools).build()
      agentic.chat(1, "hello") shouldBe "Noted."
      agentic.chat(1, "What did I say?") shouldBe "You said hello."
      messages(client.conversations(1)).filterNot(_._1 == "system") shouldBe List(
        "user"      -> "hello",
        "assistant" -> "Noted.",
        "user"      -> "What did I say?"
      )
    }

    "fail when the agent turn ends without an answer" in {
      val agentic = Llm4s
        .aiService(classOf[Agentic])
        .client(Llm4s.wrapClient(new Recording(n => toolCall(n.toString))))
        .tools(new OrderTools)
        .build()
      failure(agentic.ask("Loop forever")).getMessage should include("without an answer")
    }
  }

  "build()" should {
    "report every mistake of an interface at once, each naming its method" in {
      val e = failure(Llm4s.aiService(classOf[Broken]).client(FakeLlmClient.replying().client()).build())
      e.getKind shouldBe LlmErrorKind.VALIDATION
      List(
        "Broken.count: returns int",
        "Broken.unannotated: parameter 1 (String) needs exactly one of @UserMessage, @V and @MemoryId",
        "Broken.unannotated: has no user message",
        "Broken.twoRoles: parameter 1 (String) needs exactly one",
        "Broken.unknownPlaceholder: {{missing}} is named by no @V parameter",
        "Broken.unknownInSystem: {{secret}} is named by no @V parameter",
        "Broken.blankTemplate: its @UserMessage template is blank",
        "Broken.bothUserMessages: has more than one user message",
        "Broken.twoUserParameters: has more than one user message",
        "Broken.duplicateVariable: @V(\"a\") names more than one parameter",
        "Broken.twoMemoryIds: has more than one @MemoryId parameter",
        "Broken.resultOfInteger: returns org.llm4s.javaapi.LlmResult<java.lang.Integer>",
        "Broken.publisherOfInteger: returns java.util.concurrent.Flow$Publisher<java.lang.Integer>",
        "Broken.list: returns java.util.List<java.lang.String>",
        "Broken.unschematic: ",
        "Broken.streamWithMemory: a Flow.Publisher method cannot have a @MemoryId parameter"
      ).foreach(problem => e.getMessage should include(problem))
    }

    "refuse streaming and JCompletion methods on a service with tools" in {
      val client = FakeLlmClient.replying().client()
      Llm4s.aiService(classOf[ToolConflicts]).client(client).build() should not be null
      val e = failure(Llm4s.aiService(classOf[ToolConflicts]).client(client).tools(new OrderTools).build())
      e.getMessage should include("ToolConflicts.stream: a Flow.Publisher method cannot use tools")
      e.getMessage should include("ToolConflicts.completion: a JCompletion method cannot use tools")
    }

    "refuse a class, a missing client and tools that cannot be offered" in {
      val client = FakeLlmClient.replying().client()
      failure(Llm4s.aiService(classOf[NotAnInterface]).client(client).build()).getMessage should include(
        "is not an interface"
      )
      failure(Llm4s.aiService(classOf[Support]).build()).getMessage should include("client")
      failure(Llm4s.aiService(classOf[Support]).client(client).tools(new NoTools).build()).getMessage should include(
        "has no public @Tool method"
      )
      failure(
        Llm4s.aiService(classOf[Agentic]).client(client).tools(new OrderTools, new OtherOrderTools).build()
      ).getMessage should include("two tools are named 'orderStatus'")
    }

    "throw NullPointerException for a null argument to a setter" in {
      val builder = Llm4s.aiService(classOf[Support])
      intercept[NullPointerException](Llm4s.aiService(null))
      intercept[NullPointerException](builder.client(null))
      intercept[NullPointerException](builder.memoryStore(null))
      intercept[NullPointerException](builder.tools(new OrderTools, null))
    }
  }
}
