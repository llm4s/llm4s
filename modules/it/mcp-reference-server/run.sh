#!/bin/sh
# Starts the MCP reference server for MCPReferenceServerSpec (@Docker) on Streamable HTTP, port 3001.
#
# Installs exactly the tree pinned in package-lock.json (npm ci fails if the lockfile and
# package.json disagree) with install scripts disabled; no package in the tree declares one. The
# install goes to a scratch directory, so the source directory can be mounted read-only.
#
# CI and the documented local command run it in node:22-alpine, pinned by digest:
#   docker run --rm -p 3001:3001 -v "$PWD/modules/it/mcp-reference-server:/src:ro" \
#     node:22-alpine@sha256:<digest in .github/workflows/ci.yml> sh /src/run.sh
# MCP_SERVER_SRC and MCP_SERVER_WORKDIR override the two directories outside a container.
set -eu
src="${MCP_SERVER_SRC:-/src}"
workdir="${MCP_SERVER_WORKDIR:-/tmp/mcp-reference-server}"
mkdir -p "$workdir"
cp "$src/package.json" "$src/package-lock.json" "$workdir/"
cd "$workdir"
npm ci --ignore-scripts --no-audit --no-fund
exec node_modules/.bin/mcp-server-everything streamableHttp
