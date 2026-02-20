package org.llm4s.knowledgegraph.extraction

import org.llm4s.knowledgegraph.{ Edge, Graph }

/**
 * Validates an extracted graph against an ExtractionSchema.
 *
 * Separates nodes and edges into conforming (valid) and non-conforming (out-of-schema)
 * sets, and reports specific constraint violations such as relationship endpoint type
 * mismatches.
 *
 * @param schema The extraction schema to validate against
 */
class SchemaValidator(schema: ExtractionSchema) {

  /**
   * Validates the given graph against the schema.
   *
   * Nodes are checked against `schema.entityTypes` by label. Edges are checked
   * against `schema.relationshipTypes` by relationship name, and optionally against
   * source/target type constraints. Out-of-schema items are collected but may be
   * retained in the final graph depending on `schema.allowOutOfSchema`.
   *
   * @param graph The graph to validate
   * @return A ValidationResult with valid/invalid items separated
   */
  def validate(graph: Graph): ValidationResult = {
    val schemaEntityNames = schema.entityTypeNames.map(_.toLowerCase).toSet
    val schemaRelNames    = schema.relationshipTypeNames.map(_.toLowerCase).toSet

    // Partition nodes
    val (validNodePairs, outOfSchemaNodePairs) = graph.nodes.partition { case (_, node) =>
      schemaEntityNames.contains(node.label.toLowerCase)
    }

    val outOfSchemaNodes = outOfSchemaNodePairs.values.toList

    // Partition edges
    val (validEdges, outOfSchemaEdges, violations) = partitionEdges(graph, schemaRelNames)

    // Build the valid graph: include out-of-schema items if allowed
    val finalNodes = if (schema.allowOutOfSchema) graph.nodes else validNodePairs
    val finalEdges = if (schema.allowOutOfSchema) {
      // Keep all edges, but still report violations
      graph.edges
    } else {
      validEdges
    }

    // When not allowing out-of-schema, filter edges that reference dropped nodes
    val filteredEdges = if (schema.allowOutOfSchema) {
      finalEdges
    } else {
      finalEdges.filter(e => finalNodes.contains(e.source) && finalNodes.contains(e.target))
    }

    ValidationResult(
      validGraph = Graph(finalNodes, filteredEdges),
      outOfSchemaNodes = outOfSchemaNodes,
      outOfSchemaEdges = outOfSchemaEdges,
      violations = violations
    )
  }

  private def partitionEdges(
    graph: Graph,
    schemaRelNames: Set[String]
  ): (List[Edge], List[Edge], List[SchemaViolation]) = {
    case class Acc(valid: List[Edge], outOfSchema: List[Edge], violations: List[SchemaViolation])

    val result = graph.edges.foldLeft(Acc(Nil, Nil, Nil)) { (acc, edge) =>
      if (!schemaRelNames.contains(edge.relationship.toLowerCase)) {
        acc.copy(outOfSchema = acc.outOfSchema :+ edge)
      } else {
        // Check source/target type constraints for in-schema edges
        val newViolations = schema.findRelationshipType(edge.relationship).fold(List.empty[SchemaViolation]) { relDef =>
          val sourceViolation = if (relDef.sourceTypes.nonEmpty) {
            graph.nodes.get(edge.source).flatMap { node =>
              if (!relDef.sourceTypes.exists(_.equalsIgnoreCase(node.label)))
                Some(
                  SchemaViolation(
                    s"Relationship ${edge.relationship} expects source types " +
                      s"[${relDef.sourceTypes.mkString(", ")}] but found '${node.label}' " +
                      s"on node '${edge.source}'",
                    entityId = Some(edge.source),
                    violationType = "invalid_source_type"
                  )
                )
              else None
            }
          } else None

          val targetViolation = if (relDef.targetTypes.nonEmpty) {
            graph.nodes.get(edge.target).flatMap { node =>
              if (!relDef.targetTypes.exists(_.equalsIgnoreCase(node.label)))
                Some(
                  SchemaViolation(
                    s"Relationship ${edge.relationship} expects target types " +
                      s"[${relDef.targetTypes.mkString(", ")}] but found '${node.label}' " +
                      s"on node '${edge.target}'",
                    entityId = Some(edge.target),
                    violationType = "invalid_target_type"
                  )
                )
              else None
            }
          } else None

          List(sourceViolation, targetViolation).flatten
        }

        acc.copy(valid = acc.valid :+ edge, violations = acc.violations ++ newViolations)
      }
    }

    (result.valid, result.outOfSchema, result.violations)
  }
}
