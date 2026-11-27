package org.llm4s.vectorstore.testkit

import org.llm4s.annotation.Experimental
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.{ Failure, Success, Try, Using }

/**
 * How [[VectorStoreContract]] and [[KeywordIndexContract]] register their cases against a store: a fresh store per
 * case, released afterwards, and two ways for a backend's spec to say that a case does not hold for it yet.
 *
 *  - [[knownFailures]]: a case the backend fails today because of a known defect, named with the issue that tracks
 *    it. The case still runs, as ScalaTest's `pendingUntilFixed`: it is reported pending while it fails and fails
 *    once it passes, so the change that fixes the defect also removes the entry.
 *  - [[unsupported]]: a case the backend cannot run at all, such as a query a search engine does not offer, with the
 *    reason. It is cancelled.
 *
 * A name in either map that matches no case fails the suite, so a renamed case cannot silently stop being excused.
 */
@Experimental
trait StoreContractCases[S] extends AnyFunSuiteLike with Matchers {

  /** A fresh, empty store. Called once per case, outside the case's pending scope. */
  protected def newStore(): S

  /** Releases a store [[newStore]] made, after its case. */
  protected def releaseStore(store: S): Unit

  /** Cases this backend is known to fail, by case name, each with the issue that tracks it. */
  protected def knownFailures: Map[String, String] = Map.empty

  /** Cases this backend cannot run, by case name, each with the reason. */
  protected def unsupported: Map[String, String] = Map.empty

  /** What the contract is about, used in the name of its own consistency check. */
  protected def contractName: String

  private val caseNames = mutable.LinkedHashSet.empty[String]

  /** Registers one contract case, run against a fresh store. */
  protected def contractCase(name: String)(body: S => Any): Unit = {
    require(caseNames.add(name), s"duplicate contract case: $name")
    test(name) {
      unsupported.get(name).foreach(reason => cancel(s"unsupported by this backend: $reason"))
      val releasable: Using.Releasable[S] = store => releaseStore(store)
      Using.resource(newStore()) { store =>
        knownFailures.get(name) match {
          case Some(issue) =>
            info(s"known failure: $issue")
            pendingUntilFixed { body(store); () }
          case None => body(store)
        }
      }(using releasable)
    }
  }

  /** Registered after the cases, by the contract: every excused name is a case. */
  protected def registerConsistencyCheck(): Unit =
    test(s"$contractName: every known failure and unsupported case names a case of the contract") {
      (knownFailures.keySet ++ unsupported.keySet).diff(caseNames.toSet) shouldBe empty
    }

  /** The value of `result`, failing the case with the error, or with the exception if the call threw. */
  protected def ok[A](result: => Result[A]): A =
    Try(result) match {
      case Success(Right(a)) => a
      case Success(Left(e))  => fail(s"expected Right, got Left: ${e.formatted}")
      case Failure(t)        => fail(s"the call threw instead of returning a Result: $t")
    }

  /** Whether the call returned a `Left`, failing the case if it threw. */
  protected def isLeft[A](result: => Result[A]): Boolean =
    Try(result) match {
      case Success(r) => r.isLeft
      case Failure(t) => fail(s"the call threw instead of returning a Result: $t")
    }
}
