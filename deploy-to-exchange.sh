#!/usr/bin/env bash
#
# Publishes the JSON Logger connector to your Anypoint Exchange.
#
# Usage:
#   ./deploy-to-exchange.sh [options] <ANYPOINT_ORG_ID> [-- <extra maven args>]
#
# Options:
#   -r, --region us|eu   Anypoint control plane (default: us)
#   -s, --settings FILE  Maven settings.xml holding your Anypoint credentials
#                        (default: ~/.m2/settings.xml, see template-files/settings.xml)
#   -i, --server-id ID   <server> id in settings.xml with your Anypoint credentials
#                        (default: Exchange2)
#   -n, --dry-run        Build and test with your org id, but do not deploy
#   -h, --help           Show this help
#
# Examples:
#   ./deploy-to-exchange.sh 1dc579a2-xxxx-xxxx-xxxx-bd69ae513459
#   ./deploy-to-exchange.sh --region eu -s ~/.m2/anypoint-settings.xml <ORG_ID>
#   ./deploy-to-exchange.sh --server-id anypoint-exchange-v3 <ORG_ID>
#   ./deploy-to-exchange.sh <ORG_ID> -- -DskipTests
#
# The org id is only substituted into json-logger/pom.xml for the duration of the build;
# the file is always restored afterwards (also on failure or Ctrl+C), so the repo stays clean.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POM="$SCRIPT_DIR/json-logger/pom.xml"
TOKEN="ORG_ID_TOKEN"
MIN_JAVA=17

usage() { sed -n '3,23p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "[ERROR] $*" >&2; exit 1; }
info() { echo "> $*"; }

# ---- Arguments ---------------------------------------------------------------
REGION="us"
SETTINGS=""
SERVER_ID="Exchange2"
DRY_RUN=false
ORG_ID=""
MVN_EXTRA=()

while [ $# -gt 0 ]; do
  case "$1" in
    -r|--region)   [ $# -ge 2 ] || die "$1 requires a value"; REGION="$2"; shift 2 ;;
    -s|--settings) [ $# -ge 2 ] || die "$1 requires a value"; SETTINGS="$2"; shift 2 ;;
    -i|--server-id) [ $# -ge 2 ] || die "$1 requires a value"; SERVER_ID="$2"; shift 2 ;;
    -n|--dry-run)  DRY_RUN=true; shift ;;
    -h|--help)     usage; exit 0 ;;
    --)            shift; MVN_EXTRA=("$@"); break ;;
    -*)            usage >&2; die "Unknown option: $1" ;;
    *)             [ -z "$ORG_ID" ] || die "Unexpected argument: $1"; ORG_ID="$1"; shift ;;
  esac
done

[ -n "$ORG_ID" ] || { usage >&2; die "You need to provide your Anypoint Organization ID"; }
[[ "$SERVER_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "Invalid server id: $SERVER_ID"
[[ "$ORG_ID" =~ ^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$ ]] \
  || die "'$ORG_ID' is not a valid Organization ID (expected a UUID, see Access Management > Business Groups)"

case "$REGION" in
  us) MAVEN_HOST="maven.anypoint.mulesoft.com" ;;
  eu) MAVEN_HOST="maven.eu1.anypoint.mulesoft.com" ;;
  *)  die "Unsupported region '$REGION' (use 'us' or 'eu')" ;;
esac

if [ -n "$SETTINGS" ]; then
  [ -f "$SETTINGS" ] || die "Maven settings file not found: $SETTINGS"
  MVN_EXTRA=(-s "$SETTINGS" ${MVN_EXTRA[@]+"${MVN_EXTRA[@]}"})
fi

# ---- Prerequisites -----------------------------------------------------------
[ -f "$POM" ] || die "Connector pom not found: $POM"
command -v mvn >/dev/null 2>&1 || die "Maven (mvn) is not installed or not on PATH"

if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA="java"; fi
JAVA_VERSION="$("$JAVA" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')" || true
[ -n "$JAVA_VERSION" ] || die "Could not run Java ('$JAVA'). Set JAVA_HOME to a JDK $MIN_JAVA+ installation"
JAVA_MAJOR="${JAVA_VERSION#1.}"; JAVA_MAJOR="${JAVA_MAJOR%%.*}"
[ "$JAVA_MAJOR" -ge "$MIN_JAVA" ] || die "JDK $MIN_JAVA+ required, found Java $JAVA_VERSION ($JAVA). Set JAVA_HOME accordingly"

grep -q "<groupId>$TOKEN</groupId>" "$POM" \
  || die "$TOKEN placeholder not found in $POM. Restore it first (git checkout -- json-logger/pom.xml)"

# Project version = first <version> after <artifactId>json-logger</artifactId>
VERSION="$(awk '/<artifactId>json-logger<\/artifactId>/ {f=1} f && /<version>/ {gsub(/.*<version>|<\/version>.*/, ""); print; exit}' "$POM")"

# ---- Substitute org id (restored on exit) -----------------------------------
BACKUP="$(mktemp "${TMPDIR:-/tmp}/json-logger-pom.XXXXXX")"
cp "$POM" "$BACKUP"
restore_pom() { cp "$BACKUP" "$POM" && rm -f "$BACKUP"; }
trap restore_pom EXIT
trap 'exit 130' INT TERM

sed -e "s/$TOKEN/$ORG_ID/g" \
    -e "s#https://maven\.anypoint\.mulesoft\.com/#https://$MAVEN_HOST/#g" \
    -e "s#<id>Exchange2</id>#<id>$SERVER_ID</id>#g" \
    "$BACKUP" > "$POM"

# ---- Build & deploy ----------------------------------------------------------
if $DRY_RUN; then GOAL="verify"; ACTION="Building (dry run, nothing is published)"; else GOAL="deploy"; ACTION="Deploying to Exchange"; fi

echo "JSON Logger -> Anypoint Exchange"
info "Org ID:   $ORG_ID"
info "Region:   $REGION ($MAVEN_HOST)"
info "Version:  ${VERSION:-unknown}"
info "Server:   $SERVER_ID (credentials from ${SETTINGS:-~/.m2/settings.xml})"
info "Java:     $JAVA_VERSION"
info "$ACTION..."

if ! mvn -B -f "$POM" clean "$GOAL" ${MVN_EXTRA[@]+"${MVN_EXTRA[@]}"}; then
  echo "[ERROR] Failed to $GOAL json-logger" >&2
  if ! $DRY_RUN; then
    echo "        Common causes:" >&2
    echo "        - 401/403: check the '$SERVER_ID' server credentials in your Maven settings.xml (or pass --server-id)" >&2
    echo "        - 409/400: version ${VERSION:-?} already exists in Exchange; bump <version> in json-logger/pom.xml" >&2
  fi
  exit 1
fi

if $DRY_RUN; then
  echo "[OK] Dry run succeeded. Re-run without --dry-run to publish."
else
  echo "[OK] Published $ORG_ID:json-logger:${VERSION:-} to Exchange."
  echo "     Add it to your app with <groupId>$ORG_ID</groupId> <artifactId>json-logger</artifactId> <version>${VERSION:-}</version> <classifier>mule-plugin</classifier>"
fi
