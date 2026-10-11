#!/usr/bin/env bash
# Tests for scripts/publish-kotlin-api.sh, with no network and no credentials (#1494).
#
# `curl` is replaced by a fake Central Portal that logs every call, keeps the uploaded bundle's file list, and answers
# the upload with a deployment id (or a per-case HTTP error) and each status poll with the next state of a per-case
# sequence. The publication is a fixture file repository laid out as Gradle writes it.
#
# The cases cover what must be refused before anything is uploaded (a snapshot, an unsigned file, a missing javadoc
# jar, no credentials), the dry run, what the bundle holds and how it is sent (a bearer token from the Portal user
# token, publishingType=AUTOMATIC, no maven-metadata.xml), and every way the deployment can end (publishing,
# published, failed validation, a refused upload, no answer in time).
#
# Usage: scripts/test-publish-kotlin-api.sh      (needs bash, python3, zip and unzip, all on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${PUBLISH_SCRIPT:-$REPO_ROOT/scripts/publish-kotlin-api.sh}"   # PUBLISH_SCRIPT: run the tests against another copy
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAIL=0
PASS=0
ok()  { PASS=$((PASS + 1)); echo "ok   [$1]"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL [$1]"; [ $# -lt 2 ] || printf '       %s\n' "$2"; }

# ---- the fake Central Portal ------------------------------------------------------------------------------------------
mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
echo "curl $*" >> "$FAKE_LOG"
out=""; url=""; prev=""
for a in "$@"; do
  case "$prev" in
    -o) out="$a" ;;
    -F) unzip -Z1 "${a#bundle=@}" > "$FAKE_STATE/bundle.txt" 2>/dev/null ;;
  esac
  case "$a" in http*) url="$a" ;; esac
  prev="$a"
done
case "$url" in
  */api/v1/publisher/upload*)
    printf '%s' "${FAKE_UPLOAD_BODY:-dep-123}" > "$out"
    printf '%s' "${FAKE_UPLOAD_CODE:-201}" ;;
  */api/v1/publisher/status*)
    n=0; [ -f "$FAKE_STATE/polls" ] && n=$(cat "$FAKE_STATE/polls"); n=$((n + 1)); echo "$n" > "$FAKE_STATE/polls"
    states=($FAKE_STATES)
    i=$((n - 1)); [ "$i" -lt "${#states[@]}" ] || i=$((${#states[@]} - 1))
    printf '{"deploymentId":"dep-123","deploymentState":"%s","errors":{"pkg":["%s"]}}' "${states[$i]}" "${FAKE_ERROR:-none}" > "$out"
    printf '200' ;;
  *) printf '404' ;;
esac
exit 0
FAKE
chmod +x "$WORK/bin/curl"

VERSION=0.5.0
REL="org/llm4s/llm4s-kotlin-api/$VERSION"

fixture() { # <dir> [unsigned-file]
  local d="$1/$REL" f
  mkdir -p "$d"
  for f in "llm4s-kotlin-api-$VERSION.pom" "llm4s-kotlin-api-$VERSION.jar" "llm4s-kotlin-api-$VERSION-sources.jar" \
    "llm4s-kotlin-api-$VERSION-javadoc.jar" "llm4s-kotlin-api-$VERSION.module"; do
    echo "$f" > "$d/$f"; echo sum > "$d/$f.md5"; echo sum > "$d/$f.sha1"
    [ "$f" = "${2:-}" ] || echo sig > "$d/$f.asc"
  done
  echo '<metadata/>' > "$d/maven-metadata.xml"
  echo '<metadata/>' > "$1/org/llm4s/llm4s-kotlin-api/maven-metadata.xml"
}

# run <version> <staging> [args...]: leaves the exit code in RC, the output in OUT, the calls in CALLS
run() {
  rm -rf "$WORK/state"; mkdir -p "$WORK/state"; : > "$WORK/log"
  OUT="$(PATH="$WORK/bin:$PATH" FAKE_LOG="$WORK/log" FAKE_STATE="$WORK/state" \
    CENTRAL_PORTAL_URL=https://portal.invalid CENTRAL_POLL_SECONDS=0 \
    bash "$SCRIPT" "$@" 2>&1)"
  RC=$?
  CALLS="$(cat "$WORK/log")"
}
expect() { # <case> <rc> <fragment of the output>
  if [ "$RC" = "$2" ] && grep -qF -- "$3" <<<"$OUT"; then ok "$1"; else bad "$1" "rc=$RC, wanted $2 and '$3' in: $OUT"; fi
}
no_upload() { # <case>
  if [ -z "$CALLS" ]; then ok "$1"; else bad "$1" "curl was called: $CALLS"; fi
}

export SONATYPE_USERNAME=user SONATYPE_PASSWORD=secret
fixture "$WORK/good"

# ---- refused before anything is uploaded ----------------------------------------------------------------------------
run "$VERSION-SNAPSHOT" "$WORK/good"
expect "a snapshot is refused" 1 "refusing to upload a snapshot"
no_upload "a snapshot uploads nothing"

fixture "$WORK/unsigned" "llm4s-kotlin-api-$VERSION.jar"
run "$VERSION" "$WORK/unsigned"
expect "an unsigned jar is refused" 1 "is not signed"
no_upload "an unsigned publication uploads nothing"

fixture "$WORK/nodoc"
rm "$WORK/nodoc/$REL/llm4s-kotlin-api-$VERSION-javadoc.jar"*
run "$VERSION" "$WORK/nodoc"
expect "a publication without its javadoc jar is refused" 1 "llm4s-kotlin-api-$VERSION-javadoc.jar is missing"
no_upload "a publication without its javadoc jar uploads nothing"

run 0.4.0 "$WORK/good"
expect "a version that was not published there is refused" 1 "no publication at"

run "$VERSION" "$WORK/good" --surprise
expect "an unknown option is a usage error" 2 "usage:"

SONATYPE_PASSWORD="" run "$VERSION" "$WORK/good"
expect "no credentials, no upload" 1 "SONATYPE_USERNAME and SONATYPE_PASSWORD must be set"
no_upload "no credentials uploads nothing"

# ---- the dry run ----------------------------------------------------------------------------------------------------
run "v$VERSION" "$WORK/good" --dry-run
expect "the dry run builds the bundle, accepting a leading v" 0 "Dry run: not uploaded."
no_upload "the dry run uploads nothing"

# ---- a successful upload ----------------------------------------------------------------------------------------------
FAKE_STATES="PENDING VALIDATING PUBLISHING" run "$VERSION" "$WORK/good"
expect "a deployment that passes validation is publishing" 0 "passed validation and is PUBLISHING"
bundle="$(cat "$WORK/state/bundle.txt" 2>/dev/null)"
if grep -qx "$REL/llm4s-kotlin-api-$VERSION.pom.asc" <<<"$bundle" &&
   grep -qx "$REL/llm4s-kotlin-api-$VERSION-javadoc.jar" <<<"$bundle" &&
   ! grep -q maven-metadata <<<"$bundle"; then
  ok "the bundle holds the version directory with its signatures, and no maven-metadata.xml"
else
  bad "the bundle holds the version directory with its signatures, and no maven-metadata.xml" "$bundle"
fi
token="$(printf 'user:secret' | base64)"
upload_call="$(grep -F "/api/v1/publisher/upload?name=llm4s-kotlin-api-$VERSION&publishingType=AUTOMATIC" <<<"$CALLS")"
if grep -qF "Authorization: Bearer $token" <<<"$upload_call"; then
  ok "the upload is AUTOMATIC and authenticated with the Portal user token"
else
  bad "the upload is AUTOMATIC and authenticated with the Portal user token" "$CALLS"
fi
if [ "$(cat "$WORK/state/polls")" = 3 ] && grep -qF "status?id=dep-123" <<<"$CALLS"; then
  ok "it polls the deployment it uploaded until a final state"
else
  bad "it polls the deployment it uploaded until a final state" "polls=$(cat "$WORK/state/polls" 2>/dev/null)"
fi

FAKE_STATES="PUBLISHED" run "$VERSION" "$WORK/good"
expect "an already published deployment succeeds" 0 "is PUBLISHED"

# ---- failures after the upload ------------------------------------------------------------------------------------------
FAKE_STATES="VALIDATING FAILED" FAKE_ERROR="Invalid POM: missing scm" run "$VERSION" "$WORK/good"
expect "a deployment that fails validation fails, with the Portal's reasons" 1 "Invalid POM: missing scm"

FAKE_UPLOAD_CODE=401 FAKE_UPLOAD_BODY="Unauthorized" run "$VERSION" "$WORK/good"
expect "a refused upload fails with its HTTP status" 1 "upload refused: HTTP 401 Unauthorized"

FAKE_STATES="VALIDATING" CENTRAL_TIMEOUT_SECONDS=0 run "$VERSION" "$WORK/good"
expect "a deployment that is never validated times out" 1 "was not validated within 0s"

# ---- release.yml runs it, after the sbt publish and before the GitHub Release -------------------------------------------
RELEASE="${RELEASE_WORKFLOW:-$REPO_ROOT/.github/workflows/release.yml}"
if command -v ruby >/dev/null 2>&1; then
  wiring="$(ruby -ryaml -e '
    wf = YAML.load_file(ARGV[0]); jobs = wf["jobs"] || {}; errs = []
    k = jobs["publish-kotlin"]
    if k.nil?
      errs << "no publish-kotlin job"
    else
      errs << "publish-kotlin must run after publish" unless Array(k["needs"]).include?("publish")
      runs = (k["steps"] || []).map { |s| s["run"].to_s }.join("\n")
      errs << "publish-kotlin must check the version sbt released" unless runs.include?("print ThisBuild / version")
      errs << "publish-kotlin must build with the tag version" unless runs.include?("-Pllm4sVersion=\"${GITHUB_REF_NAME#v}\"")
      errs << "publish-kotlin must check the signed publication" unless runs.include?("REQUIRE_SIGNATURES=1 scripts/check-kotlin-publication.sh")
      errs << "publish-kotlin must upload with publish-kotlin-api.sh" unless runs.include?("scripts/publish-kotlin-api.sh")
      envs = (k["steps"] || []).map { |s| s["env"] || {} }.reduce({}, :merge)
      %w[PGP_SECRET ORG_GRADLE_PROJECT_signingPassword SONATYPE_USERNAME SONATYPE_PASSWORD].each { |e| errs << "publish-kotlin needs #{e}" unless envs.key?(e) }
      errs << "a workflow expression is interpolated into a shell" if runs.include?("${{")
    end
    gr = jobs["github-release"]
    errs << "github-release must wait for publish-kotlin" unless gr && Array(gr["needs"]).include?("publish-kotlin")
    puts errs' "$RELEASE")"
  if [ -z "$wiring" ]; then ok "release.yml publishes the Kotlin artifact after sbt and before the GitHub Release"; else bad "release.yml wiring" "$wiring"; fi
else
  bad "release.yml wiring" "ruby is needed to read release.yml"
fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
