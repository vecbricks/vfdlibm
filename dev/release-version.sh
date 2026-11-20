#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Says whether a tag may release the version pom.xml holds:
#
#   dev/release-version.sh [<tag>]
#   dev/release-version.sh --version
#   dev/release-version.sh --self-test
#
# A tag v<version> releases the pom's <version> when the two are equal, the version is not a
# -SNAPSHOT, and the pom's <scm><tag> is the tag too. .github/workflows/release.yml runs it with
# the pushed tag before anything is built for upload. With no tag it prints the pom's version
# and the tag that would release it, and passes: that is a dry run from a branch. --version
# prints the pom's version and nothing else; --self-test runs the rule on cases written here.
#
# It reads pom.xml as XML with Python, with no JVM and no Maven: the version is the project's own
# <version> element, which this pom writes out (no property, no parent). Exit status: 0 when
# the tag may release the version (or no tag was given), 1 when it may not, 2 when it could not
# run.
set -uo pipefail

die() { echo "release-version: $*" >&2; exit 2; }

root="$(cd "$(dirname "$0")/.." && pwd)" || die "cannot find the checkout"
command -v python3 > /dev/null || die "no python3 on PATH"

# Prints the project's own <version> and <scm><tag> of the pom $1, one per line.
pom_fields() {
  python3 - "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET

NS = "{http://maven.apache.org/POM/4.0.0}"
project = ET.parse(sys.argv[1]).getroot()
version = (project.findtext(NS + "version") or "").strip()
tag = (project.findtext(NS + "scm/" + NS + "tag") or "").strip()
if not version or "$" in version:
    sys.exit("the pom has no literal <version>")
print(version)
print(tag)
PY
}

# decide <version> <scm tag> <tag>: prints the verdict, returns 0 if the tag may release.
decide() {
  local version="$1" scm_tag="$2" tag="$3" bad=0
  case "$tag" in
    v?*) ;;
    *) echo "refused: the tag $tag is not v<version>"; return 1 ;;
  esac
  case "$version" in
    *-SNAPSHOT) echo "refused: the pom's version $version is a snapshot"; bad=1 ;;
  esac
  if [ "${tag#v}" != "$version" ]; then
    echo "refused: the tag $tag is not v$version, the pom's version"
    bad=1
  fi
  if [ "$scm_tag" != "$tag" ]; then
    echo "refused: the pom's <scm><tag> is $scm_tag, not $tag"
    bad=1
  fi
  [ "$bad" -eq 0 ] && echo "the tag $tag releases version $version"
  return "$bad"
}

self_test() {
  local failed=0 want version scm_tag tag
  # expected status, version, scm tag, tag
  while read -r want version scm_tag tag; do
    decide "$version" "$scm_tag" "$tag" > /dev/null
    if [ $? -ne "$want" ]; then
      echo "self-test: decide $version $scm_tag $tag did not return $want"
      failed=1
    fi
  done <<'CASES'
0 0.1.0 v0.1.0 v0.1.0
0 0.1.0-rc1 v0.1.0-rc1 v0.1.0-rc1
1 0.1.0-SNAPSHOT v0.1.0-SNAPSHOT v0.1.0-SNAPSHOT
1 0.1.0-SNAPSHOT HEAD v0.1.0
1 0.1.0 v0.1.0 v0.1.1
1 0.1.0 v0.1.0 0.1.0
1 0.1.0 HEAD v0.1.0
1 0.1.0 v0.1.0 v
1 0.1.0-rc1 v0.1.0-rc1 v0.1.0
CASES
  [ "$failed" -eq 0 ] && echo "self-test: passed"
  return "$failed"
}

case "${1:-}" in
  -h|--help) sed -n '/^# Says whether/,/^[^#]/p' "$0" | sed '$d'; exit 0 ;;
  --self-test) self_test; exit $? ;;
esac
[ $# -le 1 ] || die "usage: dev/release-version.sh [<tag>]"

fields="$(pom_fields "$root/pom.xml")" || die "cannot read $root/pom.xml"
version="$(echo "$fields" | sed -n 1p)"
scm_tag="$(echo "$fields" | sed -n 2p)"
if [ "${1:-}" = --version ]; then
  echo "$version"
  exit 0
fi
echo "release-version: pom.xml holds version $version, <scm><tag> $scm_tag"

if [ $# -eq 0 ]; then
  case "$version" in
    *-SNAPSHOT) echo "no tag releases a snapshot; a release pull request sets the version" ;;
    *) echo "the tag v$version would release it, once <scm><tag> is v$version" ;;
  esac
  exit 0
fi
decide "$version" "$scm_tag" "$1"
