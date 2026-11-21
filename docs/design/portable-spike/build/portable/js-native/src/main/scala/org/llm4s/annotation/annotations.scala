package org.llm4s.annotation

import scala.annotation.StaticAnnotation

// Scala.js and Scala Native compile no Java, so core's `@Stable` and `@Experimental` (Java annotations with
// RUNTIME retention, read by `sbt stabilityTierCheck` on the JVM) need Scala stand-ins here. A portable module
// would need the same pair, which is the one source change the inventory found the data types need.

/** Stand-in for core's Java `@Stable` on Scala.js and Scala Native. */
final class Stable extends StaticAnnotation

/** Stand-in for core's Java `@Experimental` on Scala.js and Scala Native. */
final class Experimental extends StaticAnnotation
