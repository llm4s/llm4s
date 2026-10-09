package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The shared `@Cloud` smoke contract (issue #1212): every capability in [[Capability]], run against one provider.
 *
 * A provider's smoke spec mixes this in, says how to build its client and which capabilities do not apply, and
 * calls [[registerCapabilityContract]] after its own tests. Each capability then becomes a test:
 *
 *  - applicable and the key is set: the check runs; the test fails if it does not hold;
 *  - applicable and the key is missing: the test is skipped (cancelled), or failed under `LLM4S_IT_STRICT=true`,
 *    exactly like the rest of the tier (see [[Tier]]);
 *  - not applicable: the test is cancelled with the reason, and the matrix shows `n/a` with it - a capability is
 *    never skipped silently.
 *
 * The matrix of outcomes is printed when the spec ends. The checks themselves live in [[SmokeChecks]], which is
 * proven offline by `SmokeContractOfflineSpec`.
 */
trait ProviderSmokeContract extends BeforeAndAfterAll { self: AnyFlatSpec & Matchers =>

  /** The name the matrix shows for this provider. */
  protected def providerLabel: String

  /** The environment variable that holds the key, for the skip message only. */
  protected def apiKeyEnvVar: String

  /** The key, when it is set; the contract skips every capability when it is not. */
  protected def contractKey: Option[String]

  /** A client for the provider's default (cheap) model, using `key`. */
  protected def contractClient(key: String): Result[LLMClient]

  /** Where the outcomes are recorded; the process-wide matrix, which is the one that is printed. */
  protected def matrix: SmokeMatrix = SmokeMatrix.shared

  /** Whether to print the matrix; only the offline self-test, which fails on purpose, turns it off. */
  protected def announceMatrix: Boolean = true

  /** The capabilities that do not apply to this provider, each with the reason the matrix shows. */
  protected def notApplicable: Map[Capability, String] = Map.empty

  /**
   * How to build the model and options for the `Reasoning` check, given the key; `None` (the default) makes the
   * capability not applicable.
   */
  protected def reasoningSetup: Option[String => Result[ReasoningSetup]] = None

  private val NoReasoningModel =
    "this spec has no reasoning-capable model configured (the default model is a cheap non-reasoning one)"

  private def applicability(capability: Capability): Applicability =
    notApplicable.get(capability) match {
      case Some(reason) => Applicability.NotApplicable(reason)
      case None if capability == Capability.Reasoning && reasoningSetup.isEmpty =>
        Applicability.NotApplicable(NoReasoningModel)
      case None => Applicability.Supported
    }

  private def execute(capability: Capability, key: String): Outcome =
    capability match {
      case Capability.Reasoning =>
        reasoningSetup.map(build => build(key)) match {
          case None => Outcome.Failed(s"[${capability.label}] the spec declares it applicable but has no setup")
          case Some(Left(error)) =>
            Outcome.Failed(s"[${capability.label}] could not build the reasoning client: ${error.message}")
          case Some(Right(setup)) =>
            val outcome = SmokeChecks.reasoning(setup)
            setup.client.close()
            outcome
        }
      case _ =>
        contractClient(key) match {
          case Left(error) =>
            Outcome.Failed(s"[${capability.label}] could not build the client: ${error.message}")
          case Right(client) =>
            val outcome = SmokeChecks.run(capability, client)
            client.close()
            outcome
        }
    }

  private def runCapability(capability: Capability): Unit =
    applicability(capability) match {
      case Applicability.NotApplicable(reason) =>
        matrix.record(providerLabel, capability, Outcome.NotApplicable(reason))
        cancel(s"${capability.label} is not applicable to $providerLabel: $reason")
      case Applicability.Supported =>
        if (contractKey.isEmpty)
          matrix.record(providerLabel, capability, Outcome.Skipped(s"$apiKeyEnvVar not set"))
        Tier.require(contractKey.isDefined, s"$apiKeyEnvVar not set")
        val outcome = execute(capability, contractKey.getOrElse(""))
        matrix.record(providerLabel, capability, outcome)
        outcome match {
          case Outcome.Failed(message) => fail(message)
          case _                       => ()
        }
    }

  /** Registers one test per capability; call it once, after the spec's own tests. */
  protected def registerCapabilityContract(): Unit = {
    if (announceMatrix) SmokeMatrix.printAtExit()
    behavior.of(s"$providerLabel capability contract")
    Capability.values.foreach { capability =>
      it should s"hold the ${capability.label} capability" in runCapability(capability)
    }
  }

  override protected def afterAll(): Unit = {
    if (announceMatrix) println(s"\nCapability matrix, $providerLabel:\n${matrix.renderProvider(providerLabel)}")
    super.afterAll()
  }
}
