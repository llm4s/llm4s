package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * How `getRelevantContext` fits the retrieved memories into `maxTokens` (#1580): a section heading
 * is written only when at least one entry under it fits, nothing at all is written when no entry
 * fits, and the whole text - headings and the `# Retrieved Context` line included - stays within
 * `maxTokens * 4` characters.
 */
class ContextBudgetSpec extends AnyFlatSpec with Matchers with EitherValues {

  /** Exposes `formatMemoriesAsContext` with a fixed memory order, independent of any search. */
  final private class Renderer extends BaseMemoryManagerOps {
    override val store: MemoryStore          = InMemoryStore.empty
    override val config: MemoryManagerConfig = MemoryManagerConfig.default

    override protected def withStore(updatedStore: MemoryStore): MemoryManager                        = this
    override def consolidateMemories(olderThan: Instant, minCount: Int): Result[MemoryManager]        = Right(this)
    override def extractEntities(text: String, conversationId: Option[String]): Result[MemoryManager] = Right(this)

    def render(memories: Seq[Memory], maxChars: Int): String = formatMemoriesAsContext(memories, maxChars)
  }

  private val renderer = new Renderer
  private val at       = Instant.parse("2026-01-01T00:00:00Z")

  private def memory(id: String, content: String, memoryType: MemoryType): Memory =
    Memory(MemoryId(id), content, memoryType, timestamp = at)

  /** One memory or more of every built-in type and one custom type. */
  private val allTypes: Seq[Memory] = Seq(
    memory("k1", "Scala 3 has opaque types", MemoryType.Knowledge),
    memory("k2", "Scala runs on the JVM", MemoryType.Knowledge),
    memory("e1", "Anthropic created Claude", MemoryType.Entity),
    memory("u1", "Prefers Scala over Java", MemoryType.UserFact),
    memory("c1", "We talked about Scala", MemoryType.Conversation),
    memory("t1", "Task: port to Scala 3\nOutcome: done", MemoryType.Task),
    memory("x1", "Use the release checklist", MemoryType.Custom("Notes"))
  )

  /** What `main` rendered for `allTypes` with a roomy budget before #1580, byte for byte. */
  private val goldenRoomy: String =
    "# Retrieved Context\n" +
      "## Relevant Knowledge\n- Scala 3 has opaque types\n- Scala runs on the JVM\n\n" +
      "## Entity Information\n- Anthropic created Claude\n\n" +
      "## User Preferences\n- Prefers Scala over Java\n\n" +
      "## Previous Context\n- We talked about Scala\n\n" +
      "## Past Tasks\n- Task: port to Scala 3\nOutcome: done\n\n" +
      "## Notes\n- Use the release checklist"

  private val headingLine = "## "
  private val entryLine   = "- "

  /** Every heading line is directly followed by an entry line. */
  private def everyHeadingHasAnEntry(text: String): Boolean = {
    val lines = text.linesIterator.toVector
    lines.indices.forall(i => !lines(i).startsWith(headingLine) || lines.lift(i + 1).exists(_.startsWith(entryLine)))
  }

  "formatMemoriesAsContext" should "render a roomy budget exactly as before" in {
    renderer.render(allTypes, maxChars = 10000) shouldBe goldenRoomy
    renderer.render(allTypes, maxChars = goldenRoomy.length) shouldBe goldenRoomy
  }

  it should "return an empty string when no entry fits, with no top-level header" in {
    val one = Seq(memory("k1", "Scala 3 has opaque types", MemoryType.Knowledge))
    // The whole text for this memory is 68 characters; `main` wrote a bare heading below that.
    renderer.render(one, maxChars = 0) shouldBe ""
    renderer.render(one, maxChars = 20) shouldBe ""
    renderer.render(one, maxChars = 40) shouldBe ""
    renderer.render(one, maxChars = 67) shouldBe ""
    renderer.render(
      one,
      maxChars = 68
    ) shouldBe "# Retrieved Context\n## Relevant Knowledge\n- Scala 3 has opaque types"
  }

  it should "leave out the heading of a section whose first entry does not fit" in {
    val mems = Seq(
      memory("k1", "short", MemoryType.Knowledge),
      memory("u1", "a user fact far too long to fit into the remaining budget", MemoryType.UserFact)
    )
    val text = renderer.render(mems, maxChars = 60)
    text shouldBe "# Retrieved Context\n## Relevant Knowledge\n- short"
    (text should not).include("User Preferences")
  }

  it should "still fill a later section when an earlier one has no room for its first entry" in {
    val mems = Seq(
      memory("k1", "a knowledge entry that is far too long to fit into this small budget", MemoryType.Knowledge),
      memory("u1", "short", MemoryType.UserFact)
    )
    renderer.render(mems, maxChars = 60) shouldBe "# Retrieved Context\n## User Preferences\n- short"
  }

  it should "never write a heading without an entry, or exceed the budget, at any budget" in {
    (0 to goldenRoomy.length + 10).foreach { maxChars =>
      val text = renderer.render(allTypes, maxChars)
      withClue(s"maxChars = $maxChars:\n$text\n") {
        text.length should be <= maxChars
        everyHeadingHasAnEntry(text) shouldBe true
        if (text.nonEmpty) {
          text should startWith("# Retrieved Context\n## ")
          text.linesIterator.count(_.startsWith(entryLine)) should be >= 1
        }
      }
    }
  }

  it should "count a heading only when an entry under it fits" in {
    // The middle section's first entry does not fit, so its heading is not written and its
    // characters are not charged: the last section still fits exactly.
    val mems = Seq(
      memory("k1", "aaaa", MemoryType.Knowledge),
      memory("e1", "an entity fact far too long to fit into the remaining budget", MemoryType.Entity),
      memory("u1", "bbbb", MemoryType.UserFact)
    )
    val expected = "# Retrieved Context\n## Relevant Knowledge\n- aaaa\n\n## User Preferences\n- bbbb"
    renderer.render(mems, maxChars = expected.length) shouldBe expected
    renderer.render(mems, maxChars = expected.length - 1) shouldBe "# Retrieved Context\n## Relevant Knowledge\n- aaaa"
  }

  "getRelevantContext" should "return an empty string when maxTokens leaves no room for any memory" in {
    val context = for {
      manager <- SimpleMemoryManager.empty.recordKnowledge("Scala 3 has opaque types", "docs")
      text    <- manager.getRelevantContext("Scala", maxTokens = 5)
    } yield text
    context.value shouldBe ""
  }

  it should "keep the whole text within maxTokens * 4 characters, with a heading only over an entry" in {
    val manager = allTypes.foldLeft[Result[MemoryManager]](Right(SimpleMemoryManager.empty)) { (acc, m) =>
      acc.flatMap(mm => mm.store.store(m).map(SimpleMemoryManager.withStore(_)))
    }
    (0 to 60).foreach { maxTokens =>
      val text = manager.flatMap(_.getRelevantContext("Scala", maxTokens = maxTokens)).value
      withClue(s"maxTokens = $maxTokens:\n$text\n") {
        text.length should be <= maxTokens * 4
        everyHeadingHasAnEntry(text) shouldBe true
      }
    }
  }
}
