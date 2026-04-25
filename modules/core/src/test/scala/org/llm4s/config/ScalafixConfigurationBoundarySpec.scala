// scalafix:off DisableSyntax.NoConfigFactory
package org.llm4s.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path, Paths }
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

/**
 * Validates the structure of `.scalafix.conf` so the project cannot silently
 * lose enforcement.
 *
 * Why a structural test instead of substring matching: scalafix's `DisableSyntax`
 * rule only reads its `regex` list from the canonical top-level path
 * `DisableSyntax.regex`. Custom-named subblocks like
 * `DisableSyntax.MyRules { regex = [...] }` are silently ignored — the file
 * still contains every expected substring, but no rules fire. A substring test
 * passes; CI passes; enforcement is off. This test parses the HOCON and checks
 * the path scalafix actually consults, so a future restructure that buries
 * rules under custom subblocks fails this test loudly.
 */
class ScalafixConfigurationBoundarySpec extends AnyWordSpec with Matchers {

  private val expectedRuleIds: Set[String] = Set(
    "NoConfigFactory",
    "NoSysEnv",
    "NoSystemGetenv",
    "NoPureConfigDefault",
    "NoKeywordTry",
    "NoKeywordCatch",
    "NoKeywordFinally"
  )

  ".scalafix.conf" should {
    "enable the DisableSyntax rule" in {
      val rules = scalafixConf.getStringList("rules").asScala.toSet
      rules should contain("DisableSyntax")
    }

    "place regex rules at the canonical DisableSyntax.regex path scalafix actually reads" in {
      // If this fails, scalafix is silently ignoring the rules - see class doc.
      scalafixConf.hasPath("DisableSyntax.regex") shouldBe true
      scalafixConf.getList("DisableSyntax.regex").size should be > 0
    }

    "define every required configuration-boundary rule id" in {
      val ids = scalafixConf.getConfigList("DisableSyntax.regex").asScala.map(_.getString("id")).toSet
      ids should contain allElementsOf expectedRuleIds
    }

    "give every rule a non-empty pattern, message, and a compilable regex" in {
      scalafixConf.getConfigList("DisableSyntax.regex").asScala.foreach { rule =>
        val id = rule.getString("id")
        withClue(s"rule $id: ") {
          rule.getString("pattern") should not be empty
          rule.getString("message") should not be empty
          noException should be thrownBy new Regex(rule.getString("pattern"))
        }
      }
    }
  }

  private def scalafixConf = ConfigFactory.parseFile(findRepoFile(".scalafix.conf").toFile)

  private def findRepoFile(fileName: String): Path = {
    @tailrec
    def loop(current: Path, remainingLevels: Int): Path = {
      val candidate = current.resolve(fileName)
      if (Files.exists(candidate)) candidate
      else if (remainingLevels == 0)
        throw new IllegalStateException(s"Could not locate $fileName from ${current.toAbsolutePath}")
      else {
        val parent = current.getParent
        if (parent == null) throw new IllegalStateException(s"Could not locate $fileName from filesystem root")
        loop(parent, remainingLevels - 1)
      }
    }
    loop(Paths.get(".").toAbsolutePath.normalize(), remainingLevels = 6)
  }
}
