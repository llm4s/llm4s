#!/usr/bin/env bash
# Tests for scripts/publish-kotlin-api.sh, with no network and no credentials (#1494).
#
# `curl` is replaced by a fake Maven Central and Central Portal that log every call (its argv, and the contents and
# mode of any `-H @file` header file), keep the uploaded bundle's file list, and answer: the HEAD on Central's POM with a
# per-case code (404 by default), the `published` check and the deployments listing with per-case JSON (not published,
# no deployments), the upload with a deployment id (or a per-case HTTP error) and each status poll with the next state
# of a per-case sequence. The publication is a fixture file repository laid out as Gradle writes it.
#
# The cases cover what must be refused before anything is uploaded (a snapshot, an unsigned file, a missing javadoc
# jar, no credentials), the dry run, what the bundle holds and how it is sent (a bearer token from the Portal user
# token, passed in a 0600 header file and never in curl's argv, publishingType=AUTOMATIC, no maven-metadata.xml),
# every way the deployment can end (publishing, published, failed validation, a refused upload, no answer in time),
# re-runs (already on Central, already published or publishing on the Portal, an earlier deployment still validating,
# an earlier one that failed), a Portal that cannot answer (fail closed: no upload), and the temporary directory
# being removed.
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
out=""; url=""; prev=""; head=0
for a in "$@"; do
  case "$prev" in
    -o) out="$a" ;;
    -F) unzip -Z1 "${a#bundle=@}" > "$FAKE_STATE/bundle.txt" 2>/dev/null ;;
    -H)
      case "$a" in
        @*) f="${a#@}"
            mode="$(stat -c %a "$f" 2>/dev/null || stat -f %Lp "$f" 2>/dev/null)"
            echo "header-file mode=$mode $(cat "$f")" >> "$FAKE_LOG.headers" ;;
      esac ;;
  esac
  case "$a" in http*) url="$a" ;; -I) head=1 ;; esac
  prev="$a"
done
[ -n "$out" ] || out=/dev/null
case "$url" in
  https://central.invalid/*)
    [ "$head" = 1 ] || { printf '405'; exit 0; }
    printf '%s' "${FAKE_CENTRAL_CODE:-404}" ;;
  */api/v1/publisher/published*)
    body='{"published":false}'; printf '%s' "${FAKE_PUBLISHED_BODY:-$body}" > "$out"
    printf '%s' "${FAKE_PUBLISHED_CODE:-200}" ;;
  */api/v1/publisher/deployments*)
    body='{"deployments":[],"page":0}'; printf '%s' "${FAKE_DEPLOYMENTS_BODY:-$body}" > "$out"
    printf '%s' "${FAKE_DEPLOYMENTS_CODE:-200}" ;;
  */api/v1/publisher/upload*)
    printf '%s' "${FAKE_UPLOAD_BODY:-dep-123}" > "$out"
    printf '%s' "${FAKE_UPLOAD_CODE:-201}" ;;
  */api/v1/publisher/status*)
    n=0; [ -f "$FAKE_STATE/polls" ] && n=$(cat "$FAKE_STATE/polls"); n=$((n + 1)); echo "$n" > "$FAKE_STATE/polls"
    read -r -a states <<<"${FAKE_STATES:-PUBLISHING}"
    i=$((n - 1)); [ "$i" -lt "${#states[@]}" ] || i=$((${#states[@]} - 1))
    printf '{"deploymentId":"%s","deploymentState":"%s","errors":{"pkg":["%s"]}}' "${url##*id=}" "${states[$i]}" "${FAKE_ERROR:-none}" > "$out"
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

# run <version> <staging> [args...]: leaves the exit code in RC, the output in OUT, the calls in CALLS, the header files
# curl was given in HEADERS, and what the script left in its TMPDIR in LEFTOVER
run() {
  rm -rf "${WORK:?}/state" "${WORK:?}/tmp"; mkdir -p "$WORK/state" "$WORK/tmp"; : > "$WORK/log"; : > "$WORK/log.headers"
  OUT="$(PATH="$WORK/bin:$PATH" TMPDIR="$WORK/tmp" FAKE_LOG="$WORK/log" FAKE_STATE="$WORK/state" \
    CENTRAL_PORTAL_URL=https://portal.invalid CENTRAL_REPO_URL=https://central.invalid/maven2 CENTRAL_POLL_SECONDS=0 \
    bash "$SCRIPT" "$@" 2>&1)"
  RC=$?
  CALLS="$(cat "$WORK/log")"
  HEADERS="$(cat "$WORK/log.headers")"
  LEFTOVER="$(ls -A "$WORK/tmp")"
}
expect() { # <case> <rc> <fragment of the output>
  if [ "$RC" = "$2" ] && grep -qF -- "$3" <<<"$OUT"; then ok "$1"; else bad "$1" "rc=$RC, wanted $2 and '$3' in: $OUT"; fi
}
no_upload() { # <case>
  if [ -z "$CALLS" ]; then ok "$1"; else bad "$1" "curl was called: $CALLS"; fi
}
no_upload_call() { # <case>: it may ask, but it must not upload
  if ! grep -qF "/api/v1/publisher/upload" <<<"$CALLS"; then ok "$1"; else bad "$1" "it uploaded: $CALLS"; fi
}
uploaded_once() { # <case>
  local n
  n="$(grep -cF "/api/v1/publisher/upload" <<<"$CALLS")"
  if [ "$n" = 1 ]; then ok "$1"; else bad "$1" "$n uploads: $CALLS"; fi
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
if grep -qF -- "-H @" <<<"$upload_call" && grep -qxF "header-file mode=600 Authorization: Bearer $token" <<<"$HEADERS"; then
  ok "the upload is AUTOMATIC and authenticated with the Portal user token, from a 0600 header file"
else
  bad "the upload is AUTOMATIC and authenticated with the Portal user token, from a 0600 header file" "$CALLS / $HEADERS"
fi
if ! grep -qF -- "$token" <<<"$CALLS" && ! grep -qF secret <<<"$CALLS"; then
  ok "the token never appears in curl's argv"
else
  bad "the token never appears in curl's argv" "$CALLS"
fi
central_call="$(grep -F "https://central.invalid/maven2/$REL/llm4s-kotlin-api-$VERSION.pom" <<<"$CALLS")"
if [ -n "$central_call" ] && ! grep -qF -- "-H" <<<"$central_call"; then
  ok "it asks Maven Central for the POM first, without credentials"
else
  bad "it asks Maven Central for the POM first, without credentials" "$CALLS"
fi
if grep -qF "/api/v1/publisher/published?namespace=org.llm4s&name=llm4s-kotlin-api&version=$VERSION" <<<"$CALLS" &&
   grep -qF "/api/v1/publisher/deployments?namespace=org.llm4s&deploymentName=llm4s-kotlin-api-$VERSION" <<<"$CALLS"; then
  ok "it asks the Portal whether the version is published, and for its deployments, before uploading"
else
  bad "it asks the Portal whether the version is published, and for its deployments, before uploading" "$CALLS"
fi
uploaded_once "a version that is not out is uploaded once"
if [ -z "$LEFTOVER" ]; then ok "the bundle, header file and responses are removed on exit"; else bad "the temporary directory is removed on exit" "$LEFTOVER"; fi
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

if [ -z "$LEFTOVER" ]; then ok "the temporary directory is removed after a failure too"; else bad "the temporary directory is removed after a failure too" "$LEFTOVER"; fi

run "$VERSION" "$WORK/good" --dry-run
if [ -z "$LEFTOVER" ]; then ok "the dry run removes its bundle"; else bad "the dry run removes its bundle" "$LEFTOVER"; fi

# ---- re-runs: nothing is uploaded twice -------------------------------------------------------------------------------
FAKE_CENTRAL_CODE=200 run "$VERSION" "$WORK/good"
expect "a version already on Maven Central succeeds without uploading" 0 "already on Maven Central"
no_upload_call "a version already on Maven Central is not uploaded"

FAKE_PUBLISHED_BODY='{"published":true}' run "$VERSION" "$WORK/good"
expect "a version the Portal reports published succeeds without uploading" 0 "The Portal reports llm4s-kotlin-api-$VERSION as published"
no_upload_call "a version the Portal reports published is not uploaded"

dep() { printf '{"deploymentId":"%s","deploymentName":"%s","namespace":"org.llm4s","deploymentState":"%s"}' "$@"; }
FAKE_DEPLOYMENTS_BODY="{\"deployments\":[$(dep old-1 "llm4s-kotlin-api-$VERSION" FAILED),$(dep dep-9 "llm4s-kotlin-api-$VERSION" PUBLISHING)]}" \
  run "$VERSION" "$WORK/good"
expect "a version already PUBLISHING succeeds without uploading" 0 "Deployment dep-9 of llm4s-kotlin-api-$VERSION is already PUBLISHING"
no_upload_call "a version already PUBLISHING is not uploaded"

FAKE_DEPLOYMENTS_BODY="{\"deployments\":[$(dep dep-7 "llm4s-kotlin-api-$VERSION" VALIDATING)]}" FAKE_STATES="VALIDATED PUBLISHING" \
  run "$VERSION" "$WORK/good"
expect "a deployment still validating is followed to the end" 0 "Deployment dep-7 passed validation and is PUBLISHING"
no_upload_call "a deployment still validating is not uploaded again"
if grep -qF "status?id=dep-7" <<<"$CALLS"; then ok "it polls the earlier deployment"; else bad "it polls the earlier deployment" "$CALLS"; fi

FAKE_DEPLOYMENTS_BODY="{\"deployments\":[$(dep old-1 "llm4s-kotlin-api-$VERSION" FAILED),$(dep rc-1 "llm4s-kotlin-api-$VERSION-RC1" PUBLISHED)]}" \
  FAKE_STATES="PUBLISHING" run "$VERSION" "$WORK/good"
expect "an earlier FAILED deployment, or another version's, does not stop the upload" 0 "Uploaded: deployment dep-123"
uploaded_once "after a FAILED deployment it uploads once"

# ---- a Portal that cannot answer: fail closed, no upload ----------------------------------------------------------------
FAKE_PUBLISHED_CODE=500 FAKE_PUBLISHED_BODY='{"message":"Internal server error"}' run "$VERSION" "$WORK/good"
expect "the published check failing stops the release" 1 "could not tell whether llm4s-kotlin-api-$VERSION is already published (HTTP 500"
no_upload_call "the published check failing uploads nothing"

FAKE_PUBLISHED_BODY='<html>maintenance</html>' run "$VERSION" "$WORK/good"
expect "an unreadable published answer stops the release" 1 "could not tell whether"
no_upload_call "an unreadable published answer uploads nothing"

FAKE_PUBLISHED_CODE=401 FAKE_PUBLISHED_BODY='{"message":"Authentication required"}' run "$VERSION" "$WORK/good"
expect "bad credentials are found before uploading" 1 "HTTP 401"
no_upload_call "bad credentials upload nothing"

FAKE_DEPLOYMENTS_CODE=503 FAKE_DEPLOYMENTS_BODY='unavailable' run "$VERSION" "$WORK/good"
expect "the deployments listing failing stops the release" 1 "could not list the Portal's deployments"
no_upload_call "the deployments listing failing uploads nothing"

FAKE_CENTRAL_CODE=503 FAKE_STATES="PUBLISHING" run "$VERSION" "$WORK/good"
expect "Maven Central not answering falls through to the Portal, which decides" 0 "is PUBLISHING"
uploaded_once "Maven Central not answering still uploads once, on the Portal's word"

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
