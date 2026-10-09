import sbt._
import sbt.Keys._

/**
 * `dumpBuildModel <file>` writes what sbt itself knows about this build as JSON, for
 * scripts/check-doc-support.sh to check the docs' `sbt ...` commands, modules and versions against.
 *
 * The check used to guess the build from build.sbt's text with regular expressions - which projects
 * exist, which aggregate which, where a task is set, which plugin adds which command - and every
 * review found another construct the guesses missed. This reads the loaded build instead:
 *
 *  - every project: id, base directory, the projects it aggregates, its configurations (with the
 *    ones each extends), and every key defined in it as `config`, `task axis` and `key`, so a
 *    configuration a plugin adds (`Docker`, `Jmh`) exists exactly where the plugin is enabled;
 *  - the keys defined at `ThisBuild` and `Global`, which every project delegates to;
 *  - per project, the keys whose `aggregate` is `false` there;
 *  - the commands sbt and the loaded plugins define, and each command alias with its body;
 *  - each project's `scalaVersion` and `crossScalaVersions`.
 */
object BuildModel {

  val dumpCommand: Command = Command.single("dumpBuildModel") { (state, out) =>
    val extracted = Project.extract(state)
    val structure = extracted.structure
    val root      = structure.root
    val rootDir   = structure.units(root).localBase.getCanonicalFile

    def str(s: String): String = {
      val b = new StringBuilder("\"")
      s.foreach {
        case '"'          => b.append("\\\"")
        case '\\'         => b.append("\\\\")
        case '\n'         => b.append("\\n")
        case '\r'         => b.append("\\r")
        case '\t'         => b.append("\\t")
        case c if c < ' ' => b.append(f"\\u${c.toInt}%04x")
        case c            => b.append(c)
      }
      b.append('"').toString
    }
    def arr(xs: Iterable[String]): String      = xs.mkString("[", ",", "]")
    def strs(xs: Iterable[String]): String     = arr(xs.map(str))
    def obj(fields: (String, String)*): String = fields.map { case (k, v) => s"${str(k)}:$v" }.mkString("{", ",", "}")

    def axisName[T](axis: ScopeAxis[T])(name: T => String): String = axis match {
      case Select(t) => name(t)
      case _         => ""
    }

    /** `[config, task, key]`: the config's ivy name and the task axis's label, empty for Zero. */
    def keyRow(scope: Scope, key: AttributeKey[_]): String =
      strs(Seq(axisName(scope.config)(_.name), axisName(scope.task)(_.label), key.label))

    val byProject = structure.data.scopes.toSeq.groupBy(_.project)
    def rows(scopes: Seq[Scope]): Seq[String] =
      scopes.flatMap(s => structure.data.keys(s).toSeq.map(k => keyRow(s, k))).distinct.sorted

    // The identifier sbt's slash syntax uses for each configuration name, from every project's declarations.
    val allConfigIds: Map[String, String] = (Map("it" -> "IntegrationTest") ++ structure.allProjectRefs.flatMap { ref =>
      extracted.getOpt(ref / ivyConfigurations).getOrElse(Nil).map(c => c.name -> c.id)
    }).toMap

    val projects = structure.allProjectRefs.sortBy(_.project).map { ref =>
      val resolved = Project.getProject(ref, structure).get
      val base     = rootDir.toPath.relativize(resolved.base.getCanonicalFile.toPath).toString.replace('\\', '/')
      val scopes   = byProject.getOrElse(Select(ref), Nil)
      val declared = (resolved.configurations ++ extracted.getOpt(ref / ivyConfigurations).getOrElse(Nil))
        .groupBy(_.name)
        .values
        .map(c => (c.head.id, c.head.name, c.head.extendsConfigs.map(_.name)))
        .toSeq
      // A plugin may define keys in a configuration the project never declares (sbt-scalafmt's `it`);
      // sbt still parses that configuration in the project, under the identifier of its definition.
      val undeclared = scopes
        .flatMap(s => axisName(s.config)(_.name) match { case "" => None; case n => Some(n) })
        .distinct
        .filterNot(n => declared.exists(_._2 == n))
        .map(n => (allConfigIds.getOrElse(n, n.capitalize), n, Seq.empty[String]))
      val configs = (declared ++ undeclared).sortBy(_._2)
      val noAggregate = scopes
        .filter(s => s.task.isSelect && structure.data.keys(s).exists(_.label == aggregate.key.label))
        .flatMap(s =>
          axisName(s.task)(_.label) match {
            case "" => None
            case k  => if (structure.data.get(s, aggregate.key).contains(false)) Some(k) else None
          }
        )
        .distinct
        .sorted
      obj(
        "id"        -> str(ref.project),
        "base"      -> str(if (base.isEmpty) "." else base),
        "aggregate" -> strs(resolved.aggregate.map(_.project).sorted),
        "configurations" -> arr(configs.map { case (id, name, ext) =>
          obj("id" -> str(id), "name" -> str(name), "extends" -> strs(ext))
        }),
        "keys"               -> arr(rows(scopes)),
        "noAggregate"        -> strs(noAggregate),
        "scalaVersion"       -> str(extracted.getOpt(ref / scalaVersion).getOrElse("")),
        "crossScalaVersions" -> strs(extracted.getOpt(ref / crossScalaVersions).getOrElse(Nil))
      )
    }

    val buildScopes = structure.data.scopes.toSeq.filter(_.project match {
      case Select(BuildRef(_)) => true
      case _                   => false
    })
    val globalScopes = structure.data.scopes.toSeq.filter(_.project == Zero)
    val commands     = state.definedCommands.flatMap(_.nameOption).distinct.sorted
    val aliases      = BasicCommands.allAliases(state).sortBy(_._1)

    val json = obj(
      "format"     -> "1",
      "root"       -> str(extracted.rootProject(root)),
      "projects"   -> arr(projects),
      "buildKeys"  -> arr(rows(buildScopes)),
      "globalKeys" -> arr(rows(globalScopes)),
      "commands"   -> strs(commands),
      "aliases"    -> arr(aliases.map { case (n, body) => obj("name" -> str(n), "body" -> str(body)) })
    )
    val file = new File(out)
    IO.write(file, json + "\n")
    state.log.info(
      s"Wrote the build model (${projects.size} projects, ${commands.size} commands, ${aliases.size} aliases) to $file"
    )
    state
  }
}
