package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.model.InputPurpose
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CacheKeyGeneratorSpec extends AnyFlatSpec with Matchers {

  "CacheKeyGenerator.sha256" should "generate consistent keys" in {
    val key1 = CacheKeyGenerator.sha256("hello", "model-v1")
    val key2 = CacheKeyGenerator.sha256("hello", "model-v1")
    key1 should be(key2)
  }

  it should "generate different keys for different inputs" in {
    val key1 = CacheKeyGenerator.sha256("hello", "model-v1")
    val key2 = CacheKeyGenerator.sha256("world", "model-v1")
    key1 should not be key2
  }

  it should "produce 64-character hex strings" in {
    val key = CacheKeyGenerator.sha256("test", "model")
    key.length should be(64)
    (key should fullyMatch).regex("[0-9a-f]{64}".r)
  }

  it should "handle unicode characters safely" in {
    val key = CacheKeyGenerator.sha256("你好世界", "model-v1")
    key.length should be(64)
    (key should fullyMatch).regex("[0-9a-f]{64}".r)
  }

  it should "encode its parts unambiguously, whatever characters they contain" in {
    CacheKeyGenerator.sha256("a:b", "c") should not be CacheKeyGenerator.sha256("a", "b:c")
    CacheKeyGenerator.sha256("ab", "c") should not be CacheKeyGenerator.sha256("a", "bc")
    CacheKeyGenerator.sha256("1:a", "") should not be CacheKeyGenerator.sha256("a")
    CacheKeyGenerator.sha256("a", "b", "c") should not be CacheKeyGenerator.sha256("a", "bc")
    CacheKeyGenerator.sha256("x#query", "m") should not be CacheKeyGenerator.sha256("x", "m#query")
  }

  it should "preserve isolated UTF-16 surrogates instead of replacing them" in {
    val strings = Seq(0xd800.toChar.toString, 0xd801.toChar.toString, 0xdc00.toChar.toString, "?")
    strings.map(CacheKeyGenerator.sha256(_)).distinct should have size strings.size
    strings.map(s => CacheKeyGenerator.embeddingKey(s, "m", InputPurpose.Query)).distinct should have size strings.size
  }

  "CacheKeyGenerator.embeddingKey" should "differ by purpose" in {
    CacheKeyGenerator.embeddingKey("t", "m", InputPurpose.Query) should not be
      CacheKeyGenerator.embeddingKey("t", "m", InputPurpose.Document)
  }

  it should "not let a model name stand in for a purpose" in {
    CacheKeyGenerator.embeddingKey("t", "m", InputPurpose.Query) should not be
      CacheKeyGenerator.embeddingKey("t", "m#query", InputPurpose.Document)
    CacheKeyGenerator.embeddingKey("t", "m", InputPurpose.Query) should not be
      CacheKeyGenerator.embeddingKey("t", "mquery", InputPurpose.Document)
  }

  it should "not let a text and a model name trade characters" in {
    CacheKeyGenerator.embeddingKey("a:b", "c", InputPurpose.Document) should not be
      CacheKeyGenerator.embeddingKey("a", "b:c", InputPurpose.Document)
  }
}
