package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UrlLoaderSpec extends AnyFlatSpec with Matchers {

  "UrlLoader" should "reject blocked URLs before making requests" in {
    val loader = UrlLoader("http://169.254.169.254/latest/meta-data/")
    val result = loader.load().next()

    result shouldBe a[LoadResult.Failure]

    result match {
      case LoadResult.Failure(_, error, _) =>
        error.message should include("blocked range")
      case _ => fail("Expected a failure for blocked URL")
    }
  }
}
