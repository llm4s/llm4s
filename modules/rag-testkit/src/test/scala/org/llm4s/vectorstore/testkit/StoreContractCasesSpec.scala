package org.llm4s.vectorstore.testkit

import org.llm4s.error.ValidationError
import org.scalatest.{ Args, Reporter }
import org.scalatest.events._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/** How a contract case is reported when a backend excuses it, run against a tiny contract of three cases. */
class StoreContractCasesSpec extends AnyFunSuite with Matchers {

  import StoreContractCasesSpec._

  private def outcomes(suite: TinyContract): Map[String, String] = {
    val events = new ConcurrentLinkedQueue[Event]()
    suite.run(None, Args(new Reporter { def apply(e: Event): Unit = events.add(e) }))
    events.asScala.collect {
      case e: TestSucceeded => e.testName -> "succeeded"
      case e: TestFailed    => e.testName -> "failed"
      case e: TestPending   => e.testName -> "pending"
      case e: TestCanceled  => e.testName -> "canceled"
    }.toMap
  }

  test("a case runs against a fresh store, released afterwards, whatever its outcome") {
    val suite = new TinyContract()
    (outcomes(suite) should contain).allOf("passes" -> "succeeded", "fails" -> "failed", "returns Left" -> "failed")
    Released.get(suite) shouldBe 3
  }

  test("a known failure that still fails is pending; one that passes fails, so its entry is removed") {
    val suite = new TinyContract(known = Map("fails" -> "#1", "passes" -> "#2"))
    val seen  = outcomes(suite)
    seen("fails") shouldBe "pending"
    seen("passes") shouldBe "failed"
  }

  test("an unsupported case is cancelled without opening a store") {
    val suite = new TinyContract(skip = Map("fails" -> "no such query"))
    outcomes(suite)("fails") shouldBe "canceled"
    Released.get(suite) shouldBe 2
  }

  test("an excused name that matches no case fails the consistency check") {
    outcomes(new TinyContract())(ConsistencyCheck) shouldBe "succeeded"
    outcomes(new TinyContract(known = Map("renamed" -> "#3")))(ConsistencyCheck) shouldBe "failed"
    outcomes(new TinyContract(skip = Map("renamed" -> "gone")))(ConsistencyCheck) shouldBe "failed"
  }

  test("ok and isLeft report a call that throws as a failure, not an error") {
    val suite = new TinyContract()
    an[org.scalatest.exceptions.TestFailedException] should be thrownBy suite.callOk(throw new RuntimeException("boom"))
    an[org.scalatest.exceptions.TestFailedException] should be thrownBy suite.callIsLeft(
      throw new RuntimeException("x")
    )
    suite.callIsLeft(Left(ValidationError("v", "bad"))) shouldBe true
    suite.callOk(Right(1)) shouldBe 1
  }
}

private object StoreContractCasesSpec {

  val ConsistencyCheck = "Tiny: every known failure and unsupported case names a case of the contract"

  /** How many stores each suite released, kept outside the suite, whose cases are registered while it is built. */
  object Released {
    private val counts           = new java.util.concurrent.ConcurrentHashMap[AnyRef, Int]()
    def add(suite: AnyRef): Unit = counts.merge(suite, 1, _ + _)
    def get(suite: AnyRef): Int  = counts.getOrDefault(suite, 0)
  }

  final class TinyContract(known: Map[String, String] = Map.empty, skip: Map[String, String] = Map.empty)
      extends AnyFunSuite
      with StoreContractCases[String] {
    protected def contractName: String                        = "Tiny"
    protected def newStore(): String                          = "store"
    protected def releaseStore(store: String): Unit           = Released.add(this)
    override protected def knownFailures: Map[String, String] = known
    override protected def unsupported: Map[String, String]   = skip

    def callOk[A](r: => org.llm4s.types.Result[A]): A           = ok(r)
    def callIsLeft[A](r: => org.llm4s.types.Result[A]): Boolean = isLeft(r)

    contractCase("passes")(store => store shouldBe "store")
    contractCase("fails")(_ => fail("defect"))
    contractCase("returns Left")(_ => ok(Left(ValidationError("v", "bad"))))
    registerConsistencyCheck()
  }
}
