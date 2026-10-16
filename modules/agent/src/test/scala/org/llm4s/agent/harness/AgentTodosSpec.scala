package org.llm4s.agent.harness

import org.llm4s.agent.{ Agent, AgentBuilder, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, InMemoryCheckpointer, StreamEvent, ThreadId }
import org.llm4s.agent.graph.middleware.{ PermissionMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool.Operation
import org.llm4s.llmconnect.model.*
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** Stage 3 slice F3 (#1907, design §4.30): todos through `Agent`. */
class AgentTodosSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def writing(items: (String, String)*): Completion =
    CompletionFixture.withMessage(
      AssistantMessage(
        None,
        Seq(
          ToolCall(
            "w1",
            "write_todos",
            ujson.Obj("todos" -> ujson.Arr(items.map((c, s) => ujson.Obj("content" -> c, "status" -> s))*))
          )
        )
      )
    )

  "A streamed run" should "deliver the plan's content live, and only ids and statuses durably" in {
    val client = ScriptedLLMClient.of(writing("Research" -> "in_progress"), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withMiddleware(new TodoMiddleware()))
    val seen   = new CopyOnWriteArrayList[StreamEvent]()
    agent.stream(ThreadId("streamed"), "plan")(e => seen.add(e): Unit).value.await().value
    val events = seen.asScala.toVector
    events.collect { case HarnessEvents.TodosProgress(p) => p.todos.map(_.content) } shouldBe Vector(
      Vector("Research")
    )
    events.collect { case e @ HarnessEvents.TodosUpdated(u) => (e.isInstanceOf[StreamEvent.Durable], u) } shouldBe
      Vector(true -> TodosUpdated(Vector(TodoMark("1", TodoStatus.InProgress))))
    events
      .collect { case e @ HarnessEvents.TodosProgress(_) => e.isInstanceOf[StreamEvent.Live] }
      .forall(identity) shouldBe true
  }

  "The checkpointed thread" should "hold the plan, read by a later run" in {
    val store  = new InMemoryCheckpointer
    val thread = ThreadId("kept")
    val client = ScriptedLLMClient.of(
      writing("A" -> "pending"),
      CompletionFixture.simple("planned"),
      CompletionFixture.simple("still planned")
    )
    val agent =
      built(Agent.builder("assistant", client).withMiddleware(new TodoMiddleware()).withRuntime(GraphRuntime(store)))
    agent.run(thread, "plan").value
    agent.run(thread, "again").value
    val restored = agent.loop.graph.restore(store.latest(thread).value.value.checkpoint.snapshot).value.state
    restored.get(TodoMiddleware.key).value.todos.map(_.content) shouldBe Vector("A")
    // the second turn's request showed the plan stored by the first
    client.sent.last.collectFirst { case s: SystemMessage => s.content }.value should include("(1) A")
  }

  "A permission rule set without a rule for write_todos" should "deny it: the plan never overrides tool policy" in {
    val client = ScriptedLLMClient.of(writing("A" -> "pending"), CompletionFixture.simple("done"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withMiddleware(
          new PermissionMiddleware(Seq(PermissionRule.allow(Operation.Invoke, "other"))),
          new TodoMiddleware()
        )
    )
    val result  = agent.run("plan").value
    val results = result.messages.collect { case t: ToolMessage => t.content }
    results should have size 1
    results.head should include("Denied: invoke write_todos")
  }

  "An agent without TodoMiddleware" should "register no todo key, so its structure is unchanged (rule 7)" in {
    val store                        = new InMemoryCheckpointer
    val thread                       = ThreadId("plain")
    def agent(builder: AgentBuilder) = built(builder.withRuntime(GraphRuntime(store)))
    val plain = agent(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("hi"))))
    plain.run(thread, "q").value
    val state = plain.loop.graph.restore(store.latest(thread).value.value.checkpoint.snapshot).value.state
    state.get(TodoMiddleware.key).left.value shouldBe a[GraphError.UnknownStateKey]
    val planned = agent(Agent.builder("assistant", ScriptedLLMClient.of()).withMiddleware(new TodoMiddleware()))
    plain.loop.graph.fingerprint should not be planned.loop.graph.fingerprint
  }

  "An agent's existing thread" should "continue at rest once TodoMiddleware is added, its plan starting empty (§4.29)" in {
    val store                        = new InMemoryCheckpointer
    val thread                       = ThreadId("upgraded")
    def agent(builder: AgentBuilder) = built(builder.withRuntime(GraphRuntime(store)))
    agent(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("hi")))).run(thread, "q").value
    store.latest(thread).value.value.checkpoint.snapshot.state.keySet should not contain "todos"

    val client  = ScriptedLLMClient.of(writing("A" -> "pending"), CompletionFixture.simple("planned"))
    val planned = agent(Agent.builder("assistant", client).withMiddleware(new TodoMiddleware()))
    planned.run(thread, "plan").value.answer shouldBe Some("planned")
    val state = planned.loop.graph.restore(store.latest(thread).value.value.checkpoint.snapshot).value.state
    state.get(TodoMiddleware.key).value.todos.map(_.content) shouldBe Vector("A")
  }

  it should "be refused by an agent that dropped TodoMiddleware while its plan holds items" in {
    val store                        = new InMemoryCheckpointer
    val thread                       = ThreadId("downgraded")
    def agent(builder: AgentBuilder) = built(builder.withRuntime(GraphRuntime(store)))
    val client = ScriptedLLMClient.of(writing("A" -> "pending"), CompletionFixture.simple("planned"))
    agent(Agent.builder("assistant", client).withMiddleware(new TodoMiddleware())).run(thread, "plan").value
    val refused = agent(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("never"))))
      .run(thread, "again")
      .left
      .value
    refused shouldBe a[GraphError.RestoreRejected]
    refused.message should include("'todos' was removed from this graph")
  }

  it should "be refused in flight, suspended for an approval, by an agent that added TodoMiddleware" in {
    val store  = new InMemoryCheckpointer
    val thread = ThreadId("parked")
    def agent(builder: AgentBuilder) =
      built(
        builder
          .withTools(
            org.llm4s.agent.SpecTools.set(
              org.llm4s.agent.SpecTools.tool("echo")((a, _) =>
                org.llm4s.agent.graph.tool.ToolOutcome
                  .Success(org.llm4s.agent.graph.tool.ToolContent.Json(ujson.Str(a.text)))
              )
            )
          )
          .withMiddleware(new org.llm4s.agent.graph.middleware.ApprovalMiddleware(_ => Some("review")))
          .withRuntime(GraphRuntime(store))
      )
    val calling = org.llm4s.agent.SpecTools.calling(org.llm4s.agent.SpecTools.call("c1", "echo"))
    val parked  = agent(Agent.builder("assistant", ScriptedLLMClient.of(calling))).run(thread, "go").value
    val answers = parked.status match {
      case org.llm4s.agent.AgentStatus.Suspended(approvals, _, _, _) => approvals.map(a => parked.approve(a._1)).toMap
      case other                                                     => fail(s"expected Suspended, got $other")
    }
    // the family's version changed: a turn in flight resumes only under the family it started in
    agent(Agent.builder("assistant", ScriptedLLMClient.of()).withMiddleware(new TodoMiddleware()))
      .resume(thread, answers)
      .left
      .value shouldBe a[GraphError.RestoreRejected]
    agent(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("done"))))
      .resume(thread, answers)
      .value
      .answer shouldBe Some("done")
  }

  "The guide's Todos example" should "compile and run as written" in {
    val client   = ScriptedLLMClient.of(writing("Outline" -> "pending"), CompletionFixture.simple("report"))
    val threadId = ThreadId("guide")
    val printed  = new CopyOnWriteArrayList[String]()
    def println(s: String): Unit = printed.add(s): Unit
    // as in docs/guide/agents/index.md, "Todos"
    val outcome = for {
      agent <- Agent
        .builder("researcher", client)
        .withMiddleware(new TodoMiddleware(maxTodos = 20, maxTodoChars = 200))
        .build()
      run <- agent.stream(threadId, "Write a report on ...") {
        case HarnessEvents.TodosProgress(p) =>
          p.todos.foreach(t => println(s"[${t.status.wire}] ${t.content}"))
        case _ => ()
      }
      result <- run.await()
    } yield result
    outcome.value.answer shouldBe Some("report")
    printed.asScala.toVector shouldBe Vector("[pending] Outline")
  }
}
