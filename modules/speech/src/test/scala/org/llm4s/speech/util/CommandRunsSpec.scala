package org.llm4s.speech.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ File, IOException }
import scala.sys.process.Process

/**
 * CommandRuns, using the JVM that runs the tests as the external program so the spec behaves the
 * same on Linux, macOS and Windows.
 */
class CommandRunsSpec extends AnyFlatSpec with Matchers {

  private def javaPath: String =
    System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"

  private def versionCommand = Process(Seq(javaPath, "--version"))

  private def failingCommand = Process(Seq(javaPath, "--no-such-option"))

  private def missingCommand = Process(Seq("llm4s-no-such-program-xyz"))

  "CommandRuns.stdout" should "return what the program wrote" in {
    val result = CommandRuns.stdout(versionCommand)

    result.isRight shouldBe true
    result.toOption.getOrElse("").trim should not be empty
  }

  it should "report a non-zero exit as a RuntimeException with the exit code" in {
    val result = CommandRuns.stdout(failingCommand)

    result.isLeft shouldBe true
    result.left.toOption.foreach { error =>
      error shouldBe a[RuntimeException]
      error.getMessage shouldBe "Nonzero exit value: 1"
    }
  }

  it should "report a program that cannot be started as an IOException" in {
    val result = CommandRuns.stdout(missingCommand)

    result.isLeft shouldBe true
    result.left.toOption.foreach(_ shouldBe an[IOException])
  }

  "CommandRuns.exitCode" should "return 0 for a program that succeeds" in {
    CommandRuns.exitCode(versionCommand) shouldBe Right(0)
  }

  it should "return the exit code of a program that fails" in {
    CommandRuns.exitCode(failingCommand) shouldBe Right(1)
  }

  it should "report a program that cannot be started as an IOException" in {
    val result = CommandRuns.exitCode(missingCommand)

    result.isLeft shouldBe true
    result.left.toOption.foreach(_ shouldBe an[IOException])
  }
}
