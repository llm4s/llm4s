package org.llm4s.scalafix

import org.scalatest.funsuite.AnyFunSuiteLike
import scalafix.testkit.AbstractSemanticRuleSuite

/**
 * Runs each rule named in the header of a `modules/scalafix-rules/input-1.0` file on that file (compiled against llm4s
 * 1.0.0) and compares the result with the file of the same name in `modules/scalafix-rules/output` (compiled against
 * this checkout), and each lint with the input's `// assert:` comments.
 */
class MigrationRuleSuite10 extends AbstractSemanticRuleSuite with AnyFunSuiteLike {
  runAllTests()
}
