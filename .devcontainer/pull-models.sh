#!/usr/bin/env bash
# Pulls the playground's models into the Ollama sidecar (#2286): the chat model OLLAMA_MODEL and, when EMBEDDING_MODEL
# names an Ollama model (`ollama/<name>`), that embedding model. Waits for the sidecar to answer first. Pulling a
# model that is already there only checks it, so this is safe to run again, for example after changing OLLAMA_MODEL.
set -euo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
models=("${OLLAMA_MODEL:-qwen2.5:0.5b}")
case "${EMBEDDING_MODEL:-}" in
  ollama/*) models+=("${EMBEDDING_MODEL#ollama/}") ;;
esac

for _ in $(seq 1 60); do
  curl -fsS "$BASE_URL/api/tags" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$BASE_URL/api/tags" >/dev/null || { echo "Ollama does not answer at $BASE_URL" >&2; exit 1; }

for model in "${models[@]}"; do
  echo "Pulling $model into Ollama at $BASE_URL ..."
  curl -fsS --max-time 1800 "$BASE_URL/api/pull" -d "{\"model\": \"$model\", \"stream\": false}"
  echo
done
