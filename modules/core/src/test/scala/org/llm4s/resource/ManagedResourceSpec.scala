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
}
