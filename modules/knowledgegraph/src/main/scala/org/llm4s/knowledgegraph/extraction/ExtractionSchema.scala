package org.llm4s.knowledgegraph.extraction

/**
 * Defines a property expected on an entity type.
 *
 * @param name The property name (e.g., "role", "department")
 * @param description Optional description used as a hint for the LLM
 * @param required Whether this property must be present on extracted entities of the parent type
 */
case class PropertyDefinition(
  name: String,
  description: String = "",
  required: Boolean = false
)

/**
 * Defines an entity type expected in the extraction schema.
 *
 * @param name The entity type name (e.g., "Person", "Organization")
 * @param description Optional description used as a hint for the LLM
 * @param properties Properties expected on entities of this type
 */
case class EntityTypeDefinition(
  name: String,
  description: String = "",
  properties: Seq[PropertyDefinition] = Seq.empty
)

/**
 * Defines a relationship type expected in the extraction schema.
 *
 * @param name The relationship type name (e.g., "WORKS_FOR", "LOCATED_IN")
 * @param description Optional description used as a hint for the LLM
 * @param sourceTypes Valid source entity types for this relationship (empty means any)
 * @param targetTypes Valid target entity types for this relationship (empty means any)
 */
case class RelationshipTypeDefinition(
  name: String,
  description: String = "",
  sourceTypes: Seq[String] = Seq.empty,
  targetTypes: Seq[String] = Seq.empty
)

/**
 * Schema for guided knowledge graph extraction.
 *
 * When provided to an extractor, the LLM prompt is constrained to these types
 * and extracted results are validated against the schema. If `allowOutOfSchema`
 * is true, entities and relationships outside the schema are preserved but flagged.
 *
 * @param entityTypes Expected entity type definitions
 * @param relationshipTypes Expected relationship type definitions
 * @param allowOutOfSchema If true, out-of-schema entities/relationships are kept; if false, they are dropped
 */
case class ExtractionSchema(
  entityTypes: Seq[EntityTypeDefinition],
  relationshipTypes: Seq[RelationshipTypeDefinition],
  allowOutOfSchema: Boolean = true
) {

  /** All entity type names defined in this schema. */
  def entityTypeNames: Seq[String] = entityTypes.map(_.name)

  /** All relationship type names defined in this schema. */
  def relationshipTypeNames: Seq[String] = relationshipTypes.map(_.name)

  /** Looks up an entity type definition by name (case-insensitive). */
  def findEntityType(name: String): Option[EntityTypeDefinition] =
    entityTypes.find(_.name.equalsIgnoreCase(name))

  /** Looks up a relationship type definition by name (case-insensitive). */
  def findRelationshipType(name: String): Option[RelationshipTypeDefinition] =
    relationshipTypes.find(_.name.equalsIgnoreCase(name))
}

object ExtractionSchema {

  /**
   * Convenience factory matching the simplified API proposed in issue #652.
   *
   * {{{
   * val schema = ExtractionSchema.simple(
   *   entityTypes = Seq("Person", "Organization", "Technology"),
   *   relationshipTypes = Seq("WORKS_FOR", "FOUNDED", "USES"),
   *   properties = Map("Person" -> Seq("role", "department"))
   * )
   * }}}
   *
   * @param entityTypes Entity type names
   * @param relationshipTypes Relationship type names
   * @param properties Map of entity type name to its expected property names
   * @param allowOutOfSchema If true, out-of-schema entities/relationships are kept
   * @return An ExtractionSchema with definitions built from the simple inputs
   */
  def simple(
    entityTypes: Seq[String],
    relationshipTypes: Seq[String],
    properties: Map[String, Seq[String]] = Map.empty,
    allowOutOfSchema: Boolean = true
  ): ExtractionSchema = {
    val entityDefs = entityTypes.map { name =>
      val propDefs = properties.getOrElse(name, Seq.empty).map(p => PropertyDefinition(p))
      EntityTypeDefinition(name, properties = propDefs)
    }

    val relDefs = relationshipTypes.map(name => RelationshipTypeDefinition(name))

    ExtractionSchema(entityDefs, relDefs, allowOutOfSchema)
  }
}
