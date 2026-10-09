import sjsonnew.shaded.scalajson.ast.unsafe.{ JArray, JField, JObject, JString, JValue }
import sbt.MessageOnlyException
import sjsonnew.support.scalajson.unsafe.{ Parser, PrettyPrinter }

/**
 * Keeps a CycloneDX BOM to what the described module really depends on.
 *
 * `sbt-sbom` lists a project that this module depends on only for its tests (`dependsOn(other % Test)`) as a
 * component with scope `required`, although the module's POM gives it `<scope>test</scope>` and the BOM's own
 * dependency graph has no edge from the module to it: 21 such components in 17 of the 32 published modules,
 * for example `llm4s-provider-testkit` in nearly every provider module. A BOM that says a module needs its
 * test kit is wrong, so `publishedBoms` writes each BOM through [[prune]].
 *
 * The rule is the BOM's own: a component stays when the described component reaches it through the
 * `dependencies` graph, and a dependency entry stays when its `ref` is reachable. Everything else is dropped.
 * Nothing else about the file changes (key order and values are kept; only the layout is the printer's).
 */
object SbomPrune {

  private def field(o: JObject, name: String): Option[JValue] = o.value.find(_.field == name).map(_.value)

  private def str(v: Option[JValue]): Option[String] = v.collect { case JString(s) => s }

  private def refs(v: Option[JValue]): Seq[String] = v match {
    case Some(JArray(items)) => items.toSeq.collect { case JString(s) => s }
    case _                   => Nil
  }

  private def items(v: Option[JValue]): Seq[JValue] = v match {
    case Some(JArray(xs)) => xs.toSeq
    case _                => Nil
  }

  /** The `ref`s reachable from `root` over (ref -> dependsOn) edges, root included. */
  def reachable(root: String, edges: Map[String, Seq[String]]): Set[String] = {
    @scala.annotation.tailrec
    def visit(pending: List[String], seen: Set[String]): Set[String] = pending match {
      case Nil                               => seen
      case ref :: rest if seen.contains(ref) => visit(rest, seen)
      case ref :: rest                       => visit(edges.getOrElse(ref, Nil).toList ::: rest, seen + ref)
    }
    visit(root :: Nil, Set.empty)
  }

  /** @return the BOM text without the components its module does not reach, and how many were dropped. */
  def prune(text: String): (String, Int) = {
    val bom = Parser.parseFromString(text).getOrElse(throw new MessageOnlyException("The BOM is not valid JSON"))
    bom match {
      case obj: JObject =>
        val rootRef = field(obj, "metadata")
          .collect { case m: JObject => m }
          .flatMap(m => field(m, "component"))
          .collect { case c: JObject => c }
          .flatMap(c => str(field(c, "bom-ref")))
          .getOrElse(throw new MessageOnlyException("The BOM's metadata.component has no bom-ref"))

        val edges = items(field(obj, "dependencies")).collect { case d: JObject =>
          str(field(d, "ref")).getOrElse("") -> refs(field(d, "dependsOn"))
        }.toMap
        val keep = reachable(rootRef, edges)

        val components = items(field(obj, "components"))
        val kept = components.filter {
          case c: JObject => str(field(c, "bom-ref")).forall(keep.contains)
          case _          => true
        }
        val dependencies = items(field(obj, "dependencies")).filter {
          case d: JObject => str(field(d, "ref")).forall(keep.contains)
          case _          => true
        }

        def replace(name: String, values: Seq[JValue]): JField => JField = {
          case JField(`name`, _) => JField(name, JArray(values.toArray))
          case other             => other
        }
        val rewritten = obj.value.map(replace("components", kept).andThen(replace("dependencies", dependencies)))
        (PrettyPrinter(JObject(rewritten)) + "\n", components.size - kept.size)
      case _ => throw new MessageOnlyException("The BOM is not a JSON object")
    }
  }
}
