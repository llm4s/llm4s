package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }

class SchemaValidatorTest extends AnyFunSuite with Matchers {

  private val schema = ExtractionSchema(
    entityTypes = Seq(
      EntityTypeDefinition("Person"),
      EntityTypeDefinition("Organization")
    ),
    relationshipTypes = Seq(
      RelationshipTypeDefinition("WORKS_FOR", sourceTypes = Seq("Person"), targetTypes = Seq("Organization")),
      RelationshipTypeDefinition("KNOWS")
    ),
    allowOutOfSchema = true
  )

  test("SchemaValidator should accept fully conforming graph") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person", Map("name" -> ujson.Str("Alice"))),
        "2" -> Node("2", "Organization", Map("name" -> ujson.Str("Acme")))
      ),
      List(Edge("1", "2", "WORKS_FOR"))
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.isFullyValid shouldBe true
    result.outOfSchemaNodes shouldBe empty
    result.outOfSchemaEdges shouldBe empty
    result.violations shouldBe empty
    result.validGraph.nodes should have size 2
    result.validGraph.edges should have size 1
  }

  test("SchemaValidator should identify out-of-schema nodes") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person"),
        "2" -> Node("2", "Technology") // Not in schema
      ),
      List.empty
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.outOfSchemaNodes should have size 1
    result.outOfSchemaNodes.head.label shouldBe "Technology"
  }

  test("SchemaValidator should identify out-of-schema edges") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person"),
        "2" -> Node("2", "Person")
      ),
      List(Edge("1", "2", "FRIENDS_WITH")) // Not in schema
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.outOfSchemaEdges should have size 1
    result.outOfSchemaEdges.head.relationship shouldBe "FRIENDS_WITH"
  }

  test("SchemaValidator should keep out-of-schema items when allowOutOfSchema is true") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person"),
        "2" -> Node("2", "Technology")
      ),
      List(Edge("1", "2", "USES"))
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.validGraph.nodes should have size 2 // Both kept
    result.validGraph.edges should have size 1 // Edge kept
    result.outOfSchemaNodes should have size 1 // But flagged
  }

  test("SchemaValidator should drop out-of-schema items when allowOutOfSchema is false") {
    val strictSchema = schema.copy(allowOutOfSchema = false)
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person"),
        "2" -> Node("2", "Technology")
      ),
      List(Edge("1", "2", "USES"))
    )

    val validator = new SchemaValidator(strictSchema)
    val result    = validator.validate(graph)

    result.validGraph.nodes should have size 1 // Only Person kept
    result.validGraph.edges shouldBe empty     // Edge dropped (references dropped node)
  }

  test("SchemaValidator should report source type constraint violations") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "Organization"), // WORKS_FOR expects Person as source
        "2" -> Node("2", "Organization")
      ),
      List(Edge("1", "2", "WORKS_FOR"))
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.violations should have size 1
    result.violations.head.violationType shouldBe "invalid_source_type"
    result.violations.head.entityId shouldBe Some("1")
  }

  test("SchemaValidator should report target type constraint violations") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person"),
        "2" -> Node("2", "Person") // WORKS_FOR expects Organization as target
      ),
      List(Edge("1", "2", "WORKS_FOR"))
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.violations should have size 1
    result.violations.head.violationType shouldBe "invalid_target_type"
    result.violations.head.entityId shouldBe Some("2")
  }

  test("SchemaValidator should not report violations for unconstrained relationships") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person"),
        "2" -> Node("2", "Person")
      ),
      List(Edge("1", "2", "KNOWS")) // KNOWS has no source/target constraints
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.violations shouldBe empty
  }

  test("SchemaValidator should handle case-insensitive label matching") {
    val graph = Graph(
      Map(
        "1" -> Node("1", "person"),      // lowercase
        "2" -> Node("2", "ORGANIZATION") // uppercase
      ),
      List(Edge("1", "2", "works_for")) // lowercase
    )

    val validator = new SchemaValidator(schema)
    val result    = validator.validate(graph)

    result.outOfSchemaNodes shouldBe empty
    result.outOfSchemaEdges shouldBe empty
  }

  test("SchemaValidator should handle empty graph") {
    val validator = new SchemaValidator(schema)
    val result    = validator.validate(Graph.empty)

    result.isFullyValid shouldBe true
    result.validGraph shouldBe Graph.empty
  }

  test("SchemaValidator should drop edges referencing dropped nodes in strict mode") {
    val strictSchema = schema.copy(allowOutOfSchema = false)
    val graph = Graph(
      Map(
        "1" -> Node("1", "Person"),
        "2" -> Node("2", "Technology"), // Will be dropped
        "3" -> Node("3", "Organization")
      ),
      List(
        Edge("1", "2", "USES"),     // References dropped node, out-of-schema rel
        Edge("1", "3", "WORKS_FOR") // Valid
      )
    )

    val validator = new SchemaValidator(strictSchema)
    val result    = validator.validate(graph)

    result.validGraph.nodes should have size 2 // Person + Organization
    result.validGraph.edges should have size 1 // Only WORKS_FOR
    result.validGraph.edges.head.relationship shouldBe "WORKS_FOR"
  }
}
