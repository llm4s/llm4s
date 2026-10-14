package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, ThreadId }
import org.llm4s.agent.graph.sqlite.LocksFixture.{ answer, completion, Scripted }
import org.llm4s.agent.harness.TodoMiddleware
import org.llm4s.llmconnect.model.*
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock

/**
 * F3's `TodoMiddleware` under the C5 rules (design §4.29), through SQLite and a reopen: a thread checkpointed by an
 * agent without todos continues once the agent adds them, the `todos` and `tool-queue` keys starting empty. A thread
 * whose checkpoint predates C5 records no shape, so it restores only into its own version, which adding a middleware
 * changes.
 */
class SqliteTodosEvolutionSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("planned-later")

  private def writing(items: String*): Completion =
    completion(
      AssistantMessage(
        None,
        Seq(
          ToolCall(
            "w1",
            "write_todos",
            ujson.Obj("todos" -> ujson.Arr(items.map(c => ujson.Obj("content" -> c, "status" -> "pending"))*))
          )
        )
      )
    )

  private def agent(store: SqliteCheckpointer, todos: Boolean, replies: Completion*): Agent = {
    val builder = Agent.builder("assistant", Scripted(replies*)).withRuntime(GraphRuntime(store))
    (if todos then builder.withMiddleware(new TodoMiddleware()) else builder).build().value
  }

  "A thread checkpointed without todos" should "continue, after a reopen, under the agent once it adds TodoMiddleware" in {
    val path  = SqliteFiles.fresh()
    val first = SqliteFiles.opened(path, Clock.systemUTC())
    agent(first, todos = false, answer("hi")).run(thread, "q").value.answer shouldBe Some("hi")
    first.close()

    val store   = SqliteFiles.opened(path, Clock.systemUTC())
    val planned = agent(store, todos = true, writing("A", "B"), answer("planned"))
    planned.run(thread, "plan").value.answer shouldBe Some("planned")
    val state = planned.loop.graph.restore(store.latest(thread).value.value.checkpoint.snapshot).value.state
    state.get(TodoMiddleware.key).value.todos.map(_.content) shouldBe Vector("A", "B")
    store.close()
  }

  "A thread checkpointed before C5" should "be refused by the agent once it adds TodoMiddleware, which changes its version" in {
    // a pre-C5 agent checkpoint is this one without its shape: what main wrote before #1934
    val fresh = SqliteFiles.opened(SqliteFiles.fresh(), Clock.systemUTC())
    agent(fresh, todos = false, answer("hi")).run(thread, "q").value
    val stored  = fresh.latest(thread).value.value
    val legacy  = stored.checkpoint.snapshot.copy(shape = None)
    val planned = agent(fresh, todos = true).loop.graph.restore(legacy).left.value
    planned shouldBe a[GraphError.RestoreRejected]
    planned.message should include("a snapshot without a shape restores only into its own version")
    agent(fresh, todos = false).loop.graph.restore(legacy).value
    fresh.close()
  }
}
