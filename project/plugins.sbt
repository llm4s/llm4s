addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.1")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"        % "2.5.4")
// Maven deployment
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype"   % "3.12.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.3")
addSbtPlugin("com.github.sbt" % "sbt-dynver"     % "5.1.0")
// Cross-compilation support
addSbtPlugin("com.typesafe"   % "sbt-mima-plugin" % "1.1.3")
