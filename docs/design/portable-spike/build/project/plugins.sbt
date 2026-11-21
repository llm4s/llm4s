// The portable spike's own build (#2338). It is not part of the llm4s build: these plugins never reach the root
// project, so no published module's build, publishing or CI changes.
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.22.0")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.12")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.4.0")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")
