package org.llm4s.vectorstore.redis

import org.llm4s.vectorstore.MetadataFilter
import org.llm4s.vectorstore.MetadataFilter.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RedisQueriesSpec extends AnyFlatSpec with Matchers {

  import RedisQueries.*
  import RedisQueries.Query.{ Everything, Expr, Nothing }

  private val lang = "k_6c_61_6e_67"

  "The tag encoding" should "spell every UTF-8 byte as _xx, a key after k and a value after x" in {
    keyTag("lang") shouldBe lang
    keyTag("") shouldBe "k"
    entryTag("lang", "en") shouldBe s"${lang}x_65_6e"
    entryTag("lang", "") shouldBe s"${lang}x"
    entryTag("é", "A, b") shouldBe "k_c3_a9x_41_2c_20_62"
  }

  it should "never produce the separator, a space, an upper-case letter or a query metacharacter" in {
    val tag = entryTag("Key with {braces}, | and 'quotes'", "-Value* ?\\\" ü")
    tag.forall(c => c == '_' || c == 'k' || c == 'x' || "0123456789abcdef".contains(c)) shouldBe true
  }

  "translate" should "turn All into everything" in {
    translate(All) shouldBe Everything
    translate(None) shouldBe Everything
    text(Everything) shouldBe Some("*")
  }

  it should "turn Equals into one entry tag" in {
    translate(Equals("lang", "en")) shouldBe Expr(s"@meta_tags:{${lang}x_65_6e}")
  }

  it should "turn In into a union of entry tags, and an empty In into nothing" in {
    translate(In("lang", Set("fr", "en"))) shouldBe Expr(s"@meta_tags:{${lang}x_65_6e | ${lang}x_66_72}")
    translate(In("lang", Set.empty)) shouldBe Nothing
    text(Nothing) shouldBe None
  }

  it should "turn HasKey into a key tag" in {
    translate(HasKey("lang")) shouldBe Expr(s"@meta_keys:{$lang}")
  }

  it should "turn Contains into a wildcard over the key's entries" in {
    translate(Contains("lang", "n")) shouldBe Expr(s"@meta_tags:{w'${lang}x*_6e*'}")
    translate(Contains("lang", "")) shouldBe Expr(s"@meta_tags:{w'${lang}x*'}")
  }

  it should "combine with And, Or and Not" in {
    val a  = Equals("lang", "en")
    val b  = HasKey("lang")
    val ta = s"@meta_tags:{${lang}x_65_6e}"
    val tb = s"@meta_keys:{$lang}"
    translate(And(a, b)) shouldBe Expr(s"($ta) ($tb)")
    translate(Or(a, b)) shouldBe Expr(s"($ta) | ($tb)")
    translate(Not(a)) shouldBe Expr(s"-($ta)")
    translate(a.and(!b).or(b)) shouldBe Expr(s"(($ta) (-($tb))) | ($tb)")
    text(translate(Not(a))) shouldBe Some(s"-($ta)")
  }

  it should "simplify around everything and nothing" in {
    val a     = Equals("lang", "en")
    val ta    = translate(a)
    val empty = In("lang", Set.empty)
    translate(And(All, a)) shouldBe ta
    translate(And(a, All)) shouldBe ta
    translate(And(a, empty)) shouldBe Nothing
    translate(And(empty, a)) shouldBe Nothing
    translate(Or(a, All)) shouldBe Everything
    translate(Or(All, a)) shouldBe Everything
    translate(Or(empty, a)) shouldBe ta
    translate(Or(a, empty)) shouldBe ta
    translate(Not(All)) shouldBe Nothing
    translate(Not(empty)) shouldBe Everything
  }

  "vectorBytes" should "write little-endian FLOAT32 and read back" in {
    val bytes = vectorBytes(Array(1f, -2.5f))
    bytes shouldBe Array[Byte](0, 0, -128, 63, 0, 0, 32, -64)
    vectorFrom(bytes) shouldBe Array(1f, -2.5f)
    vectorFrom(bytes :+ 7.toByte) shouldBe Array(1f, -2.5f)
  }

  "metadataJson" should "write a sorted JSON object and read it back" in {
    val meta = Map("b" -> "2", "a" -> "x\"y")
    metadataJson(meta) shouldBe """{"a":"x\"y","b":"2"}"""
    metadataFrom(metadataJson(meta)) shouldBe meta
    metadataFrom("""{"n":1,"z":null,"o":{"p":true}}""") shouldBe Map("n" -> "1", "z" -> "", "o" -> """{"p":true}""")
    metadataFrom("3") shouldBe Map.empty
  }

  "globLiteral" should "escape glob metacharacters" in {
    globLiteral("a*b?c[d]e\\f") shouldBe "a\\*b\\?c\\[d\\]e\\\\f"
    globLiteral("plain:1") shouldBe "plain:1"
  }

  "isIndexMissing" should "recognise each server's wording" in {
    RedisVectorStore.isIndexMissing("Unknown Index name") shouldBe true
    RedisVectorStore.isIndexMissing("llm4s-vectors: no such index") shouldBe true
    RedisVectorStore.isIndexMissing("Index with name 'x' not found in database 0") shouldBe true
    RedisVectorStore.isIndexMissing("Syntax error") shouldBe false
  }

  "dimensionIn" should "find the size in Redis's and Valkey's FT.INFO replies" in {
    RedisVectorStore.dimensionIn(FakeRedis.info(384)) shouldBe Some(384)
    val valkey = RedisReply.Arr(
      Seq(
        RedisReply.str("attributes"),
        RedisReply.Arr(
          Seq(
            RedisReply.Arr(
              Seq(
                RedisReply.str("identifier"),
                RedisReply.str("embedding"),
                RedisReply.str("index"),
                RedisReply.Arr(Seq("capacity", "10240", "dimensions", "768").map(RedisReply.str))
              )
            )
          )
        )
      )
    )
    RedisVectorStore.dimensionIn(valkey) shouldBe Some(768)
    RedisVectorStore.dimensionIn(RedisReply.Arr(Seq("dim", "many").map(RedisReply.str))) shouldBe None
    RedisVectorStore.dimensionIn(RedisReply.Ok) shouldBe None
  }

  "RedisReply" should "read as text where it has one" in {
    RedisReply.str("a").text shouldBe Some("a")
    RedisReply.Integer(3).text shouldBe Some("3")
    RedisReply.Nil.text shouldBe None
    RedisReply.Error("e").text shouldBe None
    RedisReply.Arr(Seq.empty).text shouldBe None
  }

  "The distance metric and algorithm" should "parse their names" in {
    RedisDistanceMetric.fromString(" cosine ") shouldBe Some(RedisDistanceMetric.Cosine)
    RedisDistanceMetric.fromString("ip") shouldBe Some(RedisDistanceMetric.InnerProduct)
    RedisDistanceMetric.fromString("InnerProduct") shouldBe Some(RedisDistanceMetric.InnerProduct)
    RedisDistanceMetric.fromString("L2") shouldBe Some(RedisDistanceMetric.L2)
    RedisDistanceMetric.fromString("manhattan") shouldBe None
    RedisVectorAlgorithm.fromString("flat") shouldBe Some(RedisVectorAlgorithm.Flat)
    RedisVectorAlgorithm.fromString("HNSW") shouldBe Some(RedisVectorAlgorithm.Hnsw)
    RedisVectorAlgorithm.fromString("ivf") shouldBe None
  }

  "MetadataFilter" should "be covered case by case" in {
    // A guard: every case of the sealed trait has a translation test above.
    val cases: Seq[MetadataFilter] =
      Seq(
        All,
        Equals("a", "b"),
        Contains("a", "b"),
        HasKey("a"),
        In("a", Set("b")),
        And(All, All),
        Or(All, All),
        Not(All)
      )
    cases.map(translate) should have size 8
  }
}
