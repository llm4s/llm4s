package org.llm4s.samples.rag

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EvalDatasetExampleSpec extends AnyFlatSpec with Matchers {
  "The evaluation dataset example" should "reject invalid input, preserve the snapshot and skip malformed JSONL" in {
    val output = EvalDatasetExample.run()
    output should contain("Rejected input: Missing required field: question")
    output should contain("Selected examples: all=4, hard=2, by ID=2")
    output should contain("After adding a question: snapshot=4, live=5")
    output should contain("JSONL import: imported=5, skipped=1")
  }
}
