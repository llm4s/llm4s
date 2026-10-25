/**
 * Which licences a published `llm4s-*` module may bring onto its users' runtime classpath (#2308).
 *
 * Legal review of a library asks what licences come with it, transitively, and whether any is copyleft or
 * otherwise restricted. `sbt licenseReport` answers per module, one CSV next to each SBOM; `sbt licenseCheck` fails
 * the build when a runtime dependency's licence is denied, or is not one the policy knows, naming the module and the
 * path that pulled the dependency in, in the style of `FrozenDependencies`.
 *
 * The licence of a dependency is what its POM declares (Maven `<licenses>`, inherited from a parent POM), normalised
 * to an SPDX identifier by [[spdx]]. A POM that lists several licences, or one licence naming a choice ("CDDL + GPLv2
 * with classpath exception"), offers a choice: the report takes the first allowed one, so a dual-licensed library is
 * reported under its permissive option. A dependency whose POM declares nothing, or declares a licence [[spdx]] does
 * not recognise, is unknown and fails, unless `project/licence-exceptions.txt` records its licence after a review.
 *
 * This file uses the standard library only, so `project/src/test/scala/LicencePolicySpec.scala` can test it.
 * See docs/reference/licences.md.
 */
object LicencePolicy {

  /** SPDX identifiers a published module may depend on at runtime. Everything else fails the check. */
  val allowed: Seq[String] = Seq(
    "Apache-2.0",
    "MIT",
    "MIT-0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "0BSD",
    "ISC",
    "Zlib",
    "BSL-1.0",
    "UPL-1.0",
    "Unicode-3.0",
    "CC0-1.0",
    "Unlicense",
    "LicenseRef-Public-Domain",
    "EPL-1.0",
    "EPL-2.0",
    "MPL-2.0",
    "CDDL-1.0",
    "CDDL-1.1",
    "GPL-2.0-only WITH Classpath-exception-2.0"
  )

  /** Licences the policy refuses outright, by SPDX id prefix, with the reason the failure message gives. */
  val denied: Seq[(String, String)] = Seq(
    "AGPL-"           -> "network copyleft",
    "LGPL-"           -> "weak copyleft without a linking exception",
    "GPL-"            -> "strong copyleft",
    "SSPL-"           -> "Server Side Public License: not open source",
    "BUSL-"           -> "Business Source License: not open source",
    "Commons-Clause"  -> "Commons Clause: restricts selling the software",
    "LicenseRef-JSON" -> "JSON licence: its \"Good, not Evil\" clause is a field-of-use restriction"
  )

  /** What the report says for a dependency whose licence is not known. */
  val Unknown = "unknown"

  /**
   * Recognised licence names and URLs (lower case, matched as substrings), in order: the first match wins, so a
   * more specific pattern ("lesser general public", "classpath exception") comes before a more general one.
   * A licence that offers a choice maps to every option.
   */
  private val patterns: Seq[(Seq[String], Seq[String])] = Seq(
    Seq("commons clause")                     -> Seq("Commons-Clause"),
    Seq("server side public license", "sspl") -> Seq("SSPL-1.0"),
    Seq("business source license", "busl")    -> Seq("BUSL-1.1"),
    Seq("cddl+gpl", "cddl + gpl", "cddl/gplv2+ce", "cddl+gplv2") -> Seq(
      "CDDL-1.1",
      "GPL-2.0-only WITH Classpath-exception-2.0"
    ),
    Seq("classpath exception", "classpath-exception", "gpl2 w/ cpe", "gplv2+ce") -> Seq(
      "GPL-2.0-only WITH Classpath-exception-2.0"
    ),
    Seq("affero", "agpl") -> Seq("AGPL-3.0-only"),
    Seq("lesser general public license, version 3", "lgpl-3", "lgpl 3", "lgplv3", "lgpl, version 3") -> Seq(
      "LGPL-3.0-only"
    ),
    Seq("lesser general public", "library general public", "lgpl") -> Seq("LGPL-2.1-only"),
    Seq("general public license, version 3", "gpl-3", "gpl 3", "gplv3", "licenses/gpl.html") -> Seq(
      "GPL-3.0-only"
    ),
    Seq("general public license", "gpl-2", "gpl 2", "gplv2", "gpl") -> Seq("GPL-2.0-only"),
    Seq("apache")                                                   -> Seq("Apache-2.0"),
    Seq("mit-0", "mit no attribution")                              -> Seq("MIT-0"),
    Seq("bouncy castle")                                            -> Seq("MIT"),
    Seq("mit", "opensource.org/licenses/mit", "expat")              -> Seq("MIT"),
    Seq("0bsd", "zero-clause bsd")                                  -> Seq("0BSD"),
    Seq("eclipse distribution license", "edl")                      -> Seq("BSD-3-Clause"),
    Seq("bsd-2", "bsd 2", "2-clause", "simplified bsd", "freebsd")  -> Seq("BSD-2-Clause"),
    Seq("bsd-3", "bsd 3", "3-clause", "new bsd", "revised bsd", "modified bsd", "bsd new") -> Seq(
      "BSD-3-Clause"
    ),
    Seq(
      "eclipse public license - v 2",
      "eclipse public license v2",
      "epl-2",
      "epl 2",
      "eclipse public license 2",
      "legal/epl-2"
    )                                                                                      -> Seq("EPL-2.0"),
    Seq("eclipse public license", "epl-1", "epl 1", "epl-v10")                             -> Seq("EPL-1.0"),
    Seq("mozilla public license", "mpl-2", "mpl 2")                                        -> Seq("MPL-2.0"),
    Seq("common development and distribution license (cddl) v1.0", "cddl-1.0", "cddl 1.0") -> Seq("CDDL-1.0"),
    Seq("common development and distribution", "cddl")                                     -> Seq("CDDL-1.1"),
    Seq("universal permissive license", "upl")                                             -> Seq("UPL-1.0"),
    Seq("boost software license", "bsl-1.0")                                               -> Seq("BSL-1.0"),
    Seq("unicode")                                                                         -> Seq("Unicode-3.0"),
    Seq("cc0", "creative commons zero", "publicdomain/zero")                               -> Seq("CC0-1.0"),
    Seq("unlicense")                                                                       -> Seq("Unlicense"),
    Seq("isc")                                                                             -> Seq("ISC"),
    Seq("zlib")                                                                            -> Seq("Zlib"),
    Seq("the json license", "json.org/license")                                            -> Seq("LicenseRef-JSON")
  )

  /** `key` occurs in `text` as a whole word: "mit" matches "MIT License" but not "Limited". */
  private def word(key: String, text: String): Boolean =
    ("(?<![a-z0-9])" + java.util.regex.Pattern.quote(key) + "(?![a-z0-9])").r.findFirstIn(text).isDefined

  /** The SPDX options one declared licence (name, URL) offers; empty when neither is recognised. */
  def spdx(name: String, url: Option[String]): Seq[String] = {
    val exact = (name +: url.toSeq).map(_.trim).filter(s => allowed.contains(s) || s == "LicenseRef-JSON")
    if (exact.nonEmpty) Seq(exact.head)
    else {
      def lookup(text: String): Option[Seq[String]] = {
        val t = text.toLowerCase
        if (t.trim.isEmpty) None else patterns.collectFirst { case (keys, ids) if keys.exists(k => word(k, t)) => ids }
      }
      lookup(name).orElse(url.flatMap(lookup)).getOrElse(Nil)
    }
  }

  /** A runtime dependency of a published module: coordinates, the licences its POM declares, how it is reached. */
  final case class Dependency(
    group: String,
    artifact: String,
    version: String,
    declared: Seq[(String, Option[String])],
    path: Seq[String]
  ) {
    def coordinates: String = s"$group:$artifact:$version"
    def key: String         = s"$group:$artifact"
  }

  /** A reviewed decision for one dependency (any version): the licence a review established, and why. */
  final case class Reviewed(key: String, licence: String, justification: String)

  /** One row of a module's report. `licence` is an SPDX id or [[Unknown]]; `problem` is set when it is refused. */
  final case class Row(dependency: Dependency, licence: String, source: String, problem: Option[String])

  /**
   * Parses `project/licence-exceptions.txt`: one `group:artifact | SPDX id | justification` per line, `#` comments.
   * Returns the entries, or the lines that are malformed.
   */
  def parseExceptions(lines: Seq[String]): Either[Seq[String], Seq[Reviewed]] = {
    val parsed = lines.zipWithIndex.collect {
      case (line, i) if line.trim.nonEmpty && !line.trim.startsWith("#") =>
        line.split('|').map(_.trim).toSeq match {
          case Seq(key, licence, why) if key.count(_ == ':') == 1 && licence.nonEmpty && why.nonEmpty =>
            Right(Reviewed(key, licence, why))
          case _ =>
            Left(s"line ${i + 1}: '${line.trim}' is not 'group:artifact | SPDX id | justification'")
        }
    }
    val bad = parsed.collect { case Left(e) => e }
    if (bad.nonEmpty) Left(bad) else Right(parsed.collect { case Right(e) => e })
  }

  private def refusal(id: String): Option[String] =
    if (allowed.contains(id)) None
    else
      denied
        .collectFirst { case (prefix, why) if id.startsWith(prefix) => s"$id is denied: $why" }
        .orElse(Some(s"$id is not on the allow-list"))

  /** The report row for one dependency. */
  def classify(dep: Dependency, exceptions: Seq[Reviewed]): Row =
    exceptions.find(_.key == dep.key) match {
      case Some(e) =>
        Row(dep, e.licence, s"reviewed: ${e.justification}", refusal(e.licence).map(r => s"$r (in licence-exceptions)"))
      case None =>
        val options = dep.declared.flatMap { case (name, url) => spdx(name, url) }.distinct
        val shown   = dep.declared.map(_._1).mkString(" / ")
        options.find(allowed.contains) match {
          case Some(id) => Row(dep, id, s"POM: $shown", None)
          case None if options.nonEmpty =>
            Row(dep, options.head, s"POM: $shown", refusal(options.head))
          case None =>
            val why =
              if (dep.declared.isEmpty) "its POM declares no licence"
              else s"its POM declares a licence the policy does not recognise ($shown)"
            Row(dep, Unknown, if (dep.declared.isEmpty) "POM: none" else s"POM: $shown", Some(s"licence unknown: $why"))
        }
    }

  /** Exceptions that match no dependency of any module: stale entries that would otherwise hide a future change. */
  def unused(exceptions: Seq[Reviewed], deps: Seq[Dependency]): Seq[Reviewed] =
    exceptions.filterNot(e => deps.exists(_.key == e.key))

  /** The failure lines for each module's refused rows, one per (module, dependency), with the path that reached it. */
  def failures(modules: Seq[(String, Seq[Row])]): Seq[String] =
    for {
      (module, rows) <- modules
      row            <- rows.sortBy(_.dependency.coordinates)
      problem        <- row.problem.toSeq
    } yield s"  $module: ${row.dependency.coordinates}: $problem; via ${row.dependency.path.mkString(" -> ")}"

  /**
   * The shortest chain of callers from `root` to `module`, both included, given each module's callers. A module
   * reached from several places shows one of the shortest routes, which is what "how does this arrive" needs.
   */
  def pathTo(module: String, callers: Map[String, Seq[String]], root: String): Seq[String] = {
    @annotation.tailrec
    def search(frontier: List[List[String]], seen: Set[String]): Seq[String] = frontier match {
      case Nil                                       => Seq(root, module)
      case (path @ (head :: _)) :: _ if head == root => path
      case (path @ (head :: _)) :: rest =>
        val next = callers.getOrElse(head, Nil).filterNot(seen.contains).distinct
        search(rest ++ next.map(_ :: path), seen ++ next)
      case Nil :: rest => search(rest, seen)
    }
    search(List(List(module)), Set(module))
  }

  /** One module's report as CSV: a header, then one row per dependency, sorted, with how it is reached. */
  def csv(rows: Seq[Row]): String = {
    def cell(s: String): String =
      if (s.exists(c => c == ',' || c == '"' || c == '\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    val header = "group,artifact,version,licence,source,via"
    val body = rows.sortBy(_.dependency.coordinates).map { r =>
      val d = r.dependency
      Seq(d.group, d.artifact, d.version, r.licence, r.source, d.path.mkString(" -> ")).map(cell).mkString(",")
    }
    (header +: body).mkString("", "\n", "\n")
  }
}
