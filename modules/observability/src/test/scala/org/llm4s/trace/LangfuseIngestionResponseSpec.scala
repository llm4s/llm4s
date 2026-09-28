package org.llm4s.trace

import org.llm4s.trace.LangfuseIngestionResponse.Rejection
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LangfuseIngestionResponseSpec extends AnyFlatSpec with Matchers with EitherValues {

  "LangfuseIngestionResponse.rejections" should "list the events a 207 body reports as errors" in {
    val body =
      """{"successes":[{"id":"ok","status":201}],
        | "errors":[{"id":"bad-1","status":400,"message":"Invalid request data"},
        |           {"id":"bad-2","status":500,"error":{"code":"x"}}]}""".stripMargin

    LangfuseIngestionResponse.rejections(body).value shouldBe Seq(
      Rejection("bad-1", Some(400), "Invalid request data"),
      Rejection("bad-2", Some(500), """{"code":"x"}""")
    )
  }

  it should "list none when every event was accepted, or the body carries no per-event errors" in {
    LangfuseIngestionResponse
      .rejections("""{"successes":[{"id":"ok","status":201}],"errors":[]}""")
      .value shouldBe empty
    LangfuseIngestionResponse.rejections("""{"successes":[{"id":"ok","status":201}]}""").value shouldBe empty
    LangfuseIngestionResponse.rejections("""{"successes":1,"errors":0}""").value shouldBe empty
  }

  it should "tolerate an error entry without an id, status or message" in {
    LangfuseIngestionResponse.rejections("""{"errors":[{}]}""").value shouldBe Seq(
      Rejection("<no id>", None, "no message")
    )
  }

  it should "report a body it cannot read" in {
    LangfuseIngestionResponse.rejections("not json").left.value should include("unreadable 207 body")
    LangfuseIngestionResponse.rejections("[1,2]").left.value should include("not a JSON object")
  }

  "LangfuseIngestionResponse.summary" should "say how many of the batch were rejected, and which" in {
    LangfuseIngestionResponse.summary(Seq(Rejection("e2", Some(400), "Invalid")), 3) shouldBe
      "Langfuse rejected 1 of 3 events: e2 (400: Invalid)"
  }
}
