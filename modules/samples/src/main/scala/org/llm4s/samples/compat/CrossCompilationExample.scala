package org.llm4s.samples.compat

import org.llm4s.compat.ScalaCompat
import org.slf4j.LoggerFactory

/**
 * A simple example demonstrating cross-compilation support
 */
object CrossCompilationExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Print the Scala version
    logger.info("Running on Scala {}", util.Properties.versionNumberString)

    // Use the compatibility layer
    logger.info("Is Scala 2.13: {}", ScalaCompat.isScala213)
    logger.info("Is Scala 3: {}", ScalaCompat.isScala3)

    // Use the version-dependent helper
    val versionText = ScalaCompat.onScala213(
      ifScala213 = "This is Scala 2.13 specific code",
      ifScala3 = "This is Scala 3 specific code"
    )

    logger.info("{}", versionText)

    // Demonstrate cross-compiled code selection
    if (ScalaCompat.isScala213) {
      logger.info("This branch only executes in Scala 2.13")
      // In a real application, here you would access Scala 2.13 specific APIs
    } else {
      logger.info("This branch only executes in Scala 3")
      // In a real application, here you would access Scala 3 specific APIs
      // For example, use Scala 3 union types, enums, extension methods, etc.
    }

    // The code can also pull in the right implementation automatically thanks to
    // the versioned source directories
    logger.info("Running version-specific code optimized for {}", util.Properties.versionNumberString)
  }
}
