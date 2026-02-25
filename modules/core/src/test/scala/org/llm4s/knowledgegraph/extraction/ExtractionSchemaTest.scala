package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ExtractionSchemaTest extends AnyFunSuite with Matchers {

  test("ExtractionSchema should store entity and relationship type definitions") {
    val schema = ExtractionSchema(
      entityTypes = Seq(
        EntityTypeDefinition("Person", "A human being", Seq(PropertyDefinition("role", "Job role", required = true))),
        EntityTypeDefinition("Organization")
      ),
      relationshipTypes = Seq(
        RelationshipTypeDefinition("WORKS_FOR", "Employment relationship", Seq("Person"), Seq("Organization"))
      )
    )

    schema.entityTypes should have size 2
    schema.relationshipTypes should have size 1
    schema.entityTypeNames should contain theSameElementsAs Seq("Person", "Organization")
    schema.relationshipTypeNames should contain theSameElementsAs Seq("WORKS_FOR")
  }

  test("ExtractionSchema should find entity type by name case-insensitively") {
    val schema = ExtractionSchema(
      entityTypes = Seq(EntityTypeDefinition("Person")),
      relationshipTypes = Seq.empty
    )

    schema.findEntityType("person") shouldBe defined
    schema.findEntityType("Person") shouldBe defined
    schema.findEntityType("PERSON") shouldBe defined
    schema.findEntityType("Unknown") shouldBe None
  }

  test("ExtractionSchema should find relationship type by name case-insensitively") {
    val schema = ExtractionSchema(
      entityTypes = Seq.empty,
      relationshipTypes = Seq(RelationshipTypeDefinition("WORKS_FOR"))
    )

    schema.findRelationshipType("works_for") shouldBe defined
    schema.findRelationshipType("WORKS_FOR") shouldBe defined
    schema.findRelationshipType("UNKNOWN") shouldBe None
  }

  test("ExtractionSchema.simple should build schema from string lists") {
    val schema = ExtractionSchema.simple(
      entityTypes = Seq("Person", "Organization", "Technology"),
      relationshipTypes = Seq("WORKS_FOR", "FOUNDED", "USES"),
      properties = Map("Person" -> Seq("role", "department"))
    )

    schema.entityTypes should have size 3
    schema.relationshipTypes should have size 3

    val personDef = schema.findEntityType("Person")
    personDef shouldBe defined
    personDef.get.properties should have size 2
    personDef.get.properties.map(_.name) should contain theSameElementsAs Seq("role", "department")
  }

  test("ExtractionSchema.simple should handle empty properties map") {
    val schema = ExtractionSchema.simple(
      entityTypes = Seq("Person"),
      relationshipTypes = Seq("KNOWS")
    )

    schema.entityTypes.head.properties shouldBe empty
  }

  test("ExtractionSchema should default allowOutOfSchema to true") {
    val schema = ExtractionSchema(Seq.empty, Seq.empty)
    schema.allowOutOfSchema shouldBe true
  }

  test("ExtractionSchema.simple should pass through allowOutOfSchema") {
    val strict = ExtractionSchema.simple(
      entityTypes = Seq("Person"),
      relationshipTypes = Seq("KNOWS"),
      allowOutOfSchema = false
    )
    strict.allowOutOfSchema shouldBe false
  }

  test("PropertyDefinition should store required flag and description") {
    val prop = PropertyDefinition("role", "Job role", required = true)
    prop.name shouldBe "role"
    prop.description shouldBe "Job role"
    prop.required shouldBe true
  }

  test("PropertyDefinition should default to not required with empty description") {
    val prop = PropertyDefinition("name")
    prop.required shouldBe false
    prop.description shouldBe ""
  }

  test("RelationshipTypeDefinition should store source and target type constraints") {
    val relDef = RelationshipTypeDefinition(
      "WORKS_FOR",
      sourceTypes = Seq("Person"),
      targetTypes = Seq("Organization", "Company")
    )

    relDef.sourceTypes should contain("Person")
    relDef.targetTypes should have size 2
  }

  test("RelationshipTypeDefinition should default to empty source and target types") {
    val relDef = RelationshipTypeDefinition("KNOWS")
    relDef.sourceTypes shouldBe empty
    relDef.targetTypes shouldBe empty
  }
}
