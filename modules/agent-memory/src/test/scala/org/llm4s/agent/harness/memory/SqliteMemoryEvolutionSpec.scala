package org.llm4s.agent.harness.memory

import org.llm4s.agent.{ Agent, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.graph.{ GraphRuntime, ThreadId }
import org.llm4s.agent.graph.sqlite.{ SqliteCheckpointer, SqliteFiles }
import org.llm4s.agent.memory.{ InMemoryStore, Memory, SimpleMemoryManager }
import org.llm4s.llmconnect.model.SystemMessage
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock

/**
 * `SemanticMemoryMiddleware` under C5's rules (design §4.29, §4.31), through a SQLite checkpoint and a reopen: a
 * thread checkpointed by an agent without the middleware continues once the agent adds it, its recall key starting
 * from its initial value; and, as a thread at rest holds no recall, it continues after the middleware is removed.
 */
class SqliteMemoryEvolutionSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("remembered-later")

  private def agent(store: SqliteCheckpointer, memory: Option[SemanticMemoryMiddleware], reply: String) = {
    val client  = ScriptedLLMClient.of(CompletionFixture.simple(reply))
    val builder = Agent.builder("assistant", client).withRuntime(GraphRuntime(store))
    (built(memory.fold(builder)(m => builder.withMiddleware(m))), client)
  }

  "A thread checkpointed without semantic memory" should "continue after a reopen once the agent adds it, and after it is removed" in {
    val path  = SqliteFiles.fresh()
    val first = SqliteFiles.opened(path, Clock.systemUTC())
    agent(first, None, "hi")._1.run(thread, "My dog is called Rex").value.answer shouldBe Some("hi")
    first.close()

    val store = SqliteFiles.opened(path, Clock.systemUTC())
    val facts = InMemoryStore.withMemories(Seq(Memory.userFact("The dog is called Rex"))).value
    val memory = new SemanticMemoryMiddleware(
      _ => Right(facts),
      (s, _) => SimpleMemoryManager.withStore(s),
      ScriptedLLMClient.of()
    )
    val (remembering, client) = agent(store, Some(memory), "Rex")
    remembering.run(thread, "What is my dog called?").value.answer shouldBe Some("Rex")
    client.sent.head.collect { case s: SystemMessage => s.content }.mkString should include("The dog is called Rex")
    store.close()

    val reopened = SqliteFiles.opened(path, Clock.systemUTC())
    agent(reopened, None, "bye")._1.run(thread, "Goodbye").value.answer shouldBe Some("bye")
    reopened.close()
  }
}
