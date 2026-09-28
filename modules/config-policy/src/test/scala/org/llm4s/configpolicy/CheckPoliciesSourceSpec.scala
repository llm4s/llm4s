package org.llm4s.configpolicy

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

/**
 * `CheckPolicies --config <file>` must see what the application would: the file layered over
 * every module's `reference.conf`, which is where provider modules bind their vendor's variable
 * to `llm4s.credentials.<id>.apiKey`. Read alone, the file hides those bindings and a section
 * relying on `OPENAI_API_KEY` looks as if it had no key.
 */
class CheckPoliciesSourceSpec extends AnyWordSpec with Matchers with EitherValues {

  private def withConfigFile[A](hocon: String)(f: String => A): A = {
    val file = Files.createTempFile("policy-check", ".conf")
    Files.writeString(file, hocon)
    val result = f(file.toString)
    Files.delete(file)
    result
  }

  "CheckPolicies.sourceFor with --config" should {

    "include the modules' reference.conf, credentials bindings among them" in {
      withConfigFile("""llm4s.providers.provider = "main"""") { path =>
        // The block exists whether or not OPENAI_API_KEY is set: llm4s-openai declares it.
        CheckPolicies.sourceFor(Some(path)).at("llm4s.credentials.openai").value().isRight shouldBe true
        // A reference default the file does not set.
        CheckPolicies.sourceFor(Some(path)).at("llm4s.tracing.mode").load[String].value shouldBe "console"
      }
    }

    "let the file override a reference default" in {
      withConfigFile("""llm4s.tracing.mode = "noop"""") { path =>
        CheckPolicies.sourceFor(Some(path)).at("llm4s.tracing.mode").load[String].value shouldBe "noop"
      }
    }
  }
}
