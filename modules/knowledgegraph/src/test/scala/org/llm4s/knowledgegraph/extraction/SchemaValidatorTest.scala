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

  // ─── #2971 ────────────────────────────────────────────────────────────────────────────────

  private val typedRel =
    RelationshipTypeDefinition("WORKS_FOR", sourceTypes = Seq("Person"), targetTypes = Seq("Organization"))

  private def typedSchema(allowOutOfSchema: Boolean, personProps: Seq[PropertyDefinition] = Nil) = ExtractionSchema(
    Seq(EntityTypeDefinition("Person", properties = personProps), EntityTypeDefinition("Organization")),
    Seq(typedRel),
    allowOutOfSchema
  )

  test("strict mode drops an edge whose endpoints break its types; lenient mode keeps and reports it (#2971)") {
    val graph = Graph(
      Map("alice" -> Node("alice", "Person"), "acme" -> Node("acme", "Organization")),
      List(Edge("acme", "alice", "WORKS_FOR"), Edge("alice", "acme", "WORKS_FOR"))
    )
    val strict = new SchemaValidator(typedSchema(allowOutOfSchema = false)).validate(graph)
    strict.validGraph.edges shouldBe List(Edge("alice", "acme", "WORKS_FOR"))
    strict.violations.map(_.violationType) shouldBe List("invalid_source_type", "invalid_target_type")
    val lenient = new SchemaValidator(typedSchema(allowOutOfSchema = true)).validate(graph)
    lenient.validGraph.edges should have size 2
    lenient.violations should have size 2
  }

  test("a missing required property is a violation, and strict mode drops the node and its edges (#2971)") {
    val schema = (strict: Boolean) => typedSchema(!strict, Seq(PropertyDefinition("role", required = true)))
    val graph = Graph(
      Map(
        "alice" -> Node("alice", "Person", Map("Role" -> ujson.Str("CEO"))),
        "bob"   -> Node("bob", "Person", Map("role" -> ujson.Null)),
        "acme"  -> Node("acme", "Organization")
      ),
      List(Edge("alice", "acme", "WORKS_FOR"), Edge("bob", "acme", "WORKS_FOR"))
    )
    val strict = new SchemaValidator(schema(true)).validate(graph)
    strict.validGraph.nodes.keySet shouldBe Set("alice", "acme")
    strict.validGraph.edges shouldBe List(Edge("alice", "acme", "WORKS_FOR"))
    strict.violations.map(v => (v.violationType, v.entityId)) shouldBe List(
      ("missing_required_property", Some("bob")),
      ("endpoint_missing", Some("bob"))
    )
    val lenient = new SchemaValidator(schema(false)).validate(graph)
    lenient.validGraph.nodes.keySet shouldBe Set("alice", "bob", "acme")
    lenient.violations.map(_.violationType) shouldBe List("missing_required_property")
  }

  test("labels and relationships are respelled as the schema spells them (#2971)") {
    val graph = Graph(
      Map("a" -> Node("a", "person"), "b" -> Node("b", "PERSON"), "c" -> Node("c", "organization")),
      List(Edge("a", "c", "works_for"))
    )
    val result = new SchemaValidator(typedSchema(allowOutOfSchema = false)).validate(graph)
    result.validGraph.nodes.values.map(_.label).toSet shouldBe Set("Person", "Organization")
    result.validGraph.findNodesByLabel("Person").map(_.id).toSet shouldBe Set("a", "b")
    result.validGraph.edges shouldBe List(Edge("a", "c", "WORKS_FOR"))
    result.isFullyValid shouldBe true
  }

  test("an edge to a dropped node is reported as endpoint_missing (#2971)") {
    val graph = Graph(
      Map("alice" -> Node("alice", "Person"), "paris" -> Node("paris", "Location")),
      List(Edge("alice", "paris", "WORKS_FOR"))
    )
    val result = new SchemaValidator(
      ExtractionSchema(Seq(EntityTypeDefinition("Person")), Seq(RelationshipTypeDefinition("WORKS_FOR")), false)
    ).validate(graph)
    result.validGraph.edges shouldBe empty
    result.outOfSchemaNodes.map(_.id) shouldBe List("paris")
    result.violations.map(v => (v.violationType, v.entityId)) shouldBe List(("endpoint_missing", Some("paris")))
  }

  test("empty entityTypes and relationshipTypes admit anything, as empty sourceTypes does (#2971)") {
    val graph  = Graph(Map("a" -> Node("a", "Thing"), "b" -> Node("b", "Other")), List(Edge("a", "b", "REL")))
    val result = new SchemaValidator(ExtractionSchema(Nil, Nil, allowOutOfSchema = false)).validate(graph)
    result.validGraph shouldBe graph
    result.isFullyValid shouldBe true
  }

  test("ExtractionSchema.simple looks up properties by type name ignoring case (#2971)") {
    val schema = ExtractionSchema.simple(Seq("Person"), Nil, properties = Map("person" -> Seq("role")))
    schema.findEntityType("Person").map(_.properties.map(_.name)) shouldBe Some(Seq("role"))
  }
}
