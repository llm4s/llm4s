package org.llm4s.scalafix

import org.scalatest.funsuite.AnyFunSuiteLike
import scalafix.testkit.AbstractSemanticRuleSuite

/**
 * Runs each rule named in the header of a `modules/scalafix-rules/input-0.5` file on that file (compiled against llm4s
 * 0.5.1) and compares the result with the file of the same name in `modules/scalafix-rules/output` (compiled against
 * this checkout), and each lint with the input's `// assert:` comments.
 */
class MigrationRuleSuite05 extends AbstractSemanticRuleSuite with AnyFunSuiteLike {
  runAllTests()
}
