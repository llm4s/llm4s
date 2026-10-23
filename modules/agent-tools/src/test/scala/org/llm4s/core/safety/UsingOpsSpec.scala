package org.llm4s.core.safety

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/** [[UsingOps.using]] and [[UsingOps.using2]]: the resource is closed however `f` ends. */
class UsingOpsSpec extends AnyFlatSpec with Matchers {

  /** A resource that appends its name to a shared log when closed, optionally failing on close. */
  final private class Res(name: String, log: ListBuffer[String], closeFailure: Option[Exception] = None)
      extends AutoCloseable {
    override def close(): Unit = {
      log += name
      closeFailure.foreach(e => throw e)
    }
  }

  "UsingOps.using" should "return the value of f and close the resource once" in {
    val log    = ListBuffer.empty[String]
    val result = UsingOps.using(new Res("a", log))(_ => 42)
    result shouldBe 42
    log.toList shouldBe List("a")
  }

  it should "close the resource and rethrow when f throws" in {
    val log = ListBuffer.empty[String]
    val ex = intercept[IllegalStateException] {
      UsingOps.using(new Res("a", log))(_ => throw new IllegalStateException("boom"))
    }
    ex.getMessage shouldBe "boom"
    log.toList shouldBe List("a")
  }

  it should "run f and not fail on a null resource" in {
    var ran = false
    val result = UsingOps.using[AutoCloseable, Int](null) { _ =>
      ran = true
      7
    }
    result shouldBe 7
    ran shouldBe true
  }

  it should "propagate the exception from close when f succeeds" in {
    val log = ListBuffer.empty[String]
    val ex = intercept[IllegalArgumentException] {
      UsingOps.using(new Res("a", log, Some(new IllegalArgumentException("close failed"))))(_ => 1)
    }
    ex.getMessage shouldBe "close failed"
    log.toList shouldBe List("a")
  }

  it should "let the exception from close replace the one from f when both throw" in {
    val log = ListBuffer.empty[String]
    val ex = intercept[IllegalArgumentException] {
      UsingOps.using(new Res("a", log, Some(new IllegalArgumentException("close failed")))) { _ =>
        throw new IllegalStateException("f failed")
      }
    }
    ex.getMessage shouldBe "close failed"
    log.toList shouldBe List("a")
  }

  "UsingOps.using2" should "return the value of f and close both resources, the second first" in {
    val log    = ListBuffer.empty[String]
    val result = UsingOps.using2(new Res("first", log), new Res("second", log))((_, _) => "done")
    result shouldBe "done"
    log.toList shouldBe List("second", "first")
  }

  it should "close both resources and rethrow when f throws" in {
    val log = ListBuffer.empty[String]
    val ex = intercept[IllegalStateException] {
      UsingOps.using2(new Res("first", log), new Res("second", log))((_, _) => throw new IllegalStateException("boom"))
    }
    ex.getMessage shouldBe "boom"
    log.toList shouldBe List("second", "first")
  }
}
