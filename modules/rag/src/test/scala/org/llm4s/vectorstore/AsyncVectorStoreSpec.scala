package org.llm4s.vectorstore

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class AsyncVectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private var syncStore: VectorStore       = _
  private var asyncStore: AsyncVectorStore = _

  private val testRecord = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f), Some("test content"), Map("key" -> "value"))

  override def beforeEach(): Unit = {
    syncStore = VectorStoreFactory.inMemory().fold(e => fail(s"Failed: ${e.formatted}"), identity)
    asyncStore = AsyncVectorStore(syncStore)
  }

  override def afterEach(): Unit =
    if (asyncStore != null) asyncStore.close()

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  "AsyncPgVectorStore" should {

    "delegate upsert to sync store" in {
      val result = await(asyncStore.upsert(testRecord))
      result shouldBe Right(())

      // Verify via sync store
      syncStore.get("test-1").toOption.flatten shouldBe defined
    }

    "delegate upsertBatch to sync store" in {
      val records = Seq(
        VectorRecord("batch-1", Array(0.1f, 0.2f, 0.3f), Some("doc1")),
        VectorRecord("batch-2", Array(0.4f, 0.5f, 0.6f), Some("doc2"))
      )
      val result = await(asyncStore.upsertBatch(records))
      result shouldBe Right(())

      syncStore.get("batch-1").toOption.flatten shouldBe defined
      syncStore.get("batch-2").toOption.flatten shouldBe defined
    }

    "delegate search to sync store" in {
      syncStore.upsert(testRecord)
      val result = await(asyncStore.search(Array(0.1f, 0.2f, 0.3f), topK = 5))
      result shouldBe a[Right[_, _]]
      result.toOption.get.nonEmpty shouldBe true
      result.toOption.get.head.record.id shouldBe "test-1"
    }

    "delegate get to sync store" in {
      syncStore.upsert(testRecord)
      val result = await(asyncStore.get("test-1"))
      result shouldBe a[Right[_, _]]
      result.toOption.get shouldBe defined
      result.toOption.get.get.id shouldBe "test-1"
    }

    "delegate get returns None for missing record" in {
      val result = await(asyncStore.get("nonexistent"))
      result shouldBe Right(None)
    }

    "delegate getBatch to sync store" in {
      syncStore.upsert(testRecord)
      syncStore.upsert(VectorRecord("test-2", Array(0.4f, 0.5f, 0.6f), Some("doc2")))
      val result = await(asyncStore.getBatch(Seq("test-1", "test-2")))
      result shouldBe a[Right[_, _]]
      result.toOption.get.size shouldBe 2
    }

    "delegate delete to sync store" in {
      syncStore.upsert(testRecord)
      val result = await(asyncStore.delete("test-1"))
      result shouldBe Right(())
      syncStore.get("test-1").toOption.flatten shouldBe None
    }

    "delegate count to sync store" in {
      syncStore.upsert(testRecord)
      val result = await(asyncStore.count())
      result shouldBe Right(1L)
    }

    "delegate clear to sync store" in {
      syncStore.upsert(testRecord)
      val result = await(asyncStore.clear())
      result shouldBe Right(())
      syncStore.count().toOption.get shouldBe 0L
    }

    "delegate stats to sync store" in {
      syncStore.upsert(testRecord)
      val result = await(asyncStore.stats())
      result shouldBe a[Right[_, _]]
      result.toOption.get.totalRecords shouldBe 1L
    }

    "propagate sync store errors as Left in Future" in {
      asyncStore.close()
      // After close, the underlying store returns Left errors
      // This verifies the Future wrapping preserves Result errors
      val result = await(asyncStore.get("test-1"))
      result shouldBe a[Left[_, _]]
    }
  }

  "AsyncVectorStore companion" should {

    "create wrapper via apply factory" in {
      val store = AsyncVectorStore(syncStore)
      store shouldBe a[AsyncPgVectorStore]
    }
  }
}
