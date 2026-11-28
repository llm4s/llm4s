import sbt._
import sbt.Keys._
import scoverage.ScoverageKeys

/**
 * `generateCiGraph [file]` writes the project graph CI selects tests from (#3184) to `.github/ci-graph.json` (or
 * `file`): for every project, its base directory, its in-build dependencies with their configurations as declared
 * (`compile->compile`, `test->compile` for `% Test`, `test->test`, `provided`, `optional`, ...), and the source files
 * it compiles from outside its own directory (`Test / unmanagedSources ++= ...`, such as `it` borrowing samples'
 * Kafka pipeline), which a path-to-project mapping alone would miss.
 *
 * It also writes each project's settings fingerprint (#3182, #3189): `fp.compile` hashes what its main code and every
 * dependent see (library dependencies outside the test configuration, overrides and exclusions, the Scala version,
 * main scalac/javac options, main source and resource directories, enabled plugins, the number of main generators);
 * `fp.test` what only its own tests see (test library dependencies, test scalac/javac options, test options, forking,
 * JVM options and environment, parallelism, the coverage policy, test directories and generators). And `global`
 * hashes the command aliases. `scripts/ci-changes.sh` compares base's file with head's, so a `build.sbt` edit that
 * changes one module's floor selects that module's tests, and a comment selects nothing, with no sbt in the `Changes`
 * job. What a hash cannot see (a task's or command's body, a generator's code) is listed there.
 *
 * Paths are relative to the build root, and the user's home and the coursier cache are replaced by placeholders, so
 * the file is the same on every machine and checkout. The file is committed, so `scripts/ci-changes.sh` reads it
 * without starting sbt, and Quick Checks fails when it differs from what the build says ("Check the CI project graph
 * is up to date"). One project per line, sorted, so a change to the build shows as a small diff.
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
    val home                   = sys.props("user.home")
    // what differs between machines, longest first, so the build root inside the home is replaced as the root
    val prefixes = Seq(root.getCanonicalPath -> "$ROOT", root.getAbsolutePath -> "$ROOT") ++
      Option(sys.env.getOrElse("COURSIER_CACHE", null)).map(_ -> "$COURSIER").toSeq ++
      Seq(s"$home/Library/Caches/Coursier" -> "$COURSIER", s"$home/.cache/coursier" -> "$COURSIER", home -> "$HOME")
    def norm(s: String): String = prefixes.foldLeft(s.replace('\\', '/')) { case (acc, (p, to)) =>
      acc.replace(p.replace('\\', '/'), to)
    }
    // the version is dynver's, so it differs with every commit: in a test's JVM options and environment (`it` names
    // the workspace image by it) it is a placeholder; nowhere else, where a release's `1.2.0` could be a library's
    val version = extracted.get(ThisBuild / Keys.version)
    // (also as a Docker tag, `+` made `-`: WorkspaceRunnerDocker)
    def unversioned(s: Seq[String]): String =
      s.map { v =>
        if (version.isEmpty) v else v.replace(version, "$VERSION").replace(version.replace('+', '-'), "$VERSION")
      }.mkString(" ")
    def hash(parts: Seq[String]): String = {
      val md = java.security.MessageDigest.getInstance("SHA-256")
      md.digest(parts.map(norm).mkString("\n").getBytes("UTF-8")).take(8).map("%02x".format(_)).mkString
    }
    def option(o: TestOption): String = o match {
      case Tests.Argument(fw, as) =>
        s"Argument(${fw.map(_.implClassNames.mkString("|")).getOrElse("")},${as.mkString(" ")})"
      case Tests.Exclude(ts) => s"Exclude(${ts.toSeq.sorted.mkString(",")})"
      case _: Tests.Filter   => "Filter"
      case _: Tests.Setup    => "Setup"
      case _: Tests.Cleanup  => "Cleanup"
      case other             => other.getClass.getName
    }

    var current = state
    def task[A](key: TaskKey[A]): A = {
      val (next, a) = extracted.runTask(key, current)
      current = next
      a
    }
    def opt[A](key: SettingKey[A]): String = extracted.getOpt(key).map(_.toString).getOrElse("-")
    def isTest(m: ModuleID): Boolean       = m.configurations.exists(_.split("[;,]").exists(_.trim.startsWith("test")))

    val rows = structure.allProjectRefs.sortBy(_.project).map { ref =>
      val resolved = Project.getProject(ref, structure).get
      val base     = rel(resolved.base) match { case "" => "."; case b => b }
      val deps = resolved.dependencies
        .map(d => (d.project.project, d.configuration.getOrElse("compile->compile")))
        .sortBy(_._1)
        .map { case (p, c) => s"[${str(p)},${str(c)}]" }
      val borrowed = Seq(Compile, Test).flatMap { conf =>
        task(ref / conf / unmanagedSources)
          .filterNot(_.getCanonicalPath.startsWith(resolved.base.getCanonicalPath + java.io.File.separator))
          .map(f => s"[${str(conf.name)},${str(rel(f))}]")
      }.sorted
      val (tl, cl) = extracted.get(ref / libraryDependencies).partition(isTest)
      val dirs     = (k: SettingKey[Seq[File]]) => extracted.get(k).map(rel).sorted.mkString(",")
      val gens     = (k: SettingKey[Seq[Task[Seq[File]]]]) => extracted.getOpt(k).map(_.size).getOrElse(0).toString
      val compileFp = hash(
        Seq(
          s"libs ${cl.map(_.toString).sorted.mkString(";")}",
          s"overrides ${extracted.get(ref / dependencyOverrides).map(_.toString).sorted.mkString(";")}",
          s"excludes ${extracted.get(ref / excludeDependencies).map(_.toString).sorted.mkString(";")}",
          s"scala ${extracted.get(ref / scalaVersion)}",
          s"scalac ${task(ref / Compile / scalacOptions).mkString(" ")}",
          s"javac ${task(ref / Compile / javacOptions).mkString(" ")}",
          s"sources ${dirs(ref / Compile / unmanagedSourceDirectories)}",
          s"resources ${dirs(ref / Compile / unmanagedResourceDirectories)}",
          s"plugins ${resolved.autoPlugins.map(_.label).sorted.mkString(",")}",
          s"generators ${gens(ref / Compile / sourceGenerators)} ${gens(ref / Compile / resourceGenerators)}"
        )
      )
      val testFp = hash(
        Seq(
          s"libs ${tl.map(_.toString).sorted.mkString(";")}",
          s"scalac ${task(ref / Test / scalacOptions).mkString(" ")}",
          s"javac ${task(ref / Test / javacOptions).mkString(" ")}",
          s"options ${task(ref / Test / testOptions).map(option).mkString(" ")}",
          s"test options ${task(ref / Test / test / testOptions).map(option).mkString(" ")}",
          s"fork ${opt(ref / Test / fork)} ${opt(ref / Test / parallelExecution)} ${opt(ref / Test / testForkedParallel)}",
          s"jvm ${unversioned(task(ref / Test / javaOptions))}",
          s"env ${unversioned(task(ref / Test / envVars).toSeq.sorted.map { case (k, v) => s"$k=$v" })}",
          s"coverage ${opt(ref / Coverage.coveragePolicy)} ${opt(ref / ScoverageKeys.coverageEnabled)} " +
            s"${opt(ref / ScoverageKeys.coverageMinimumStmtTotal)} ${opt(ref / ScoverageKeys.coverageFailOnMinimum)} " +
            s"${opt(ref / ScoverageKeys.coverageExcludedPackages)} ${opt(ref / ScoverageKeys.coverageExcludedFiles)}",
          s"sources ${dirs(ref / Test / unmanagedSourceDirectories)}",
          s"resources ${dirs(ref / Test / unmanagedResourceDirectories)}",
          s"generators ${gens(ref / Test / sourceGenerators)} ${gens(ref / Test / resourceGenerators)}"
        )
      )
      s"""    ${str(ref.project)}: {"base":${str(base)},"deps":[${deps.mkString(",")}],"borrowed":[${borrowed
          .mkString(",")}],"fp":{"compile":${str(compileFp)},"test":${str(testFp)}}}"""
    }
    val global = hash(BasicCommands.allAliases(current).sortBy(_._1).map { case (n, v) => s"$n=$v" })
    IO.write(
      out,
      "{\n  \"generatedBy\": \"sbt generateCiGraph (project/CiGraph.scala)\",\n  \"global\": " + str(global) +
        ",\n  \"projects\": {\n" + rows.mkString(",\n") + "\n  }\n}\n"
    )
    current.log.info(s"Wrote the CI project graph (${rows.size} projects) to ${rel(out)}")
    current
  }

}
