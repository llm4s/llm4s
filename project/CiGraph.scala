import sbt._
import sbt.Keys._

/**
 * `generateCiGraph [file]` writes the project graph CI selects tests from (#3184) to `.github/ci-graph.json` (or
 * `file`): for every project, its base directory, its in-build dependencies with their configurations as declared
 * (`compile->compile`, `test->compile` for `% Test`, `test->test`, `provided`, `optional`, ...), and the source files
 * it compiles from outside its own directory (`Test / unmanagedSources ++= ...`, such as `it` borrowing samples'
 * Kafka pipeline), which a path-to-project mapping alone would miss.
 *
 * The file is committed, so `scripts/ci-changes.sh` reads it without starting sbt, and Quick Checks fails when it
 * differs from what the build says ("Check the CI project graph is up to date"). One project per line, sorted, so a
 * change to the build shows as a small diff.
 */
object CiGraph {

  val command: Command = Command.args("generateCiGraph", "[file]") { (state, args) =>
    val extracted = Project.extract(state)
    val structure = extracted.structure
    val root      = file(".").getCanonicalFile
    val out       = args.headOption.map(file).getOrElse(root / ".github" / "ci-graph.json")

    def rel(f: File): String =
      root.toPath.relativize(f.getCanonicalFile.toPath).toString.replace('\\', '/')
    def str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    var current = state
    val rows = structure.allProjectRefs.sortBy(_.project).map { ref =>
      val resolved = Project.getProject(ref, structure).get
      val base     = rel(resolved.base) match { case "" => "."; case b => b }
      val deps = resolved.dependencies
        .map(d => (d.project.project, d.configuration.getOrElse("compile->compile")))
        .sortBy(_._1)
        .map { case (p, c) => s"[${str(p)},${str(c)}]" }
      val borrowed = Seq(Compile, Test).flatMap { conf =>
        val (next, files) = extracted.runTask(ref / conf / unmanagedSources, current)
        current = next
        files
          .filterNot(_.getCanonicalPath.startsWith(resolved.base.getCanonicalPath + java.io.File.separator))
          .map(f => s"[${str(conf.name)},${str(rel(f))}]")
      }.sorted
      s"""    ${str(ref.project)}: {"base":${str(base)},"deps":[${deps.mkString(",")}],"borrowed":[${borrowed
          .mkString(",")}]}"""
    }
    IO.write(
      out,
      "{\n  \"generatedBy\": \"sbt generateCiGraph (project/CiGraph.scala)\",\n  \"projects\": {\n" +
        rows.mkString(",\n") + "\n  }\n}\n"
    )
    current.log.info(s"Wrote the CI project graph (${rows.size} projects) to ${rel(out)}")
    current
  }
}
