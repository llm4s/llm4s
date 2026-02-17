package org.llm4s.llmconnect.caching

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
}
