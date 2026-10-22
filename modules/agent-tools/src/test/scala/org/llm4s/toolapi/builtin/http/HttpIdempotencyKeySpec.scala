package org.llm4s.toolapi.builtin.http

import org.llm4s.toolapi.ToolCallInfo
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `HTTPTool` sends an agent call's idempotency key as `Idempotency-Key` on a write (#1740). */
class HttpIdempotencyKeySpec extends AnyFlatSpec with Matchers {

  private val call = Some(ToolCallInfo("call_1", "key-1"))

  "HTTPTool" should "add Idempotency-Key to a POST, PUT, PATCH or DELETE run as an agent's call" in {
    Seq("POST", "put", "Patch", "DELETE").foreach { method =>
      HTTPTool.withIdempotencyKey(None, method, call) shouldBe Some(Map("Idempotency-Key" -> "key-1"))
    }
    HTTPTool.withIdempotencyKey(Some(Map("Accept" -> "text/plain")), "POST", call) shouldBe
      Some(Map("Accept" -> "text/plain", "Idempotency-Key" -> "key-1"))
  }

  it should "leave a read alone, and any request outside an agent" in {
    Seq("GET", "HEAD", "OPTIONS").foreach(m => HTTPTool.withIdempotencyKey(None, m, call) shouldBe None)
    HTTPTool.withIdempotencyKey(None, "POST", None) shouldBe None
  }

  it should "keep an Idempotency-Key the call's own headers set, in any case" in {
    val own = Some(Map("idempotency-KEY" -> "mine"))
    HTTPTool.withIdempotencyKey(own, "POST", call) shouldBe own
  }
}
