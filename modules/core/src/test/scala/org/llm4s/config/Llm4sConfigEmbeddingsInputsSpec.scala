package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.embeddingsInputs:
 * ensure it mirrors EmbeddingConfig.inputPath/inputPaths/query via the configured precedence.
 */
class Llm4sConfigEmbeddingsInputsSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

  "Llm4sConfig.embeddingsInputs" should {
    "return None values when no input/query config is provided" in {
      val props = Map.empty[String, String]

      withProps(props) {
        val inputs = Llm4sConfig.embeddingsInputs().fold(err => fail(err.toString), identity)
        inputs.inputPath shouldBe None
        inputs.inputPaths shouldBe None
        inputs.query shouldBe None
      }
    }

    "respect llm4s.embeddings.inputPath and llm4s.embeddings.query" in {
      val props = Map(
        "llm4s.embeddings.inputPath" -> "/single/path.txt",
        "llm4s.embeddings.query"     -> "test query"
      )

      withProps(props) {
        val inputs = Llm4sConfig.embeddingsInputs().fold(err => fail(err.toString), identity)
        inputs.inputPath shouldBe Some("/single/path.txt")
        inputs.inputPaths shouldBe None
        inputs.query shouldBe Some("test query")
      }
    }

    "respect llm4s.embeddings.inputPaths when provided directly" in {
      val props = Map(
        "llm4s.embeddings.inputPaths" -> "/a.txt,/b.txt"
      )

      withProps(props) {
        val inputs = Llm4sConfig.embeddingsInputs().fold(err => fail(err.toString), identity)
        inputs.inputPaths shouldBe Some("/a.txt,/b.txt")
      }
    }
  }
}
