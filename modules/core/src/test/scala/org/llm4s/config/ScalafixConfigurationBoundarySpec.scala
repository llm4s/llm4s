package org.llm4s.config

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import scala.annotation.tailrec

class ScalafixConfigurationBoundarySpec extends AnyWordSpec with Matchers {

  "Scalafix configuration boundary rules" should {
    "include all required DisableSyntax rules" in {
      val scalafixConf = findRepoFile(".scalafix.conf")
      val content      = Files.readString(scalafixConf)

      // Verify basic structure
      content should include("rules = [DisableSyntax]")

      // Verify global excludePackages for infrastructure
      content should include("org.llm4s.config")
      content should include("org.llm4s.samples")
      content should include("org.llm4s.workspace")
      content should include("org.llm4s.core.safety")
      content should include("org.llm4s.agent.orchestration")
    }

    "enforce global configuration boundaries" in {
      val scalafixConf = findRepoFile(".scalafix.conf")
      val content      = Files.readString(scalafixConf)

      // Global rules (apply across all packages with excludePackages exceptions)
      content should include("NoConfigFactory")
      content should include("ConfigFactory")

      content should include("NoSysEnv")
      content should include("sys\\\\.env")

      content should include("NoSystemGetenv")
      content should include("System\\\\.getenv")

      // Exception handling rules
      content should include("NoKeywordTry")
      content should include("NoKeywordCatch")
      content should include("NoKeywordFinally")

      // Code style rules
      content should include("NoInfixOperators")
    }

    "enforce scoped core main source boundaries" in {
      val scalafixConf = findRepoFile(".scalafix.conf")
      val content      = Files.readString(scalafixConf)

      // Scoped core main rules
      content should include("fileFilter = \"glob:modules/core/src/main/scala/**\"")
      content should include("excludePackages = [\"org.llm4s.config\"]")

      // Core-specific rules
      content should include("NoLlm4sConfigInCore")
      content should include("org\\\\.llm4s\\\\.config\\\\.Llm4sConfig")

      content should include("NoConfigFactoryInCore")
      content should include("NoPureConfigDefaultInCore")
      content should include("ConfigSource\\\\.default")

      content should include("NoSysEnvInCore")
      content should include("NoSystemGetenvInCore")
    }

    "be valid HOCON with correct structure" in {
      val scalafixConf = findRepoFile(".scalafix.conf")
      val content      = Files.readString(scalafixConf)

      // Verify that rules are defined
      content should include("rules = [DisableSyntax]")

      // Verify key rule blocks exist
      content should include("DisableSyntax {")
      content should include("DisableSyntax.NoLlm4sConfig")
      content should include("DisableSyntax.NoConfigFactory")
      content should include("DisableSyntax.NoPureConfigDefault")
      content should include("DisableSyntax.NoEnvReads")

      // Verify excludePackages are configured
      content should include("excludePackages = [")
      content should include("\"org.llm4s.config\"")
      content should include("\"org.llm4s.samples\"")
      content should include("\"org.llm4s.workspace\"")
    }
  }

  private def findRepoFile(fileName: String): Path = {
    @tailrec
    def loop(current: Path, remainingLevels: Int): Path = {
      val candidate = current.resolve(fileName)
      if (Files.exists(candidate)) candidate
      else if (remainingLevels == 0) {
        val error = s"Could not locate $fileName from ${current.toAbsolutePath}"
        throw new IllegalStateException(error)
      } else {
        val parent = current.getParent
        if (parent == null) {
          val error = s"Could not locate $fileName from filesystem root"
          throw new IllegalStateException(error)
        }
        loop(parent, remainingLevels - 1)
      }
    }

    loop(Paths.get(".").toAbsolutePath.normalize(), remainingLevels = 6)
  }
}
