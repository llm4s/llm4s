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
# Credentials: SONATYPE_USERNAME and SONATYPE_PASSWORD, the Central Portal user token sbt-ci-release uses.
# CENTRAL_PORTAL_URL (default https://central.sonatype.com), CENTRAL_POLL_SECONDS (10) and CENTRAL_TIMEOUT_SECONDS
# (1800) are for tests and slow days.
#
# Exit code 0 = validated and publishing. 1 = refused or failed. 2 = usage.
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

BUNDLE="$(mktemp -d)/$ARTIFACT-$VERSION-bundle.zip"
(cd "$STAGING" && zip -q -X "$BUNDLE" "${files[@]}") || die "could not build the bundle"
echo "Bundle $BUNDLE:"
(cd "$STAGING" && printf '  %s\n' "${files[@]}")

if [ "$DRY_RUN" = 1 ]; then
  echo "Dry run: not uploaded."
  exit 0
fi

[ -n "${SONATYPE_USERNAME:-}" ] && [ -n "${SONATYPE_PASSWORD:-}" ] || die "SONATYPE_USERNAME and SONATYPE_PASSWORD must be set"
# The Portal's API takes the user token as a bearer token: base64 of "username:password".
AUTH="Authorization: Bearer $(printf '%s:%s' "$SONATYPE_USERNAME" "$SONATYPE_PASSWORD" | base64 | tr -d '\n')"

RESPONSE="$(mktemp)"
code="$(curl -sS -o "$RESPONSE" -w '%{http_code}' --max-time 300 -X POST -H "$AUTH" \
  -F "bundle=@$BUNDLE" \
  "$PORTAL/api/v1/publisher/upload?name=$ARTIFACT-$VERSION&publishingType=AUTOMATIC")" || code=000
case "$code" in
  200 | 201) ;;
  *) die "upload refused: HTTP $code $(head -c 2000 "$RESPONSE")" ;;
esac
DEPLOYMENT="$(tr -d ' \r\n"' < "$RESPONSE")"
[ -n "$DEPLOYMENT" ] || die "the Portal accepted the upload but returned no deployment id"
echo "Uploaded: deployment $DEPLOYMENT. Waiting for the Portal to validate it..."

waited=0
while :; do
  code="$(curl -sS -o "$RESPONSE" -w '%{http_code}' --max-time 60 -X POST -H "$AUTH" \
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
