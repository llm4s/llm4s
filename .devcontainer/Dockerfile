# The llm4s dev container (#2286): JDK 21 (from the base image), Coursier, sbt and Scala CLI.
# Every input is pinned: the base image by digest, each tool by version and SHA-256, so a rebuild is the same
# environment. To bump a tool, change its version and both checksums together.
FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm@sha256:0af991049cded4b8d5a90d2614289ceec390779ad6a3e4b70957e2b4f3bb1230

ARG COURSIER_VERSION=v2.1.26
ARG COURSIER_SHA256_X86_64=348e37bc2a8c706640e6b032c4551a0e055a7f1537485b4a91540e9d3598ec6d
ARG COURSIER_SHA256_AARCH64=6c358ac223332c8981eb86417d3faf9338a5ef6c95d1879a58162b24c510025c
ARG SCALA_CLI_VERSION=v1.18.0
ARG SCALA_CLI_SHA256_X86_64=eb41442f870bcae75aa5d6622ac96a173dae14fed59f71c97125eb21536bb060
ARG SCALA_CLI_SHA256_AARCH64=a9e5d10b4bb623839d1697518102d5b6a81d8f9ed8031b10b97f87f35a63ae68
# The launcher; the sbt version a build uses is the one in its project/build.properties.
ARG SBT_VERSION=1.12.12
ARG SBT_SHA256=84c6dd93c094577ce857d3b7ae450ef7ff88fceec099c8feb1cefac3e4b18a32

RUN set -eux; \
    case "$(uname -m)" in \
      x86_64)  arch=x86_64;  cs_sha="$COURSIER_SHA256_X86_64";  scli_sha="$SCALA_CLI_SHA256_X86_64" ;; \
      aarch64) arch=aarch64; cs_sha="$COURSIER_SHA256_AARCH64"; scli_sha="$SCALA_CLI_SHA256_AARCH64" ;; \
      *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    tmp="$(mktemp -d)"; cd "$tmp"; \
    curl -fsSL -o cs.gz "https://github.com/coursier/coursier/releases/download/${COURSIER_VERSION}/cs-${arch}-pc-linux.gz"; \
    echo "${cs_sha}  cs.gz" | sha256sum -c -; \
    gzip -d cs.gz; install -m 0755 cs /usr/local/bin/cs; \
    curl -fsSL -o scala-cli.gz "https://github.com/VirtusLab/scala-cli/releases/download/${SCALA_CLI_VERSION}/scala-cli-${arch}-pc-linux.gz"; \
    echo "${scli_sha}  scala-cli.gz" | sha256sum -c -; \
    gzip -d scala-cli.gz; install -m 0755 scala-cli /usr/local/bin/scala-cli; \
    curl -fsSL -o sbt.tgz "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz"; \
    echo "${SBT_SHA256}  sbt.tgz" | sha256sum -c -; \
    tar -xzf sbt.tgz -C /usr/local/share; ln -s /usr/local/share/sbt/bin/sbt /usr/local/bin/sbt; \
    cd /; rm -rf "$tmp"
