#!/usr/bin/env bash
# Uploads the signed org.llm4s:llm4s-kotlin-api publication to Maven Central through the Sonatype Central Portal
# (#1494). release.yml runs it after the Gradle build wrote the publication to a file repository and
# scripts/check-kotlin-publication.sh passed on it with REQUIRE_SIGNATURES=1.
#
#   scripts/publish-kotlin-api.sh 0.5.0 modules/kotlin-api/build/staging-deploy
#   scripts/publish-kotlin-api.sh 0.5.0 modules/kotlin-api/build/staging-deploy --dry-run   # build the bundle only
#
# It zips the version directory (org/llm4s/llm4s-kotlin-api/<version>/: the POM, jars, Gradle module file, their
# signatures and checksums - not maven-metadata.xml, which Central writes itself) into a Portal bundle, uploads it
# with publishingType=AUTOMATIC, the way `sbt ci-release` releases the sbt artifacts, and polls the deployment until
# the Portal has validated it and started publishing (PUBLISHING or PUBLISHED). A deployment that FAILED validation is
# reported with the Portal's reasons. Nothing reaches Central unless validation passes.
#
# It is safe to re-run. Before uploading it asks, in this order, whether the version is already out, and uploads only
# when every answer is no:
#   1. Maven Central itself: a HEAD on the POM at repo1.maven.org (no credentials sent). 200 = published, exit 0.
#      Anything else falls through: Central's index lags the Portal, so a 404 proves nothing.
#   2. The Portal: GET /api/v1/publisher/published?namespace=org.llm4s&name=llm4s-kotlin-api&version=<v>, which
#      answers {"published": true|false}. true = published, exit 0.
#   3. The Portal's deployments: GET /api/v1/publisher/deployments?namespace=org.llm4s&deploymentName=<bundle name>.
#      A deployment of exactly this name that is PUBLISHING or PUBLISHED = exit 0; one still PENDING, VALIDATING or
#      VALIDATED (an earlier run that timed out waiting) is adopted and polled instead of uploading a second one. A
#      FAILED one is ignored: nothing of it reached Central.
# The Portal checks fail closed: if either does not answer 200 with the expected JSON, the script exits 1 without
# uploading. A second deployment of a version that is already publishing cannot be withdrawn and only fails
# validation later; failing here costs a re-run once the Portal answers, and the release waits (see
# docs/reference/release.md, "Re-triggering a failed release").
#
# Credentials: SONATYPE_USERNAME and SONATYPE_PASSWORD, the Central Portal user token sbt-ci-release uses. The bearer
# token derived from them is passed to curl in a 0600 header file (`-H @file`), never on its command line, and the
# file, the bundle and every response are in one temporary directory removed on exit.
# CENTRAL_PORTAL_URL (default https://central.sonatype.com), CENTRAL_REPO_URL (https://repo1.maven.org/maven2),
# CENTRAL_POLL_SECONDS (10) and CENTRAL_TIMEOUT_SECONDS (1800) are for tests and slow days.
#
# Exit code 0 = validated and publishing, or already published or publishing. 1 = refused or failed. 2 = usage.
set -uo pipefail

usage() { echo "usage: $0 <version> <staging-repo> [--dry-run]" >&2; exit 2; }
[ $# -ge 2 ] || usage
VERSION="${1#v}"; STAGING="$2"; shift 2
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    *) usage ;;
  esac
done

ARTIFACT=llm4s-kotlin-api
REL="org/llm4s/$ARTIFACT/$VERSION"
PORTAL="${CENTRAL_PORTAL_URL:-https://central.sonatype.com}"
CENTRAL_REPO="${CENTRAL_REPO_URL:-https://repo1.maven.org/maven2}"
POLL="${CENTRAL_POLL_SECONDS:-10}"
TIMEOUT="${CENTRAL_TIMEOUT_SECONDS:-1800}"

die() { echo "$*" >&2; exit 1; }

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "not a release version: '$VERSION'" >&2; exit 2; }
case "$VERSION" in *SNAPSHOT*) die "refusing to upload a snapshot ($VERSION): Maven Central takes releases only" ;; esac
[ -d "$STAGING/$REL" ] || die "no publication at $STAGING/$REL"

# Every file Central receives must be signed. check-kotlin-publication.sh checks this too; the upload refuses on its own
# so that it can never be the step that ships an unsigned artifact.
files=()
while IFS= read -r f; do files+=("$f"); done < <(cd "$STAGING" && find "$REL" -maxdepth 1 -type f ! -name 'maven-metadata.xml*' | sort)
[ "${#files[@]}" -gt 0 ] || die "the publication at $STAGING/$REL is empty"
for f in "${files[@]}"; do
  case "$f" in
    *.pom | *.jar | *.module) [ -f "$STAGING/$f.asc" ] || die "refusing to upload: $f is not signed (no $f.asc)" ;;
  esac
done
for required in "$ARTIFACT-$VERSION.pom" "$ARTIFACT-$VERSION.jar" "$ARTIFACT-$VERSION-sources.jar" "$ARTIFACT-$VERSION-javadoc.jar"; do
  [ -f "$STAGING/$REL/$required" ] || die "refusing to upload: $REL/$required is missing"
done

# One private directory for the bundle, the auth header and the responses, removed however the script ends.
umask 077
TMP="$(mktemp -d "${TMPDIR:-/tmp}/publish-kotlin-api.XXXXXX")" || die "could not create a temporary directory"
trap 'rm -rf "$TMP"' EXIT
BUNDLE="$TMP/$ARTIFACT-$VERSION-bundle.zip"
(cd "$STAGING" && zip -q -X "$BUNDLE" "${files[@]}") || die "could not build the bundle"
echo "Bundle $ARTIFACT-$VERSION-bundle.zip:"
(cd "$STAGING" && printf '  %s\n' "${files[@]}")

if [ "$DRY_RUN" = 1 ]; then
  echo "Dry run: not uploaded."
  exit 0
fi

[ -n "${SONATYPE_USERNAME:-}" ] && [ -n "${SONATYPE_PASSWORD:-}" ] || die "SONATYPE_USERNAME and SONATYPE_PASSWORD must be set"
# The Portal's API takes the user token as a bearer token: base64 of "username:password". It goes to curl as a header
# file, so it never appears in a process listing; printf is a builtin, so it is not in base64's arguments either.
AUTH_HEADER="$TMP/auth-header"
{ : > "$AUTH_HEADER" && chmod 600 "$AUTH_HEADER"; } || die "could not write the auth header file"
printf 'Authorization: Bearer %s\n' "$(printf '%s:%s' "$SONATYPE_USERNAME" "$SONATYPE_PASSWORD" | base64 | tr -d '\n')" > "$AUTH_HEADER"
RESPONSE="$TMP/response"
NAME="$ARTIFACT-$VERSION"

# ---- already out? (see the header) ------------------------------------------------------------------------------------
code="$(curl -sS -I -o /dev/null -w '%{http_code}' --max-time 60 "$CENTRAL_REPO/$REL/$NAME.pom")" || code=000
if [ "$code" = 200 ]; then
  echo "$NAME is already on Maven Central ($CENTRAL_REPO/$REL/$NAME.pom): nothing to upload."
  exit 0
fi

code="$(curl -sS -o "$RESPONSE" -w '%{http_code}' --max-time 60 -H "@$AUTH_HEADER" \
  "$PORTAL/api/v1/publisher/published?namespace=org.llm4s&name=$ARTIFACT&version=$VERSION")" || code=000
published=""
[ "$code" = 200 ] && published="$(python3 -c '
import json, sys
v = json.load(open(sys.argv[1])).get("published")
print({True: "yes", False: "no"}.get(v, ""))' "$RESPONSE" 2>/dev/null)"
case "$published" in
  yes) echo "The Portal reports $NAME as published: nothing to upload."; exit 0 ;;
  no) ;;
  *) die "could not tell whether $NAME is already published (HTTP $code $(head -c 2000 "$RESPONSE")): not uploading, so as not to deploy it twice. Re-run once the Portal answers." ;;
esac

code="$(curl -sS -o "$RESPONSE" -w '%{http_code}' --max-time 60 -H "@$AUTH_HEADER" \
  "$PORTAL/api/v1/publisher/deployments?namespace=org.llm4s&deploymentName=$NAME&size=100")" || code=000
[ "$code" = 200 ] || die "could not list the Portal's deployments of $NAME (HTTP $code $(head -c 2000 "$RESPONSE")): not uploading, so as not to deploy it twice. Re-run once the Portal answers."
# deploymentName is a case-insensitive substring filter (0.5.0 also matches 0.5.0-RC1), so keep exact names only.
# Prints "<state> <id>" for the deployment to act on, or nothing.
existing="$(python3 -c '
import json, sys
ds = [d for d in json.load(open(sys.argv[1])).get("deployments", []) if d.get("deploymentName") == sys.argv[2]]
for wanted in (("PUBLISHED", "PUBLISHING"), ("PENDING", "VALIDATING", "VALIDATED")):
    hit = [d for d in ds if d.get("deploymentState") in wanted]
    if hit:
        print(hit[0]["deploymentState"], hit[0].get("deploymentId", ""))
        break' "$RESPONSE" "$NAME" 2>/dev/null)" || die "could not read the Portal's deployments of $NAME: $(head -c 2000 "$RESPONSE")"
DEPLOYMENT=""
case "$existing" in
  PUBLISHED* | PUBLISHING*)
    echo "Deployment ${existing#* } of $NAME is already ${existing%% *}: nothing to upload."
    exit 0 ;;
  ?*)
    DEPLOYMENT="${existing#* }"
    [ -n "$DEPLOYMENT" ] || die "the Portal lists a ${existing%% *} deployment of $NAME with no id"
    echo "Deployment $DEPLOYMENT of $NAME is already ${existing%% *}: following it instead of uploading again." ;;
esac

if [ -z "$DEPLOYMENT" ]; then
  code="$(curl -sS -o "$RESPONSE" -w '%{http_code}' --max-time 300 -X POST -H "@$AUTH_HEADER" \
    -F "bundle=@$BUNDLE" \
    "$PORTAL/api/v1/publisher/upload?name=$NAME&publishingType=AUTOMATIC")" || code=000
  case "$code" in
    200 | 201) ;;
    *) die "upload refused: HTTP $code $(head -c 2000 "$RESPONSE")" ;;
  esac
  DEPLOYMENT="$(tr -d ' \r\n"' < "$RESPONSE")"
  [ -n "$DEPLOYMENT" ] || die "the Portal accepted the upload but returned no deployment id"
  echo "Uploaded: deployment $DEPLOYMENT. Waiting for the Portal to validate it..."
fi

waited=0
while :; do
  code="$(curl -sS -o "$RESPONSE" -w '%{http_code}' --max-time 60 -X POST -H "@$AUTH_HEADER" \
    "$PORTAL/api/v1/publisher/status?id=$DEPLOYMENT")" || code=000
  state=""
  if [ "$code" = 200 ]; then
    state="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("deploymentState",""))' "$RESPONSE" 2>/dev/null)"
  fi
  case "$state" in
    PUBLISHED) echo "Deployment $DEPLOYMENT is PUBLISHED."; exit 0 ;;
    PUBLISHING) echo "Deployment $DEPLOYMENT passed validation and is PUBLISHING to Maven Central."; exit 0 ;;
    FAILED) die "Deployment $DEPLOYMENT FAILED: $(head -c 4000 "$RESPONSE")" ;;
    *) echo "  state ${state:-unknown} (HTTP $code)" ;;
  esac
  [ "$waited" -lt "$TIMEOUT" ] || die "Deployment $DEPLOYMENT was not validated within ${TIMEOUT}s; check https://central.sonatype.com/publishing/deployments"
  sleep "$POLL"
  waited=$((waited + POLL))
done
