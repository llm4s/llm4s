package org.llm4s.capability

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The lookup behind `model"..."`, run outside the compiler for the case the compile-time specs cannot reach. */
class ModelRefMacrosSpec extends AnyFlatSpec with Matchers with EitherValues {

  "model\"...\"" should "fail clearly, naming the cause, when the bundled model data cannot be read" in {
    val missing = ModelRefMacros.bundledFrom(Left(ConfigurationError("Embedded metadata not found: /x.json")))
    ModelRefMacros.resolve("openai/gpt-4o", missing).left.value shouldBe
      "model\"openai/gpt-4o\" cannot be checked: the bundled model data of llm4s-core cannot be read " +
      "(Embedded metadata not found: /x.json)."
  }

  it should "resolve a model of the registry it is given, by provider/model" in {
    val bundled = ModelRefMacros.bundledFrom(Right(Fixtures.registry))
    bundled.value._2 shouldBe List("acme/omni", "acme/plain")
    val (provider, model, metadata) = ModelRefMacros.resolve("acme/omni", bundled).value
    (provider, model, metadata) shouldBe (("acme", "omni", Fixtures.everything))
    ModelRefMacros.resolve("acme/omnii", bundled).left.value shouldBe
      "unknown model acme/omnii in the bundled model data; did you mean acme/omni?"
  }
}
