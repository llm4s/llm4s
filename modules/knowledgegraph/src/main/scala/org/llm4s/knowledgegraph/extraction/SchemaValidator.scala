package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

/**
 * Validates an extracted graph against an ExtractionSchema.
 *
 * Separates nodes and edges into conforming (valid) and non-conforming (out-of-schema)
 * sets, and reports specific constraint violations such as relationship endpoint type
 * mismatches.
 *
 * What it enforces (#2971):
 *  - A node's label matches an entity type, and an edge's relationship a relationship type, ignoring case; in the
 *    valid graph both are respelled as the schema spells them, so `person` and `Person` become one label. An empty
 *    `entityTypes` (or `relationshipTypes`) admits any label (relationship), as an empty `sourceTypes` admits any
 *    source.
 *  - An in-schema node lacking a `required` property (absent or `null`; the key matched ignoring case) is a
 *    `missing_required_property` violation.
 *  - An in-schema edge whose endpoint labels break its `sourceTypes`/`targetTypes` is an `invalid_source_type` or
 *    `invalid_target_type` violation.
 *  - An edge whose endpoint is missing, or is dropped, is an `endpoint_missing` violation.
 *
 * With `allowOutOfSchema = false` the valid graph holds only what conforms: out-of-schema nodes and edges, nodes
 * missing a required property, edges that break their endpoint types and edges to a dropped node are all left out.
 * With `allowOutOfSchema = true` everything is kept (labels respelled) and only reported.
 *
 * @example
 * {{{
 * val schema = ExtractionSchema.simple(
 *   entityTypes = Seq("Person", "Organization"),
 *   relationshipTypes = Seq("WORKS_FOR")
 * )
 * val validator = new SchemaValidator(schema)
 * val result = validator.validate(extractedGraph)
 * if (result.isFullyValid) println("Graph conforms to schema")
 * }}}
 *
 * @param schema The extraction schema to validate against
 */
class SchemaValidator(schema: ExtractionSchema) {

  /**
   * Validates the given graph against the schema.
   *
   * @param graph The graph to validate
   * @return A ValidationResult with valid/invalid items separated
   */
  def validate(graph: Graph): ValidationResult = {
    val strict = !schema.allowOutOfSchema

    def entityType(label: String): Option[EntityTypeDefinition] =
      if (schema.entityTypes.isEmpty) None else schema.findEntityType(label)
    def inSchemaNode(node: Node): Boolean = schema.entityTypes.isEmpty || entityType(node.label).isDefined
    def inSchemaRel(rel: String): Boolean =
      schema.relationshipTypes.isEmpty || schema.findRelationshipType(rel).isDefined

    // Labels respelled as the schema spells them
    val nodes: Map[String, Node] = graph.nodes.map { case (id, node) =>
      id -> entityType(node.label).fold(node)(t => node.copy(label = t.name))
    }

    val outOfSchemaNodes = graph.nodes.values.filterNot(inSchemaNode).toList.sortBy(_.id)

    val missingRequired: List[SchemaViolation] = nodes.values.toList.sortBy(_.id).flatMap { node =>
      entityType(node.label).toList.flatMap(_.properties.filter(_.required)).flatMap { prop =>
        val present = node.properties.exists { case (k, v) => k.equalsIgnoreCase(prop.name) && v != ujson.Null }
        if (present) None
        else
          Some(
            SchemaViolation(
              s"Entity '${node.id}' of type ${node.label} lacks the required property '${prop.name}'",
              entityId = Some(node.id),
              violationType = "missing_required_property"
            )
          )
      }
    }
    val lackingRequired = missingRequired.flatMap(_.entityId).toSet

    val keptNodes: Map[String, Node] =
      if (strict) nodes.filter { case (id, node) => inSchemaNode(node) && !lackingRequired.contains(id) }
      else nodes

    val outOfSchemaEdges = graph.edges.filterNot(e => inSchemaRel(e.relationship))

    val checked: List[(Edge, List[SchemaViolation])] = graph.edges.map { edge =>
      val respelled =
        if (schema.relationshipTypes.isEmpty) edge
        else schema.findRelationshipType(edge.relationship).fold(edge)(r => edge.copy(relationship = r.name))
      val endpoint = Seq(edge.source, edge.target).distinct.filterNot(keptNodes.contains).map { id =>
        SchemaViolation(
          s"Relationship ${edge.relationship} from '${edge.source}' to '${edge.target}' refers to node '$id', " +
            (if (graph.nodes.contains(id)) "which was dropped" else "which does not exist"),
          entityId = Some(id),
          violationType = "endpoint_missing"
        )
      }
      val typed =
        if (!inSchemaRel(edge.relationship)) Nil
        else endpointTypeViolations(edge, nodes)
      respelled -> (endpoint.toList ++ typed)
    }

    val finalEdges =
      if (strict) checked.collect { case (e, Nil) if inSchemaRel(e.relationship) => e }
      else checked.collect { case (e, vs) if vs.forall(_.violationType != "endpoint_missing") => e }

    ValidationResult(
      validGraph = Graph(keptNodes, finalEdges),
      outOfSchemaNodes = outOfSchemaNodes,
      outOfSchemaEdges = outOfSchemaEdges,
      violations = missingRequired ++ checked.flatMap(_._2)
    )
  }

  private def endpointTypeViolations(edge: Edge, nodes: Map[String, Node]): List[SchemaViolation] =
    schema.findRelationshipType(edge.relationship).toList.flatMap { relDef =>
      def check(types: Seq[String], id: String, end: String, violationType: String): Option[SchemaViolation] =
        if (types.isEmpty) None
        else
          nodes.get(id).filterNot(n => types.exists(_.equalsIgnoreCase(n.label))).map { node =>
            SchemaViolation(
              s"Relationship ${edge.relationship} expects $end types [${types.mkString(", ")}] but found " +
                s"'${node.label}' on node '$id'",
              entityId = Some(id),
              violationType = violationType
            )
          }
      List(
        check(relDef.sourceTypes, edge.source, "source", "invalid_source_type"),
        check(relDef.targetTypes, edge.target, "target", "invalid_target_type")
      ).flatten
    }
}
