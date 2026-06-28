package org.llm4s.vectorstore

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class AsyncKeywordIndexSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private var syncIndex: KeywordIndex       = _
  private var asyncIndex: AsyncKeywordIndex = _

  private val testDoc = KeywordDocument("doc-1", "Scala is a functional programming language", Map("lang" -> "scala"))

  override def beforeEach(): Unit = {
    syncIndex = KeywordIndex.inMemory().fold(e => fail(s"Failed: ${e.formatted}"), identity)
    asyncIndex = AsyncKeywordIndex(syncIndex)
  }

  override def afterEach(): Unit =
    if (asyncIndex != null) asyncIndex.close()

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  "AsyncKeywordIndexWrapper" should {

    "delegate index to sync store" in {
      val result = await(asyncIndex.index(testDoc))
      result shouldBe Right(())
      syncIndex.get("doc-1").toOption.flatten shouldBe defined
    }

    "delegate indexBatch to sync store" in {
      val docs = Seq(
        KeywordDocument("batch-1", "First document"),
        KeywordDocument("batch-2", "Second document")
      )
      val result = await(asyncIndex.indexBatch(docs))
      result shouldBe Right(())
      syncIndex.get("batch-1").toOption.flatten shouldBe defined
      syncIndex.get("batch-2").toOption.flatten shouldBe defined
    }

    "delegate search to sync store" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.search("Scala", topK = 5))
      result shouldBe a[Right[_, _]]
      result.toOption.get.nonEmpty shouldBe true
      result.toOption.get.head.id shouldBe "doc-1"
    }

    "delegate searchWithHighlights to sync store" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.searchWithHighlights("Scala", topK = 5))
      result shouldBe a[Right[_, _]]
      result.toOption.get.nonEmpty shouldBe true
    }

    "delegate get to sync store" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.get("doc-1"))
      result shouldBe a[Right[_, _]]
      result.toOption.get shouldBe defined
      result.toOption.get.get.content shouldBe testDoc.content
    }

    "delegate get returns None for missing document" in {
      val result = await(asyncIndex.get("nonexistent"))
      result shouldBe Right(None)
    }

    "delegate delete to sync store" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.delete("doc-1"))
      result shouldBe Right(())
      syncIndex.get("doc-1").toOption.flatten shouldBe None
    }

    "delegate deleteBatch to sync store" in {
      syncIndex.index(testDoc)
      syncIndex.index(KeywordDocument("doc-2", "Another document"))
      val result = await(asyncIndex.deleteBatch(Seq("doc-1", "doc-2")))
      result shouldBe Right(())
      syncIndex.count().toOption.get shouldBe 0L
    }

    "delegate count to sync store" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.count())
      result shouldBe Right(1L)
    }

    "delegate clear to sync store" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.clear())
      result shouldBe Right(())
      syncIndex.count().toOption.get shouldBe 0L
    }

    "delegate stats to sync store" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.stats())
      result shouldBe a[Right[_, _]]
      result.toOption.get.totalDocuments shouldBe 1L
    }

    "implement update as delete + re-index" in {
      syncIndex.index(testDoc)
      val updated = KeywordDocument("doc-1", "Updated Scala content", Map("lang" -> "scala3"))
      val result  = await(asyncIndex.update(updated))
      result shouldBe Right(())

      val retrieved = syncIndex.get("doc-1").toOption.flatten
      retrieved shouldBe defined
      retrieved.get.content shouldBe "Updated Scala content"
    }

    "return empty search results for non-matching query" in {
      syncIndex.index(testDoc)
      val result = await(asyncIndex.search("xyznonexistent"))
      result shouldBe a[Right[_, _]]
      result.toOption.get shouldBe empty
    }
  }

  "AsyncKeywordIndex companion" should {

    "create wrapper via apply factory" in {
      val index = AsyncKeywordIndex(syncIndex)
      index shouldBe a[AsyncKeywordIndexWrapper]
    }

    "create in-memory instance via inMemory factory" in {
      val result = AsyncKeywordIndex.inMemory()
      result shouldBe a[Right[_, _]]
      result.toOption.get shouldBe a[AsyncKeywordIndexWrapper]
    }
  }
}
