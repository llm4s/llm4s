package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ThreadStateSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val count = StateKey.replace[Int]("count", 0)
  private val log   = StateKey.appending[String]("log")
  private val bounded = StateKey[Int, Int]("bounded", 0) { (current, delta) =>
    val next = current + delta
    if next > 10 then Left(ValidationError("bounded", s"$next exceeds 10")) else Right(next)
  }

  private def state(keys: StateKey[?, ?]*): ThreadState = ThreadState.empty(keys.map(k => k.id -> k).toMap)

  "ThreadState" should "read a registered key's initial value until it is written" in {
    val s = state(count, log)
    s.get(count).value shouldBe 0
    s.get(log).value shouldBe Vector.empty
    s.isSet(count) shouldBe false
  }

  it should "apply every update to the current value, including a single writer's" in {
    val s = state(count, log, bounded)
      .applyUpdate(
        StateUpdate
          .update(count, 1)
          .update(count, 2)
          .update(log, "a")
          .update(log, "b")
          .update(bounded, 3)
          .update(bounded, 4)
      )
      .value
    s.get(count).value shouldBe 2
    s.get(log).value shouldBe Vector("a", "b")
    s.get(bounded).value shouldBe 7
    s.isSet(count) shouldBe true
  }

  it should "compose updates sequentially with combine" in {
    val first  = StateUpdate.update(log, "1").update(count, 1)
    val second = StateUpdate.update(log, "2").update(count, 2)
    val s      = state(count, log).applyUpdate(first.combine(second)).value
    s.get(log).value shouldBe Vector("1", "2")
    s.get(count).value shouldBe 2
    first.combine(StateUpdate.empty).operations shouldBe first.operations
    StateUpdate.empty.isEmpty shouldBe true
  }

  it should "restore the initial value when a key is removed" in {
    val s = state(log)
      .applyUpdate(StateUpdate.update(log, "a").remove(log).update(log, "b"))
      .value
    s.get(log).value shouldBe Vector("b")
    val cleared = s.applyUpdate(StateUpdate.remove(log)).value
    cleared.get(log).value shouldBe Vector.empty
    cleared.isSet(log) shouldBe false
  }

  it should "report the key when its update function rejects an update" in {
    val error = state(bounded).applyUpdate(StateUpdate.update(bounded, 6).update(bounded, 6)).left.value
    error shouldBe a[GraphError.StateUpdateFailed]
    error.asInstanceOf[GraphError.StateUpdateFailed].keyId shouldBe bounded.id
  }

  it should "turn an update function that throws into a failed update" in {
    val throwing = StateKey[Int, String]("parsed", 0)((_, raw) => Right(raw.toInt))
    val error    = state(throwing).applyUpdate(StateUpdate.update(throwing, "not a number")).left.value
    error shouldBe a[GraphError.StateUpdateFailed]
    error.message should include("not a number")
  }

  it should "reject reads and writes of an unregistered key" in {
    val s = state(count)
    s.get(log).left.value shouldBe GraphError.UnknownStateKey(log.id)
    s.applyUpdate(StateUpdate.update(log, "x")).left.value shouldBe GraphError.UnknownStateKey(log.id)
    s.isSet(log) shouldBe false
  }

  it should "reject a different key instance that shares a registered id" in {
    val impostor = StateKey.replace[Int]("count", 99)
    state(count).get(impostor).left.value shouldBe GraphError.UnknownStateKey(impostor.id)
  }

  "StateKey" should "round-trip values through its state codec" in {
    log.decode(log.encode(Vector("a", "b"))).value shouldBe Vector("a", "b")
    count.toString shouldBe "StateKey(count)"
  }
}
