import sbt._
import sbt.Keys._
import com.typesafe.sbt.packager.docker._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.Keys.{ packageName, maintainer }

/**
 * Centralised Docker settings for the workspaceRunner image.
 * Keeping these here keeps build.sbt concise and makes the image easier to tweak.
 */
object WorkspaceRunnerDocker {

  // Extra system packages and tooling currently installed in the image
  // Note: This is the existing behaviour moved out of build.sbt for clarity.
  val devToolingCommands: Seq[CmdLike] = Seq(
    Cmd("USER", "root"),
    Cmd("RUN", "apt-get update && apt-get install -y curl gnupg apt-transport-https ca-certificates zip unzip"),
    Cmd(
      "RUN",
      "echo 'deb https://repo.scala-sbt.org/scalasbt/debian all main' | tee /etc/apt/sources.list.d/sbt.list"
    ),
    Cmd(
      "RUN",
      "curl -sL 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823' | apt-key add"
    ),
    Cmd("RUN", "apt-get update && apt-get install -y sbt"),
    Cmd("RUN", "curl -s 'https://get.sdkman.io' | bash"),
    Cmd(
      "RUN",
      // Install both Scala versions used by the repo so `scala` tooling exists in the container
      // when needed. Consider a slimmer image in future.
      "bash -c 'source /root/.sdkman/bin/sdkman-init.sh && sdk install scala 3.3.3 && sdk install scala 2.13.14'"
    ),
    Cmd("ENV", "PATH=/root/.sdkman/candidates/scala/current/bin:$PATH")
  )

  // Core image settings used by workspaceRunner
  val settings: Seq[Setting[_]] = Seq(
    Docker / maintainer := "llm4s",
    Docker / packageName := "llm4s/workspace-runner",
    dockerExposedPorts  := Seq(8080),
    dockerBaseImage     := "eclipse-temurin:21-jdk",
    Docker / version    := version.value.replace('+', '-'),
    Docker / dockerBuildOptions := Seq("--platform=linux/amd64"),
    dockerCommands ++= devToolingCommands,
    dockerLabels ++= Map(
      "org.opencontainers.image.title"       -> "llm4s workspace-runner",
      "org.opencontainers.image.source"      -> "https://github.com/llm4s/llm4s",
      "org.opencontainers.image.description" -> "Executes workspace actions for llm4s via WebSocket"
    )
  )
}
