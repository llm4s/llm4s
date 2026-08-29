package org.llm4s.vectorstore

import org.llm4s.error.ProcessingError
import org.llm4s.reranker.{ RerankRequest, RerankResponse, RerankResult, Reranker }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class AsyncHybridSearcherSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private var vectorStore: VectorStore      = _
  private var keywordIndex: KeywordIndex    = _
  private var searcher: AsyncHybridSearcher = _

  private val docs = Seq(
    ("scala-guide", "Scala is a powerful functional programming language", Array(0.9f, 0.1f, 0.0f)),
    ("scala-jvm", "Scala runs on the JVM and interoperates with Java", Array(0.8f, 0.2f, 0.1f)),
    ("python-intro", "Python is a dynamically typed programming language", Array(0.2f, 0.9f, 0.0f)),
    ("java-basics", "Java is an object-oriented programming language", Array(0.3f, 0.8f, 0.2f)),
    ("rust-systems", "Rust is a systems programming language focused on safety", Array(0.1f, 0.3f, 0.9f))
  )

  override def beforeEach(): Unit = {
    vectorStore = VectorStoreFactory.inMemory().fold(e => fail(s"Failed: ${e.formatted}"), identity)
    keywordIndex = KeywordIndex.inMemory().fold(e => fail(s"Failed: ${e.formatted}"), identity)

    docs.foreach { case (id, content, embedding) =>
      vectorStore.upsert(VectorRecord(id, embedding, Some(content), Map("type" -> "doc")))
      keywordIndex.index(KeywordDocument(id, content, Map("type" -> "doc")))
    }

    searcher = AsyncHybridSearcher(
      AsyncVectorStore(vectorStore),
      AsyncKeywordIndex(keywordIndex)
    )
  }

  override def afterEach(): Unit =
    if (searcher != null) searcher.close()

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  "AsyncHybridSearcher" should {

    "perform VectorOnly search" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(searcher.search(queryEmbedding, "ignored", topK = 3, strategy = FusionStrategy.VectorOnly))

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      matches.size shouldBe 3
      matches.head.id should startWith("scala")
      matches.head.vectorScore shouldBe defined
      matches.head.keywordScore shouldBe None
    }

    "perform KeywordOnly search" in {
      val result = await(
        searcher.search(Array(0.0f, 0.0f, 0.0f), "Scala programming", topK = 3, strategy = FusionStrategy.KeywordOnly)
      )

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      matches.nonEmpty shouldBe true
      matches.head.keywordScore shouldBe defined
      matches.head.vectorScore shouldBe None
    }

    "perform RRF fusion search" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result =
        await(searcher.search(queryEmbedding, "Scala programming", topK = 3, strategy = FusionStrategy.RRF()))

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      matches.nonEmpty shouldBe true
      // RRF combines both scores
      matches.head.score should be > 0.0
    }

    "perform WeightedScore fusion search" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(
        searcher.search(
          queryEmbedding,
          "Scala programming",
          topK = 3,
          strategy = FusionStrategy.WeightedScore(vectorWeight = 0.7, keywordWeight = 0.3)
        )
      )

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      matches.nonEmpty shouldBe true
      matches.head.score should be > 0.0
      matches.head.score should be <= 1.0
    }

    "use default strategy when none specified" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(searcher.search(queryEmbedding, "Scala"))

      result shouldBe a[Right[_, _]]
      result.toOption.get.nonEmpty shouldBe true
    }

    "respect topK limit" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(searcher.search(queryEmbedding, "programming", topK = 2, strategy = FusionStrategy.RRF()))

      result shouldBe a[Right[_, _]]
      result.toOption.get.size should be <= 2
    }

    "return empty results for non-matching keyword search" in {
      val result = await(
        searcher.search(Array(0.0f, 0.0f, 0.0f), "xyznonexistent", topK = 3, strategy = FusionStrategy.KeywordOnly)
      )

      result shouldBe a[Right[_, _]]
      result.toOption.get shouldBe empty
    }

    "searchWithReranking without reranker returns top results" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(
        searcher.searchWithReranking(
          queryEmbedding,
          "Scala programming",
          topK = 2,
          rerankTopK = 5,
          reranker = None
        )
      )

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      matches.size should be <= 2
    }

    "searchWithReranking applies reranker scores" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val mockReranker = new Reranker {
        override def rerank(request: RerankRequest): Result[RerankResponse] = {
          // Reverse the order: give highest score to last document
          val results = request.documents.zipWithIndex
            .map { case (doc, idx) =>
              RerankResult(index = idx, score = 1.0 - (idx * 0.1), document = doc)
            }
            .sortBy(-_.score)
          Right(RerankResponse(results.take(request.topK.getOrElse(results.size))))
        }
      }

      val result = await(
        searcher.searchWithReranking(
          queryEmbedding,
          "Scala programming",
          topK = 3,
          rerankTopK = 5,
          reranker = Some(mockReranker)
        )
      )

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      matches.nonEmpty shouldBe true
      // Scores should come from reranker
      matches.foreach(m => m.score should be > 0.0)
    }

    "searchWithReranking handles reranker errors gracefully" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val failingReranker = new Reranker {
        override def rerank(request: RerankRequest): Result[RerankResponse] =
          Left(ProcessingError("reranker", "Service unavailable"))
      }

      val result = await(
        searcher.searchWithReranking(
          queryEmbedding,
          "Scala",
          topK = 3,
          rerankTopK = 5,
          reranker = Some(failingReranker)
        )
      )

      result shouldBe a[Left[_, _]]
    }

    "searchWithReranking handles reranker exceptions gracefully" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val throwingReranker = new Reranker {
        override def rerank(request: RerankRequest): Result[RerankResponse] =
          throw new RuntimeException("Unexpected failure")
      }

      val result = await(
        searcher.searchWithReranking(
          queryEmbedding,
          "Scala",
          topK = 3,
          rerankTopK = 5,
          reranker = Some(throwingReranker)
        )
      )

      result shouldBe a[Left[_, _]]
    }

    "searchWithReranking handles out-of-range reranker indices safely" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val badIndexReranker = new Reranker {
        override def rerank(request: RerankRequest): Result[RerankResponse] =
          Right(
            RerankResponse(
              Seq(
                RerankResult(index = 0, score = 0.9, document = "doc"),
                RerankResult(index = 999, score = 0.8, document = "ghost"), // out of range
                RerankResult(index = 1, score = 0.7, document = "doc2")
              )
            )
          )
      }

      val result = await(
        searcher.searchWithReranking(
          queryEmbedding,
          "Scala",
          topK = 3,
          rerankTopK = 5,
          reranker = Some(badIndexReranker)
        )
      )

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      // Out-of-range index (999) should be silently filtered out via .lift
      matches.size shouldBe 2
    }

    "construct from sync HybridSearcher" in {
      val syncSearcher   = HybridSearcher(vectorStore, keywordIndex)
      val asyncFromSync  = AsyncHybridSearcher(syncSearcher)
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(asyncFromSync.search(queryEmbedding, "Scala", topK = 2, strategy = FusionStrategy.VectorOnly))

      result shouldBe a[Right[_, _]]
      result.toOption.get.nonEmpty shouldBe true
      asyncFromSync.close()
    }

    "RRF fusion returns results with combined scores from both sources" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(searcher.search(queryEmbedding, "Scala", topK = 5, strategy = FusionStrategy.RRF(k = 60)))

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      // Documents appearing in both vector and keyword results should have higher scores
      matches.nonEmpty shouldBe true
      // Results should be sorted by score descending
      matches.zip(matches.tail).foreach { case (a, b) => a.score should be >= b.score }
    }

    "WeightedScore normalizes scores correctly" in {
      val queryEmbedding = Array(0.85f, 0.15f, 0.0f)

      val result = await(
        searcher.search(
          queryEmbedding,
          "Scala",
          topK = 5,
          strategy = FusionStrategy.WeightedScore(vectorWeight = 1.0, keywordWeight = 0.0)
        )
      )

      result shouldBe a[Right[_, _]]
      val matches = result.toOption.get
      matches.nonEmpty shouldBe true
      // With keyword weight 0, results should be purely vector-based
      matches.zip(matches.tail).foreach { case (a, b) => a.score should be >= b.score }
    }
  }
}
