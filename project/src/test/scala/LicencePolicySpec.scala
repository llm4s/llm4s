import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import LicencePolicy._

/** Tests of `project/LicencePolicy.scala`, run with `sbt "reload plugins" test` (CI's quick checks, #2308). */
class LicencePolicySpec extends AnyFlatSpec with Matchers {

  private def dep(artifact: String, licences: (String, Option[String])*): Dependency =
    Dependency(
      "com.example",
      artifact,
      "1.0",
      licences,
      Seq("llm4s-rag", "org.vendor:client", s"com.example:$artifact")
    )

  private def fails(d: Dependency, exceptions: Seq[Reviewed] = Nil): Seq[String] =
    failures(Seq("llm4s-rag" -> Seq(classify(d, exceptions))))

  "licenseCheck" should "fail a GPL-3.0 dependency, naming the module, the licence and the path that pulled it in" in {
    val lines = fails(
      dep("copyleft", "GNU General Public License, version 3" -> Some("https://www.gnu.org/licenses/gpl-3.0.txt"))
    )
    lines shouldBe Seq(
      "  llm4s-rag: com.example:copyleft:1.0: GPL-3.0-only is denied: strong copyleft; " +
        "via llm4s-rag -> org.vendor:client -> com.example:copyleft"
    )
  }

  it should "fail a dependency whose POM declares no licence as unknown" in {
    val row = classify(dep("silent"), Nil)
    row.licence shouldBe Unknown
    fails(dep("silent")) shouldBe Seq(
      "  llm4s-rag: com.example:silent:1.0: licence unknown: its POM declares no licence; " +
        "via llm4s-rag -> org.vendor:client -> com.example:silent"
    )
  }

  it should "fail a dependency whose licence it does not recognise as unknown, quoting what the POM says" in {
    val lines = fails(dep("odd", "Do What You Like v7" -> None))
    lines should have size 1
    lines.head should include(
      "licence unknown: its POM declares a licence the policy does not recognise (Do What You Like v7)"
    )
  }

  it should "fail AGPL, LGPL, SSPL, BUSL and Commons Clause with their reasons" in {
    val cases = Seq(
      "GNU Affero General Public License v3"           -> "AGPL-3.0-only is denied: network copyleft",
      "GNU Lesser General Public License, version 2.1" -> "LGPL-2.1-only is denied",
      "Server Side Public License"                     -> "SSPL-1.0 is denied",
      "Business Source License 1.1"                    -> "BUSL-1.1 is denied",
      "Apache 2.0 with Commons Clause"                 -> "Commons-Clause is denied",
      "The JSON License"                               -> "LicenseRef-JSON is denied"
    )
    cases.foreach { case (name, message) =>
      withClue(name) {
        val lines = fails(dep("x", name -> None))
        lines should have size 1
        lines.head should include(message)
      }
    }
  }

  it should "pass the permissive licences as their SPDX ids" in {
    val cases = Seq(
      "The Apache Software License, Version 2.0"  -> "Apache-2.0",
      "Apache 2"                                  -> "Apache-2.0",
      "MIT License"                               -> "MIT",
      "MIT-0"                                     -> "MIT-0",
      "BSD 3-Clause"                              -> "BSD-3-Clause",
      "Eclipse Distribution License - v 1.0"      -> "BSD-3-Clause",
      "The BSD 2-Clause License"                  -> "BSD-2-Clause",
      "Eclipse Public License - v 2.0"            -> "EPL-2.0",
      "Mozilla Public License, version 2.0"       -> "MPL-2.0",
      "Bouncy Castle Licence"                     -> "MIT",
      "Universal Permissive License, Version 1.0" -> "UPL-1.0",
      "GPL2 w/ CPE"                               -> "GPL-2.0-only WITH Classpath-exception-2.0"
    )
    cases.foreach { case (name, id) =>
      withClue(name) {
        val row = classify(dep("x", name -> None), Nil)
        row.licence shouldBe id
        row.problem shouldBe None
      }
    }
  }

  it should "recognise a licence by its URL when the name says nothing" in {
    classify(dep("x", "LICENSE" -> Some("https://opensource.org/licenses/MIT")), Nil).licence shouldBe "MIT"
  }

  it should "not read 'MIT' into a word that merely contains it" in {
    classify(dep("x", "Limited Use Licence" -> None), Nil).licence shouldBe Unknown
  }

  it should "report a dual-licensed dependency under its permissive option" in {
    val listed = classify(dep("jna", "LGPL-2.1-or-later" -> None, "Apache-2.0" -> None), Nil)
    listed.licence shouldBe "Apache-2.0"
    listed.problem shouldBe None
    val combined = classify(dep("jaxb", "CDDL+GPL License" -> None), Nil)
    combined.licence shouldBe "CDDL-1.1"
    combined.problem shouldBe None
  }

  "the exceptions file" should "record a reviewed licence for a dependency whose POM is silent" in {
    val exceptions =
      parseExceptions(Seq("# comment", "", "com.example:silent | MIT | the LICENSE file in the jar is MIT"))
    exceptions shouldBe Right(Seq(Reviewed("com.example:silent", "MIT", "the LICENSE file in the jar is MIT")))
    val row = classify(dep("silent"), exceptions.toOption.get)
    row.licence shouldBe "MIT"
    row.problem shouldBe None
    row.source shouldBe "reviewed: the LICENSE file in the jar is MIT"
  }

  it should "never admit a denied licence" in {
    val exceptions = Seq(Reviewed("com.example:copyleft", "GPL-3.0-only", "we like it"))
    fails(dep("copyleft"), exceptions).head should include(
      "GPL-3.0-only is denied: strong copyleft (in licence-exceptions)"
    )
  }

  it should "reject a malformed line, with its line number" in {
    parseExceptions(Seq("# header", "com.example:x | MIT")) shouldBe
      Left(Seq("line 2: 'com.example:x | MIT' is not 'group:artifact | SPDX id | justification'"))
  }

  it should "find entries that match no dependency" in {
    val stale = Reviewed("com.gone:lib", "MIT", "was reviewed")
    unused(Seq(stale), Seq(dep("x"))) shouldBe Seq(stale)
  }

  "pathTo" should "give the shortest chain of callers from the module to the dependency" in {
    val callers = Map(
      "d"  -> Seq("c", "b"),
      "c"  -> Seq("b"),
      "b"  -> Seq("root"),
      "e"  -> Seq("e2"),
      "e2" -> Seq("e")
    )
    pathTo("d", callers, "root") shouldBe Seq("root", "b", "d")
    pathTo("b", callers, "root") shouldBe Seq("root", "b")
    pathTo("e", callers, "root") shouldBe Seq("root", "e") // a cycle that never reaches the root
  }

  "csv" should "write one quoted row per dependency, sorted, with how it is reached" in {
    val rows =
      Seq(classify(dep("b", "MIT" -> None), Nil), classify(dep("a", "Apache License, Version 2.0" -> None), Nil))
    csv(rows) shouldBe
      """group,artifact,version,licence,source,via
        |com.example,a,1.0,Apache-2.0,"POM: Apache License, Version 2.0",llm4s-rag -> org.vendor:client -> com.example:a
        |com.example,b,1.0,MIT,POM: MIT,llm4s-rag -> org.vendor:client -> com.example:b
        |""".stripMargin
  }
}
