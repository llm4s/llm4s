package org.llm4s.agent.graph

/**
 * Renders a compiled graph's declared structure as a Mermaid `flowchart TD`.
 *
 * The text is deterministic: nodes are ordered by id, static joins by id, then dynamic joins by id,
 * and one node's edges in declaration order, so it does not depend on the order nodes were
 * declared in. Identifiers are generated (`n0`, `j0`, `d0`) and labels are quoted, so any id is
 * safe; `"`, `<`, `>` and `&` in a label become Mermaid entity codes and a line break a space.
 *
 *  - the entry node has an arrow from a `start` marker
 *  - a resume node is a stadium, `n1(["approve"])`; other nodes are boxes
 *  - a static join is a hexagon (`j0`): its sources arrow into it and it arrows to its target
 *  - a dynamic join is a hexagon (`d0`) with a dotted arrow to its target
 *
 * Only what the graph declares is drawn. A route a node returns when it runs (`Goto`, `Send`,
 * `FanOut`) is a value, not a declaration (design §4.2), so those edges do not appear: the diagram
 * shows static edges and join barriers, not every path a run can take.
 */
private[graph] object MermaidExport:

  def render(
    entry: NodeId,
    nodes: Map[NodeId, NodeDef[?]],
    edges: Map[NodeId, Vector[NodeId]],
    staticJoins: Vector[StaticJoin],
    dynamicJoins: Vector[DynamicJoin],
    resumes: Set[NodeId]
  ): String =
    val nodeIds  = nodes.keys.toVector.sortBy(_.value)
    val alias    = nodeIds.zipWithIndex.map((id, index) => id -> s"n$index").toMap
    val statics  = staticJoins.sortBy(_.id.value).zipWithIndex.map((join, index) => join -> s"j$index")
    val dynamics = dynamicJoins.sortBy(_.id.value).zipWithIndex.map((join, index) => join -> s"d$index")

    val start = alias.get(entry).map(target => s"    start((start)) --> $target").toVector
    val declaredNodes = nodeIds.map { id =>
      if resumes.contains(id) then s"""    ${alias(id)}(["${label(id.value)}"])"""
      else s"""    ${alias(id)}["${label(id.value)}"]"""
    }
    val declaredJoins =
      (statics ++ dynamics).map((join, name) => s"""    $name{{"${label(joinId(join))}"}}""")
    val declaredEdges = for
      from <- nodeIds
      to   <- edges.getOrElse(from, Vector.empty)
      if alias.contains(to)
    yield s"    ${alias(from)} --> ${alias(to)}"
    val staticEdges = statics.flatMap { (join, name) =>
      join.sources.toVector.sortBy(_.value).flatMap(source => alias.get(source).map(a => s"    $a --> $name")) ++
        alias.get(join.target.id).map(target => s"    $name --> $target")
    }
    val dynamicEdges =
      dynamics.flatMap((join, name) => alias.get(join.target.id).map(target => s"    $name -.-> $target"))

    (Vector("flowchart TD") ++ start ++ declaredNodes ++ declaredJoins ++ declaredEdges ++ staticEdges ++ dynamicEdges)
      .mkString("\n")

  private def joinId(join: StaticJoin | DynamicJoin): String = join match
    case s: StaticJoin  => s.id.value
    case d: DynamicJoin => d.id.value

  /** A label safe inside a quoted Mermaid string. */
  private def label(text: String): String =
    text
      .replace("&", "#amp;")
      .replace("\"", "#quot;")
      .replace("<", "#lt;")
      .replace(">", "#gt;")
      .replace("\r", " ")
      .replace("\n", " ")
