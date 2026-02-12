addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.1")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"        % "2.5.4")
// Maven deployment
addSbtPlugin("com.github.sbt" % "sbt-pgp"        % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.0")
addSbtPlugin("com.github.sbt" % "sbt-dynver"     % "5.1.1")
// Cross-compilation support
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")
// Dependency updates
addSbtPlugin("org.jmotor.sbt" % "sbt-dependency-updates" % "1.2.9")

// Test coverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.12")

// Scalafix for linting and rewrites
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")
