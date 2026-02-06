package org.llm4s.testing

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ScrubberSpec extends AnyFunSpec with Matchers {

  describe("Scrubber.default") {
    it("should scrub OpenAI API keys") {
      val text     = "key: sk-abc123def456ghi789jkl012mno345pqr678stu901vwx234"
      val scrubbed = Scrubber.default.scrub(text)
      (scrubbed should not).include("sk-abc123")
      scrubbed should include("[OPENAI_API_KEY]")
    }

    it("should scrub Anthropic API keys") {
      val text     = "api_key=sk-ant-api03-abcdef123456789-abcdef123456789"
      val scrubbed = Scrubber.default.scrub(text)
      (scrubbed should not).include("sk-ant-")
      scrubbed should include("[ANTHROPIC_API_KEY]")
    }

    it("should scrub Bearer tokens") {
      val text =
        "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
      val scrubbed = Scrubber.default.scrub(text)
      (scrubbed should not).include("eyJh")
      scrubbed should include("Bearer [REDACTED]")
    }

    it("should scrub api_key query parameters") {
      val text     = "https://api.example.com?api_key=secret123&other=value"
      val scrubbed = Scrubber.default.scrub(text)
      (scrubbed should not).include("secret123")
      scrubbed should include("api_key=[REDACTED]")
    }
  }

  describe("Scrubber.none") {
    it("should not modify content") {
      val text     = "sk-abc123def456ghi789jkl012mno345pqr678stu901vwx234"
      val scrubbed = Scrubber.none.scrub(text)
      scrubbed shouldBe text
    }
  }

  describe("Scrubber.custom") {
    it("should apply custom patterns") {
      val scrubber = Scrubber.custom("secret-[0-9]+".r -> "[SECRET]")
      val text     = "The code is secret-12345 for access"
      val scrubbed = scrubber.scrub(text)
      scrubbed shouldBe "The code is [SECRET] for access"
    }
  }

  describe("addPattern") {
    it("should add new patterns to existing scrubber") {
      val scrubber = Scrubber.none.addPattern("password=\\S+".r, "password=[HIDDEN]")
      val text     = "login with password=mySecret123"
      val scrubbed = scrubber.scrub(text)
      scrubbed shouldBe "login with password=[HIDDEN]"
    }
  }
}
