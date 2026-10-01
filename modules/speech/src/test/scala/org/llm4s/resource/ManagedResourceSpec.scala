package org.llm4s.resource

import org.llm4s.error.NotFoundError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

class ManagedResourceSpec extends AnyFlatSpec with Matchers {

  class CountingResource {
    val acquireCount: AtomicInteger = new AtomicInteger(0)
    val releaseCount: AtomicInteger = new AtomicInteger(0)

    val managed: ManagedResource[Int] = ManagedResource(
      acquireF = () => {
        acquireCount.incrementAndGet()
        Right(acquireCount.get())
      },
      releaseF = _ => {
        releaseCount.incrementAndGet()
        Right(())
      }
    )
  }

  "ManagedResource.use" should "release the resource when f returns Right" in {
    val counting = new CountingResource

    val result = counting.managed.use(r => Right(r * 2))

    result shouldBe Right(2)
    counting.releaseCount.get() shouldBe 1
  }

  it should "release the resource when f returns Left" in {
    val counting = new CountingResource
    val error    = NotFoundError("thing", "id")

    val result: Result[Int] = counting.managed.use(_ => Left(error))

    result shouldBe Left(error)
    counting.releaseCount.get() shouldBe 1
  }

  it should "release the resource when f throws" in {
    val counting = new CountingResource

    val result: Result[Int] = counting.managed.use(_ => throw new RuntimeException("boom"))

    result.isLeft shouldBe true
    counting.releaseCount.get() shouldBe 1
  }

  it should "keep acquire and release counts equal across many mixed outcomes" in {
    val counting = new CountingResource

    (1 to 1000).foreach { i =>
      i % 3 match {
        case 0 => counting.managed.use(_ => Right(i))
        case 1 => counting.managed.use(_ => Left(NotFoundError("thing", i.toString)))
        case _ => counting.managed.use(_ => throw new RuntimeException("boom"))
      }
    }

    counting.acquireCount.get() shouldBe 1000
    counting.releaseCount.get() shouldBe 1000
  }

  "ManagedResource.use" should "not run f, nor release, when acquire fails" in {
    val error    = NotFoundError("missing", "r")
    var ran      = false
    var released = false
    val managed  = ManagedResource[Int](() => Left(error), _ => { released = true; Right(()) })

    managed.use { _ =>
      ran = true; Right(1)
    } shouldBe Left(error)
    ran shouldBe false
    released shouldBe false
  }

  it should "report a failed release after a successful f" in {
    val error   = NotFoundError("close failed", "r")
    val managed = ManagedResource[Int](() => Right(1), _ => Left(error))
    managed.use(r => Right(r + 1)) shouldBe Left(error)
  }

  "ManagedResource.fromTry" should "turn a throwing acquire into Left" in {
    val managed =
      ManagedResource.fromTry[Int](() => scala.util.Failure(new RuntimeException("boom")), _ => scala.util.Success(()))
    managed.use(r => Right(r)).isLeft shouldBe true
  }

  "ManagedResource.tempFile" should "create a file for the computation and delete it afterwards" in {
    var seen: Option[java.nio.file.Path] = None
    val result = ManagedResource.tempFile("managed-resource-spec", ".tmp").use { path =>
      seen = Some(path)
      Right(java.nio.file.Files.exists(path))
    }
    result shouldBe Right(true)
    seen.exists(java.nio.file.Files.exists(_)) shouldBe false
  }

  "ManagedResource.fileOutputStream" should "write through the stream and close it" in {
    val path = java.nio.file.Files.createTempFile("managed-resource-spec", ".bin")
    val result = ManagedResource.fileOutputStream(path).use { out =>
      out.write(Array[Byte](1, 2, 3))
      Right(())
    }
    result shouldBe Right(())
    java.nio.file.Files.readAllBytes(path).toSeq shouldBe Seq[Byte](1, 2, 3)
    java.nio.file.Files.delete(path)
  }
}
