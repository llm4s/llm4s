package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.agent.guardrails.patterns.SecretPatterns
import org.llm4s.agent.guardrails.patterns.SecretPatterns.SecretType
import org.scalatest.EitherValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SecretLeakGuardrailSpec extends AnyFunSpec with Matchers with EitherValues {

  // ─────────────────────────────────────────────────────────────────────────
  // Sample credentials used throughout the suite
  // ─────────────────────────────────────────────────────────────────────────
  private val openAiKey         = "sk-abcdefghijklmnopqrstuvwxyz123456"
  private val openAiProjKey     = "sk-proj-abcdefghijklmnopqrstuvwxyz123456"
  private val anthropicKey      = "sk-ant-abcdefghijklmnopqrstuvwxyz123456"
  private val googleApiKey      = "AIzaSyAbcdefghijklmnopqrstuvwxyz01234567890"
  private val voyageKey         = "pa-abcdefghijklmnopqrstuvwxyz123456"
  private val langfusePublicKey = "pk-lf-abcdefghijklmnopqrstuv"
  private val langfuseSecretKey = "sk-lf-abcdefghijklmnopqrstuv"
  private val awsAccessKey      = "AKIAIOSFODNN7EXAMPLE"
  private val jwtToken =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
  private val pemHeader = "-----BEGIN RSA PRIVATE KEY-----"
  private val safeText  = "The quick brown fox jumps over the lazy dog."

  // ─────────────────────────────────────────────────────────────────────────
  // SecretPatterns – unit tests for the pattern library itself
  // ─────────────────────────────────────────────────────────────────────────
  describe("SecretPatterns") {

    describe("SecretType patterns") {
      it("detects OpenAI keys (sk-...)") {
        SecretType.OpenAIKey.findAll(s"key=$openAiKey end") should not be empty
      }

      it("detects OpenAI project keys (sk-proj-...)") {
        SecretType.OpenAIKey.findAll(s"key=$openAiProjKey end") should not be empty
      }

      it("detects Anthropic keys (sk-ant-...)") {
        SecretType.AnthropicKey.findAll(s"key=$anthropicKey end") should not be empty
      }

      it("detects Google API keys (AIza...)") {
        SecretType.GoogleApiKey.findAll(s"key=$googleApiKey end") should not be empty
      }

      it("detects Voyage API keys (pa-...)") {
        SecretType.VoyageKey.findAll(s"key=$voyageKey end") should not be empty
      }

      it("detects Langfuse public keys (pk-lf-...)") {
        SecretType.LangfuseKey.findAll(s"key=$langfusePublicKey end") should not be empty
      }

      it("detects Langfuse secret keys (sk-lf-...)") {
        SecretType.LangfuseKey.findAll(s"key=$langfuseSecretKey end") should not be empty
      }

      it("detects AWS Access Key IDs (AKIA...)") {
        SecretType.AwsAccessKey.findAll(s" $awsAccessKey ") should not be empty
      }

      it("detects JWT tokens") {
        SecretType.JwtToken.findAll(s"token=$jwtToken end") should not be empty
      }

      it("detects PEM private key block headers") {
        SecretType.PrivateKeyPem.findAll(s"$pemHeader\nsome-key-data") should not be empty
      }

      it("does NOT match safe text with any default pattern") {
        SecretType.default.flatMap(_.findAll(safeText)) shouldBe empty
      }

      it("SecretMatch carries correct secretType, value, startIndex and endIndex") {
        val text    = s"prefix-$openAiKey-suffix"
        val matches = SecretType.OpenAIKey.findAll(text)
        matches should have size 1
        val m = matches.head
        m.secretType shouldBe SecretType.OpenAIKey
        m.value shouldBe openAiKey
        m.startIndex shouldBe 7
        m.endIndex shouldBe (7 + openAiKey.length)
        m.redactedValue shouldBe "[REDACTED_OPENAI_KEY]"
      }
    }

    describe("SecretType sets") {
      it("default set contains 7 types and excludes PrivateKeyPem") {
        SecretType.default should have size 7
        SecretType.default should not contain SecretType.PrivateKeyPem
      }

      it("all set contains 8 types including PrivateKeyPem") {
        SecretType.all should have size 8
        SecretType.all should contain(SecretType.PrivateKeyPem)
      }
    }

    describe("detect()") {
      it("returns empty for safe text") {
        SecretPatterns.detect(safeText) shouldBe empty
      }

      it("detects a single secret") {
        SecretPatterns.detect(s"Here is my key: $openAiKey") should have size 1
      }

      it("detects multiple secrets in one string") {
        val text    = s"openai=$openAiKey anthropic=$anthropicKey"
        val matches = SecretPatterns.detect(text)
        (matches.map(_.secretType) should contain).allOf(SecretType.OpenAIKey, SecretType.AnthropicKey)
      }

      it("scans only the requested types when types is restricted") {
        val text = s"openai=$openAiKey aws= $awsAccessKey"
        SecretPatterns.detect(text, Seq(SecretType.OpenAIKey)) should have size 1
      }
    }

    describe("containsSecret()") {
      it("returns false for safe text") {
        SecretPatterns.containsSecret(safeText) shouldBe false
      }

      it("returns true when a secret is present") {
        SecretPatterns.containsSecret(s"key=$openAiKey") shouldBe true
      }
    }

    describe("redactAll()") {
      it("leaves safe text unchanged") {
        SecretPatterns.redactAll(safeText) shouldBe safeText
      }

      it("replaces OpenAI key with typed placeholder") {
        val result = SecretPatterns.redactAll(s"key=$openAiKey done")
        result should include("[REDACTED_OPENAI_KEY]")
        (result should not).include(openAiKey)
      }

      it("replaces Anthropic key with typed placeholder") {
        val result = SecretPatterns.redactAll(s"key=$anthropicKey done")
        result should include("[REDACTED_ANTHROPIC_KEY]")
        (result should not).include(anthropicKey)
      }

      it("replaces Google API key with typed placeholder") {
        val result = SecretPatterns.redactAll(s"key=$googleApiKey done")
        result should include("[REDACTED_GOOGLE_KEY]")
        (result should not).include(googleApiKey)
      }

      it("replaces Voyage key with typed placeholder") {
        val result = SecretPatterns.redactAll(s"key=$voyageKey done")
        result should include("[REDACTED_VOYAGE_KEY]")
        (result should not).include(voyageKey)
      }

      it("replaces Langfuse key with typed placeholder") {
        val result = SecretPatterns.redactAll(s"key=$langfusePublicKey done")
        result should include("[REDACTED_LANGFUSE_KEY]")
        (result should not).include(langfusePublicKey)
      }

      it("replaces AWS Access Key ID with typed placeholder") {
        val result = SecretPatterns.redactAll(s" $awsAccessKey ")
        result should include("[REDACTED_AWS_KEY]")
        (result should not).include(awsAccessKey)
      }

      it("replaces JWT token with typed placeholder") {
        val result = SecretPatterns.redactAll(s"Bearer $jwtToken")
        result should include("[REDACTED_JWT]")
        (result should not).include(jwtToken)
      }

      it("redacts multiple secrets preserving surrounding text") {
        val text   = s"a=$openAiKey b=$anthropicKey c=safe"
        val result = SecretPatterns.redactAll(text)
        result should include("[REDACTED_OPENAI_KEY]")
        result should include("[REDACTED_ANTHROPIC_KEY]")
        result should include("c=safe")
        (result should not).include(openAiKey)
        (result should not).include(anthropicKey)
      }

      it("redacts PEM key header when using SecretType.all") {
        val text   = s"$pemHeader\nkey-data"
        val result = SecretPatterns.redactAll(text, SecretType.all)
        result should include("[REDACTED_PRIVATE_KEY]")
        (result should not).include(pemHeader)
      }

      it("redacts a PEM private key whole: header, body and footer (#2824)") {
        val body =
          "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\nVJTUt9Us8cKjMzEfYyjiWA4R4/M2bS1GB4t7NXp98C3SC6dV\n"
        Seq("", "RSA ", "EC ", "DSA ", "OPENSSH ", "ENCRYPTED ").foreach { kind =>
          val pem    = s"-----BEGIN ${kind}PRIVATE KEY-----\n$body-----END ${kind}PRIVATE KEY-----"
          val result = SecretPatterns.redactAll(s"key:\n$pem\ndone", SecretType.all)
          withClue(s"$kind: ")(result shouldBe "key:\n[REDACTED_PRIVATE_KEY]\ndone")
          SecretType.PrivateKeyPem.findAll(s"key:\n$pem\ndone").map(_.value) shouldBe Seq(pem)
        }
      }

      it("redacts a PEM private key with RFC 1421 headers, and one written with escaped newlines (#2824)") {
        val encrypted = "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,0A1B2C\n\n" +
          "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END RSA PRIVATE KEY-----"
        SecretPatterns.redactAll(encrypted, SecretType.all) shouldBe "[REDACTED_PRIVATE_KEY]"
        val json =
          """{"private_key": "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----\n"}"""
        SecretPatterns.redactAll(json, SecretType.all) shouldBe """{"private_key": "[REDACTED_PRIVATE_KEY]\n"}"""
      }

      it("redacts each of two PEM private keys and keeps the text between them (#2824)") {
        val pem    = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----"
        val result = SecretPatterns.redactAll(s"$pem\nbetween\n$pem", SecretType.all)
        result shouldBe "[REDACTED_PRIVATE_KEY]\nbetween\n[REDACTED_PRIVATE_KEY]"
      }

      it("redacts the body of a PEM private key cut off before its footer (#2824)") {
        val cut = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nVJTUt9Us8cKj"
        SecretPatterns.redactAll(cut, SecretType.all) shouldBe "[REDACTED_PRIVATE_KEY]"
      }

      it("redacts an OpenAI project, service-account or admin key whole, with its - and _ (#2824)") {
        Seq("proj", "svcacct", "admin").foreach { kind =>
          val key = s"sk-$kind-Ab3_dE-fGh1jKlMn0pQrStUvWxYz_T3BlbkFJ-aBcDeFgHiJkLmNoP_qRsT"
          SecretPatterns.redactAll(s"key=$key done") shouldBe "key=[REDACTED_OPENAI_KEY] done"
          SecretType.OpenAIKey.findAll(s"key=$key done").map(_.value) shouldBe Seq(key)
        }
      }

      it("still reads an Anthropic key as an Anthropic key only") {
        SecretPatterns.detect("key=sk-ant-api03-Ab3_dE-fGh1jKlMn0pQrStUvWxYz").map(_.secretType) shouldBe Seq(
          SecretType.AnthropicKey
        )
      }

      it("redacts a JWT that follows other token characters, and only the JWT (#2824)") {
        SecretPatterns.redactAll(s"sess_$jwtToken done") shouldBe "sess_[REDACTED_JWT] done"
        val found = SecretType.JwtToken.findAll(s"sess_$jwtToken done")
        found.map(_.value) shouldBe Seq(jwtToken)
        found.map(_.startIndex) shouldBe Seq(5)
      }
    }

    describe("a SecretType of the application's own") {
      val acme = new SecretType("Acme token", """acme_[A-Za-z0-9]{12}""".r, "[REDACTED_ACME_TOKEN]")
      val text = s"acme=acme_abcdef123456 openai=$openAiKey"

      it("is detected and redacted beside the built-in types (#2824)") {
        val types = SecretType.default :+ acme
        SecretPatterns.detect(text, types).map(_.secretType) shouldBe Seq(SecretType.OpenAIKey, acme)
        SecretPatterns.redactAll(text, types) shouldBe "acme=[REDACTED_ACME_TOKEN] openai=[REDACTED_OPENAI_KEY]"
        SecretLeakGuardrail(types).validate(text).isLeft shouldBe true
        SecretLeakGuardrail(types, GuardrailAction.Fix).validate("acme_abcdef123456") shouldBe Right(
          "[REDACTED_ACME_TOKEN]"
        )
      }

      it("redacts group 1 of a pattern that has one, and keeps the rest of the match (#2824)") {
        val keyed = new SecretType("Keyed", """password=(\w+)""".r, "[REDACTED_PASSWORD]")
        keyed.findAll("password=hunter2 x").map(m => (m.value, m.startIndex, m.endIndex)) shouldBe Seq(
          ("hunter2", 9, 16)
        )
        keyed.redact("password=hunter2 x") shouldBe "password=[REDACTED_PASSWORD] x"
      }

      it("writes a placeholder holding $ or a backslash literally") {
        new SecretType("Odd", "secret".r, "$0\\1").redact("a secret b") shouldBe "a $0\\1 b"
      }
    }

    describe("summarize()") {
      it("returns empty map for safe text") {
        SecretPatterns.summarize(safeText) shouldBe empty
      }

      it("counts one occurrence of an OpenAI key") {
        val map = SecretPatterns.summarize(s"k=$openAiKey")
        map("OpenAI API Key") shouldBe 1
      }

      it("counts multiple occurrences of the same type") {
        val text = s"a=$openAiKey b=$openAiKey"
        val map  = SecretPatterns.summarize(text)
        map("OpenAI API Key") shouldBe 2
      }

      it("counts different types independently") {
        val text = s"openai=$openAiKey anthropic=$anthropicKey"
        val map  = SecretPatterns.summarize(text)
        map("OpenAI API Key") shouldBe 1
        map("Anthropic API Key") shouldBe 1
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // SecretLeakGuardrail – validate() in each action mode
  // ─────────────────────────────────────────────────────────────────────────
  describe("SecretLeakGuardrail") {

    describe("default constructor / apply()") {
      it("uses Block action by default") {
        SecretLeakGuardrail().onFail shouldBe GuardrailAction.Block
      }

      it("uses the default secret type set") {
        SecretLeakGuardrail().secretTypes shouldBe SecretType.default
      }

      it("has the expected name") {
        SecretLeakGuardrail().name shouldBe "SecretLeakGuardrail"
      }

      it("has a non-empty description listing the types") {
        val desc = SecretLeakGuardrail().description
        desc shouldBe defined
        desc.get should include("OpenAI API Key")
      }
    }

    describe("Block mode (default)") {
      val guardrail = SecretLeakGuardrail()

      it("passes safe text through as Right") {
        guardrail.validate(safeText) shouldBe Right(safeText)
      }

      it("returns Left when an OpenAI key is present") {
        val result = guardrail.validate(s"My key is $openAiKey")
        result.isLeft shouldBe true
      }

      it("error message lists the detected credential type") {
        val err = guardrail.validate(s"key=$openAiKey").left.value
        err.message should include("OpenAI API Key")
      }

      it("returns Left for Anthropic keys") {
        guardrail.validate(anthropicKey).isLeft shouldBe true
      }

      it("returns Left for Google API keys") {
        guardrail.validate(googleApiKey).isLeft shouldBe true
      }

      it("returns Left for Voyage keys") {
        guardrail.validate(voyageKey).isLeft shouldBe true
      }

      it("returns Left for Langfuse public keys") {
        guardrail.validate(langfusePublicKey).isLeft shouldBe true
      }

      it("returns Left for Langfuse secret keys") {
        guardrail.validate(langfuseSecretKey).isLeft shouldBe true
      }

      it("returns Left for AWS Access Key IDs") {
        guardrail.validate(s" $awsAccessKey ").isLeft shouldBe true
      }

      it("returns Left for JWT tokens") {
        guardrail.validate(jwtToken).isLeft shouldBe true
      }

      it("returns Left when multiple secrets are present and names all are reported") {
        val text = s"openai=$openAiKey anthropic=$anthropicKey"
        val err  = guardrail.validate(text).left.value
        err.message should include("OpenAI API Key")
        err.message should include("Anthropic API Key")
      }

      it("passes an empty string as Right") {
        guardrail.validate("") shouldBe Right("")
      }
    }

    describe("Fix (masking) mode") {
      val guardrail = SecretLeakGuardrail.masking

      it("has Fix action") {
        guardrail.onFail shouldBe GuardrailAction.Fix
      }

      it("passes safe text through unchanged") {
        guardrail.validate(safeText) shouldBe Right(safeText)
      }

      it("returns Right with OpenAI key replaced by typed placeholder") {
        val result = guardrail.validate(s"key=$openAiKey done").value
        result should include("[REDACTED_OPENAI_KEY]")
        (result should not).include(openAiKey)
      }

      it("returns Right with Anthropic key replaced by typed placeholder") {
        val result = guardrail.validate(anthropicKey).value
        result should include("[REDACTED_ANTHROPIC_KEY]")
        (result should not).include(anthropicKey)
      }

      it("returns Right with Google key replaced by typed placeholder") {
        val result = guardrail.validate(googleApiKey).value
        result should include("[REDACTED_GOOGLE_KEY]")
        (result should not).include(googleApiKey)
      }

      it("returns Right with Voyage key replaced by typed placeholder") {
        val result = guardrail.validate(voyageKey).value
        result should include("[REDACTED_VOYAGE_KEY]")
        (result should not).include(voyageKey)
      }

      it("returns Right with Langfuse key replaced by typed placeholder") {
        val result = guardrail.validate(langfusePublicKey).value
        result should include("[REDACTED_LANGFUSE_KEY]")
        (result should not).include(langfusePublicKey)
      }

      it("returns Right with AWS key replaced by typed placeholder") {
        val result = guardrail.validate(s" $awsAccessKey ").value
        result should include("[REDACTED_AWS_KEY]")
        (result should not).include(awsAccessKey)
      }

      it("returns Right with JWT replaced by typed placeholder") {
        val result = guardrail.validate(jwtToken).value
        result should include("[REDACTED_JWT]")
        (result should not).include(jwtToken)
      }

      it("redacts multiple secrets in one string preserving safe text") {
        val text   = s"openai=$openAiKey anthropic=$anthropicKey safe=hello"
        val result = guardrail.validate(text).value
        result should include("[REDACTED_OPENAI_KEY]")
        result should include("[REDACTED_ANTHROPIC_KEY]")
        result should include("safe=hello")
      }
    }

    describe("Warn (monitoring) mode") {
      val guardrail = SecretLeakGuardrail.monitoring

      it("has Warn action") {
        guardrail.onFail shouldBe GuardrailAction.Warn
      }

      it("returns Right even when a secret is present, keeping original text") {
        val text   = s"key=$openAiKey"
        val result = guardrail.validate(text).value
        result shouldBe text
      }

      it("returns Right for safe text unchanged") {
        guardrail.validate(safeText) shouldBe Right(safeText)
      }
    }

    describe("transform()") {
      val guardrail = SecretLeakGuardrail.masking

      it("replaces secrets with their typed placeholders") {
        val result = guardrail.transform(s"k=$openAiKey")
        result should include("[REDACTED_OPENAI_KEY]")
        (result should not).include(openAiKey)
      }

      it("leaves safe text unchanged") {
        guardrail.transform(safeText) shouldBe safeText
      }
    }

    describe("containsSecret()") {
      val guardrail = SecretLeakGuardrail()

      it("returns false for safe text") {
        guardrail.containsSecret(safeText) shouldBe false
      }

      it("returns true when a secret is present") {
        guardrail.containsSecret(s"k=$openAiKey") shouldBe true
      }
    }

    describe("summarize()") {
      val guardrail = SecretLeakGuardrail()

      it("returns empty map for safe text") {
        guardrail.summarize(safeText) shouldBe empty
      }

      it("returns counts per credential type") {
        val map = guardrail.summarize(s"openai=$openAiKey anthropic=$anthropicKey")
        map("OpenAI API Key") shouldBe 1
        map("Anthropic API Key") shouldBe 1
      }
    }

    describe("apply() factory overloads") {
      it("apply(GuardrailAction) sets the action") {
        SecretLeakGuardrail(GuardrailAction.Fix).onFail shouldBe GuardrailAction.Fix
      }

      it("apply(Seq[SecretType]) sets the types") {
        val types = Seq(SecretType.OpenAIKey)
        SecretLeakGuardrail(types).secretTypes shouldBe types
      }

      it("apply(Seq[SecretType], GuardrailAction) sets both") {
        val types     = Seq(SecretType.AnthropicKey)
        val guardrail = SecretLeakGuardrail(types, GuardrailAction.Warn)
        guardrail.secretTypes shouldBe types
        guardrail.onFail shouldBe GuardrailAction.Warn
      }
    }

    describe("SecretLeakGuardrail.all preset") {
      val guardrail = SecretLeakGuardrail.all

      it("uses the full secret type set") {
        guardrail.secretTypes shouldBe SecretType.all
      }

      it("detects PEM private key headers") {
        guardrail.validate(s"$pemHeader\nkey-data").isLeft shouldBe true
      }

      it("blocks on regular key too") {
        guardrail.validate(openAiKey).isLeft shouldBe true
      }
    }

    describe("restricted to a single secret type") {
      it("ignores secrets from types not in the configured set") {
        val openAiOnly = SecretLeakGuardrail(Seq(SecretType.OpenAIKey))
        // Anthropic key should NOT be detected
        openAiOnly.validate(anthropicKey) shouldBe Right(anthropicKey)
        // OpenAI key SHOULD be detected
        openAiOnly.validate(openAiKey).isLeft shouldBe true
      }
    }

    describe("edge cases") {
      val guardrail = SecretLeakGuardrail()

      it("empty string returns Right(empty) in Block mode") {
        guardrail.validate("") shouldBe Right("")
      }

      it("whitespace-only string returns Right in Block mode") {
        guardrail.validate("   ") shouldBe Right("   ")
      }

      it("a key embedded inside a longer word is still detected") {
        val text = s"config_sk-abcdefghijklmnopqrstuvwxyz123456_suffix"
        SecretPatterns.containsSecret(text) shouldBe true
      }

      it("Fix mode on empty string returns Right(empty)") {
        SecretLeakGuardrail.masking.validate("") shouldBe Right("")
      }
    }
  }
}
