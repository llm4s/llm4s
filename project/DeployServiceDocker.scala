import sbt._
import sbt.Keys._
import com.typesafe.sbt.packager.docker._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.Keys.{ packageName, maintainer }

/**
 * Docker settings for the deploy-service image (#846).
 *
 * Built like the workspace-runner image, with the sbt Docker plugin and `Docker/publishLocal`, so no
 * Dockerfile builds sbt inside an image and nothing copies the repository into a build context. The base is
 * a JRE, not a JDK, and the plugin runs the service as a non-root user.
 *
 * The image is not pinned to one architecture: it is built for the machine that builds it, which on a
 * GitHub-hosted runner is amd64. Build on an arm64 host, or pass `--platform` through `dockerBuildOptions`,
 * for a cluster of the other kind.
 */
object DeployServiceDocker {

  val settings: Seq[Setting[_]] = Seq(
    Docker / maintainer  := "llm4s",
    Docker / packageName := "llm4s/deploy-service",
    dockerExposedPorts   := Seq(8080),
    dockerBaseImage      := "eclipse-temurin:21-jre",
    // sbt-dynver versions contain '+', which is not valid in an image tag.
    Docker / version := version.value.replace('+', '-'),
    dockerLabels ++= Map(
      "org.opencontainers.image.title"  -> "llm4s deploy-service",
      "org.opencontainers.image.source" -> "https://github.com/llm4s/llm4s",
      "org.opencontainers.image.description" -> "A small llm4s HTTP service: /health and /llm-check, for staged-deployment pipelines"
    )
  )
}
