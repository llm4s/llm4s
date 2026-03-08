package org.llm4s.samples.compat

import org.llm4s.compat.ScalaCompat
import org.slf4j.LoggerFactory

/** A simple example demonstrating the Scala 3 runtime environment. */
object CrossCompilationExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Print the Scala version
    logger.info("Running on Scala {}", util.Properties.versionNumberString)

    logger.info("Is Scala 3: {}", ScalaCompat.isScala3)

    val versionText = ScalaCompat.onScala213(
      ifScala213 = "Legacy Scala 2.13 branch",
      ifScala3 = "This is the Scala 3-only build"
    )

    logger.info("{}", versionText)

    logger.info("This branch executes in Scala 3")
    logger.info("Running Scala 3-only code optimized for {}", util.Properties.versionNumberString)
  }
}
