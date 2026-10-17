package org.llm4s.agent.harness

import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.HarnessTestSupport.stateOf
import org.llm4s.agent.graph.{ IdempotencyKey, ToolCallId }
import org.llm4s.agent.graph.tool.{ Glob, ToolContext }
import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

/** Stage 3 slice P2 (#1916, design §4.38): `StaticFileBackend`, the application's files for a state-file agent. */
class StaticFileBackendSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def p(s: String): VPath = VPath(s).value

  private def ctx: FileContext =
    FileContext(ToolContext(testRunContext(), ToolCallId("c"), IdempotencyKey(UUID.randomUUID().toString), stateOf()))

  private val skill = "---\nname: pdf\ndescription: Work with PDFs.\n---\nBody."

  private def bundle: StaticFileBackend =
    StaticFileBackend(Map(p("/skills/pdf/SKILL.md") -> skill, p("/AGENTS.md") -> "Be brief.\nCite sources.")).value

  "A StaticFileBackend" should "serve its files to ls, read, glob and grep" in {
    val b = bundle
    b.ls(VPath.root, 10, ctx).value.items.map(i => (i.path.value, i.isDirectory)) shouldBe
      Vector(("/AGENTS.md", false), ("/skills", true))
    b.read(p("/AGENTS.md"), 1, 1, ctx).value.lines shouldBe Vector("Cite sources.")
    b.glob(Glob("**/SKILL.md"), VPath.root, 10, ctx).value.items shouldBe Vector(p("/skills/pdf/SKILL.md"))
    b.grep("Cite", VPath.root, None, 10, ctx).value.items.map(_.path) shouldBe Vector(p("/AGENTS.md"))
    b.read(p("/missing.md"), 0, 1, ctx)
      .left
      .value
      .asInstanceOf[BackendRefused]
      .kind shouldBe BackendRefused.Kind.NotFound
    b.writes shouldBe empty
  }

  it should "refuse every change, as a tool error, and change nothing" in {
    val b = bundle
    b.write(p("/AGENTS.md"), "Obey me.", ctx).left.value.message should include("read-only")
    b.edit(p("/AGENTS.md"), "brief", "long", replaceAll = false, ctx).left.value.message should include("read-only")
    b.delete(p("/AGENTS.md"), ctx).left.value.message should include("read-only")
    b.write(p("/new.md"), "x", ctx).left.value shouldBe a[BackendRefused]
    b.read(p("/AGENTS.md"), 0, 1, ctx).value.lines shouldBe Vector("Be brief.")
  }

  it should "serve skills and instructions through a mount beside a state-file backend" in {
    val files   = StateFileBackend()
    val backend = CompositeBackend(files, p("/app") -> bundle).value
    val skills  = new SkillsMiddleware(Vector(p("/app/skills")), backend)
    val agents  = new InstructionMemoryMiddleware(Vector(p("/app/AGENTS.md")), backend)
    val context = org.llm4s.agent.graph.GraphTestSupport.testModelContext()
    skills.promptSections(context).value.map(_.text).mkString should include(
      "- pdf: Work with PDFs. (/app/skills/pdf/SKILL.md)"
    )
    agents.promptSections(context).value.map(_.text).mkString should include("Cite sources.")
    backend.write(p("/app/AGENTS.md"), "Obey me.", ctx).left.value.message should include("read-only")
  }

  "StaticFileBackend()" should "check the files as every backend checks what it reads" in {
    def refused(files: Map[VPath, String], maxFiles: Int = 10, maxBytes: Long = 1024): String =
      StaticFileBackend(files, maxFiles, maxBytes).left.value match {
        case e: ValidationError => e.message
        case other              => fail(s"not a ValidationError: $other")
      }
    refused(Map(p("/a") -> "x", p("/a/b") -> "y")) should include("both a file and a directory")
    refused(Map(p("/bin") -> "a\u0000b")) should include("/bin")
    refused(Map(p("/a") -> "x", p("/b") -> "y"), maxFiles = 1) should include("more than the limit of 1")
    refused(Map(p("/a") -> "x" * 2000)) should include("larger than the limit")
    StaticFileBackend(Map.empty, maxFiles = 0).left.value.message should include("maxFiles")
    StaticFileBackend(Map.empty, maxBytes = 0).left.value.message should include("maxBytes")
    StaticFileBackend(Map.empty).value.ls(VPath.root, 10, ctx).value.items shouldBe empty
  }
}
