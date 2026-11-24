package org.llm4s.testutil

/**
 * Methods with Scaladoc, for a spec in another module that reads documentation from the TASTy of a class
 * compiled in an earlier run (`DerivedToolsSpec` in `llm4s-schema-derivation`). The wording is asserted there.
 */
object DocumentedFixture {

  /**
   * Check if a word is a palindrome.
   *
   * Case is ignored.
   *
   * @param word The word to check
   * @return True when the word reads the same in both directions
   */
  def isPalindrome(word: String): Boolean = {
    val lower = word.toLowerCase
    lower == lower.reverse
  }
}
