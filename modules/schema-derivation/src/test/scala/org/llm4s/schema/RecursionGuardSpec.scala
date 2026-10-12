package org.llm4s.schema

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * A recursion the compile-time check cannot see, because it passes through a hand-written given that derives its
 * type, fails on first use with a message naming the type. Every case runs on another thread under a timeout, so
 * that a regression to the old deadlock fails this spec instead of hanging the build.
 */
class RecursionGuardSpec extends AnyFlatSpec with Matchers {

  import RecursionFixtures.*

  private val timeout = 30.seconds

  /** Runs `body` on another thread and waits for it, failing (not hanging) if it does not finish in time. */
  private def withinTimeout[T](body: => T): T = Await.result(Future(body), timeout)

  /** Making the instance must succeed; building its schema must throw the recursion error. */
  private def refused(make: => SchemaOf[?]): String =
    withinTimeout {
      val instance = make
      val error    = intercept[IllegalStateException](instance.toJsonSchema())
      error.getMessage
    }

  private def recursionMessage(message: String, types: String*): Unit = {
    message should include("recursive type")
    message should include("llm4s-schema-derivation cannot describe recursive types")
    message should include("core's schema model has no $ref")
    types.exists(t => message.contains(s"recursive type $t:")) shouldBe true
  }

  "Building a schema" should "refuse mutually recursive types whose hand-written givens both derive" in {
    import BothGivens.Gs.given
    recursionMessage(refused(SchemaOf[BothGivens.P]), "P")
    recursionMessage(refused(SchemaOf[BothGivens.Q]), "Q")
  }

  it should "refuse them when one side derives and the other's given derives through a helper" in {
    recursionMessage(refused(SchemaOf[DerivesAndHelper.P]), "P")
    recursionMessage(refused(SchemaOf[DerivesAndHelper.Q]), "Q")
  }

  it should "refuse them when the instances are plain vals" in {
    import PlainVals.Gs.given
    recursionMessage(refused(SchemaOf[PlainVals.P]), "P")
    recursionMessage(refused(SchemaOf[PlainVals.Q]), "Q")
  }

  it should "refuse them when the givens are defs, which make a new instance on every summon" in {
    import DefGivens.Gs.given
    recursionMessage(refused(SchemaOf[DefGivens.P]), "P", "Q")
  }

  it should "refuse the same instance again on every call, and leave the thread able to build other schemas" in {
    withinTimeout {
      val instance = SchemaOf[DerivesAndHelper.P]
      intercept[IllegalStateException](instance.definition)
      intercept[IllegalStateException](instance.definition)
      SchemaOf[Fixtures.Boxes].toJsonSchema()("type").str shouldBe "object"
    }
  }

  it should "still describe the same shape when the given closing the loop does not derive" in {
    val schema = withinTimeout(SchemaOf[ClosedByHand.P].toJsonSchema())
    ujson.write(schema) shouldBe
      """{"type":"object","description":"P","properties":{"q":{"type":["string","null"],""" +
      """"description":"the Q, as text"}},"required":["q"],"additionalProperties":false}"""
  }

  it should "build schemas of one type on many threads at once" in {
    val schemas =
      Await.result(Future.sequence((1 to 16).map(_ => Future(SchemaOf[Fixtures.Nested].toJsonSchema()))), timeout)
    schemas.distinct should have size 1
  }

  "The recursion guard" should "be restored when a field's instance throws an ExceptionInInitializerError" in {
    import Throwing.*
    withinTimeout {
      intercept[ExceptionInInitializerError](SchemaOf[HoldsLinkage].definition)
      SchemaOf.buildsInProgress shouldBe 0
      // the same instance on the same thread reports the real error again, not "recursive type HoldsLinkage"
      intercept[ExceptionInInitializerError](SchemaOf[HoldsLinkage].definition)
      SchemaOf.buildsInProgress shouldBe 0
      SchemaOf[Fixtures.Boxes].toJsonSchema()("type").str shouldBe "object"
    }
  }

  it should "be restored when a field's companion fails to initialise, and report the real error after it" in {
    import Throwing.*
    withinTimeout {
      val first = intercept[ExceptionInInitializerError](SchemaOf[HoldsBoom].toJsonSchema())
      first.getCause.getMessage shouldBe "Boom's initialiser fails"
      SchemaOf.buildsInProgress shouldBe 0
      // the JVM reports a class whose initialiser failed as NoClassDefFoundError from then on
      intercept[NoClassDefFoundError](SchemaOf[HoldsBoom].toJsonSchema())
      SchemaOf.buildsInProgress shouldBe 0
    }
  }

  it should "be restored on an InterruptedException, leaving the thread's interrupt flag as the field set it" in {
    import Throwing.*
    withinTimeout {
      intercept[InterruptedException](SchemaOf[HoldsInterrupted].definition)
      // `interrupted` reads and clears the flag, so the pool thread is not left interrupted
      Thread.interrupted() shouldBe true
      SchemaOf.buildsInProgress shouldBe 0
      intercept[InterruptedException](SchemaOf[HoldsInterrupted].definition)
      Thread.interrupted() shouldBe true
      SchemaOf.buildsInProgress shouldBe 0
    }
  }

  it should "be restored on a ControlThrowable" in {
    import Throwing.*
    withinTimeout {
      intercept[Control.Thrown](SchemaOf[HoldsControl].definition)
      SchemaOf.buildsInProgress shouldBe 0
      intercept[Control.Thrown](SchemaOf[HoldsControl].definition)
      SchemaOf.buildsInProgress shouldBe 0
    }
  }

  "A generic case class nested in itself" should "be described up to 16 levels deep and refused at 17" in {
    import Nesting.*
    withinTimeout {
      val sixteen = SchemaOf[W16[Int]].toJsonSchema()
      Iterator.iterate(sixteen)((w: ujson.Value) => w("properties")("a")).drop(16).next()("type").str shouldBe "integer"
      recursionMessage(intercept[IllegalStateException](SchemaOf[W17[Int]].toJsonSchema()).getMessage, "W")
      SchemaOf.buildsInProgress shouldBe 0
    }
  }
}
