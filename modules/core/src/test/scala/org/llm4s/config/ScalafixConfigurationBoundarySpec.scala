package org.llm4s.config

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import scala.annotation.tailrec

class ScalafixConfigurationBoundarySpec extends AnyWordSpec with Matchers {

  "Scalafix configuration boundary rules" should {
    "enforce config access boundaries in core main sources" in {
      val scalafixConf = findRepoFile(".scalafix.conf")
      val content      = Files.readString(scalafixConf)

      content should include("rules = [DisableSyntax]")
      content should include("fileFilter = \"glob:modules/core/src/main/scala/**\"")
      content should include("excludePackages = [\"org.llm4s.config\"]")

      content should include("NoLlm4sConfigInCore")
      content should include("org\\\\.llm4s\\\\.config\\\\.Llm4sConfig")

      content should include("NoConfigFactoryInCore")
      content should include("ConfigFactory")

      content should include("NoPureConfigDefaultInCore")
      content should include("ConfigSource\\\\.default")

      content should include("NoSysEnvInCore")
      content should include("sys\\\\.env")

      content should include("NoSystemGetenvInCore")
      content should include("System\\\\.getenv")
    }
  }

  private def findRepoFile(fileName: String): Path = {
    @tailrec
    def loop(current: Path, remainingLevels: Int): Path = {
      val candidate = current.resolve(fileName)
      if (Files.exists(candidate)) candidate
      else if (remainingLevels == 0) {
        throw new IllegalStateException(s"Could not locate $fileName from ${current.toAbsolutePath}")
      } else {
        val parent = current.getParent
        if (parent == null) {
          throw new IllegalStateException(s"Could not locate $fileName from filesystem root")
        }
        loop(parent, remainingLevels - 1)
      }
    }

    loop(Paths.get(".").toAbsolutePath.normalize(), remainingLevels = 6)
  }
}
