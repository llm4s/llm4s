package org.llm4s.samples.cookbook

import org.llm4s.llmconnect.model.MessageRole
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the folder RAG recipe over a temporary folder, with the bag-of-words embeddings and the scripted client. */
class FolderQaRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  "FolderQaRecipe.answer" should "answer from the file that matches the question and name it as a source" in {
    val folder = FolderQaRecipe.writeHandbook().value

    val result = FolderQaRecipe.answer(FolderQaRecipe.script, folder, "How many days of paid vacation do I get?").value

    result.answer should include("25 days of paid vacation")
    result.contexts.head.metadata.get("source") shouldBe Some("vacation.txt")
  }

  it should "ingest every file of the folder" in {
    val folder = FolderQaRecipe.writeHandbook().value

    val result = FolderQaRecipe.answer(FolderQaRecipe.script, folder, "When must expense claims be filed?").value

    result.contexts.head.metadata.get("source") shouldBe Some("expenses.txt")
    result.answer should include("receipt")
  }

  it should "put only the retrieved passages in the prompt, not the whole folder" in {
    val folder = FolderQaRecipe.writeHandbook().value
    val client = FolderQaRecipe.script

    FolderQaRecipe.answer(client, folder, "How many days of paid vacation do I get?").value

    client.calls should have size 1
    val prompt = client.calls.head._1.messages.filter(_.role == MessageRole.User).map(_.content).mkString
    prompt should include("25 days")
    prompt.linesIterator.count(_.matches("\\[\\d+\\] .*")) shouldBe 2 // topK = 2 of the 3 files
  }

  it should "fail with a Left, not an exception, for a folder that does not exist" in {
    val missing = java.nio.file.Paths.get(sys.props("java.io.tmpdir"), "no-such-cookbook-folder", "x")

    FolderQaRecipe.answer(FolderQaRecipe.script, missing, "anything").isLeft shouldBe true
  }

  "BagOfWordsEmbeddings" should "give the same text the same vector, of length 1, and texts sharing words closer ones" in {
    def cosine(a: Seq[Double], b: Seq[Double]) = a.zip(b).map(_ * _).sum
    val vacation                               = BagOfWordsEmbeddings.vector("paid vacation days")

    vacation shouldBe BagOfWordsEmbeddings.vector("Paid vacation, days!")
    cosine(vacation, vacation) shouldBe 1.0 +- 1e-9
    cosine(vacation, BagOfWordsEmbeddings.vector("vacation days carry over")) should be >
      cosine(vacation, BagOfWordsEmbeddings.vector("expense receipts"))
    BagOfWordsEmbeddings.vector("...").forall(_ == 0.0) shouldBe true
  }
}
