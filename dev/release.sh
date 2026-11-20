#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Prepares a release on the owner's machine: sets the version, verifies, commits, tags, and
# returns to a snapshot. Adapted from vecbricks/jegg's dev/release.sh.
#
#   dev/release.sh 0.1.0                  release 0.1.0, then start 0.1.1-SNAPSHOT
#   dev/release.sh 0.2.0 0.3.0-SNAPSHOT   release 0.2.0, then start the named snapshot
#   dev/release.sh --check 0.1.0          the checks only, nothing changed
#   dev/release.sh --self-test            the script's own checks and flow, in scratch repositories
#
# The checks: a clean main at origin/main; no tag v<version>, here or on origin; a
# `## [<version>]` section and a `[<version>]:` link line in CHANGELOG.md; a pom at
# <version>-SNAPSHOT. The release: sets the pom's version, its <scm><tag> (v<version>, which
# dev/release-version.sh requires of a tag) and project.build.outputTimestamp (so that the jars
# are reproducible), dates the changelog section, runs `mvn -B -Prelease -Dgpg.skip=true clean
# verify` with every test and dev/jar-check.sh --release on the jars it builds, commits
# "Release <version>", tags v<version>, and commits the next -SNAPSHOT (with <scm><tag> back
# at HEAD). A failure on the way puts the tree back. It pushes nothing and deploys nothing: it
# prints what the owner runs next: the push of main and the tag, then, once the dry run
# (.github/workflows/release.yml) and CI are green on the tag, `mvn -B -Prelease clean deploy` on
# the tag, which signs with the owner's key and uploads to Central. The jars that deploy builds
# are those this script checked because the build is reproducible (project.build.outputTimestamp
# in pom.xml). AGENTS.md, "Releases".
#
# Its commits end with a Generated-by line, as the project's commits do (CLAUDE.md);
# RELEASE_GENERATED_BY replaces the line's value (default: Claude Code (Sonnet 5.5), the tool
# that wrote this script) and, set to the empty string, leaves the line out.
# Exit status: 0 when it passed, 1 when it refused, 2 for a usage error or a missing tool.
set -euo pipefail
cd "$(dirname "$0")/.."

GENERATED_BY="${RELEASE_GENERATED_BY-Claude Code (Sonnet 5.5)}"

usage() { sed -n '/^# Prepares a release/,/^# Exit status/p' "$0" | sed 's/^# \{0,1\}//'; }
fail() { echo "refusing: $*" >&2; exit 1; }
usage_error() { echo "$*" >&2; exit 2; }

# edit_pom <version> <scm tag> [<timestamp>]: the project's own <version>, its <scm><tag> and,
# when given, project.build.outputTimestamp. The project's children are indented two spaces.
edit_pom() {
  python3 - "$@" <<'PY'
import re
import sys

version, scm_tag = sys.argv[1], sys.argv[2]
timestamp = sys.argv[3] if len(sys.argv) > 3 else None
text = open("pom.xml").read()
text, n = re.subn(r"(\n  <artifactId>[^<]*</artifactId>\n  <version>)[^<]*(</version>)",
                  lambda m: m.group(1) + version + m.group(2), text, count=1)
if n != 1:
    sys.exit("pom.xml: no project <version> to set")
text, n = re.subn(r"(<scm>.*?<tag>)[^<]*(</tag>)", lambda m: m.group(1) + scm_tag + m.group(2),
                  text, count=1, flags=re.S)
if n != 1:
    sys.exit("pom.xml: no <scm><tag> to set")
if timestamp is not None:
    name = r"project\.build\.outputTimestamp"
    text, n = re.subn(r"(<" + name + r">)[^<]*(</" + name + r">)",
                      lambda m: m.group(1) + timestamp + m.group(2), text, count=1)
    if n != 1:
        sys.exit("pom.xml: no project.build.outputTimestamp to set")
open("pom.xml", "w").write(text)
PY
}

# checks <version> <next>: everything a release needs to be true before it starts.
checks() {
  local version="$1" next="$2" tag="v$1" current
  local dotted="${1//./\\.}"   # the version as a regular expression: its dots are dots
  [ "$(git branch --show-current)" = main ] || fail "not on main"
  [ -z "$(git status --porcelain)" ] || fail "the working tree is not clean"
  git fetch -q origin main
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
    || fail "main is not at origin/main (pull, or push what is not pushed)"
  ! git rev-parse -q --verify "refs/tags/$tag" > /dev/null || fail "the tag $tag exists"
  ! git ls-remote --exit-code --tags origin "$tag" > /dev/null 2>&1 \
    || fail "$tag exists on origin"
  grep -q "^## \[$dotted\]" CHANGELOG.md \
    || fail "CHANGELOG.md has no '## [$version]' section: move the Unreleased lines under it"
  grep -q "^\[$dotted\]: " CHANGELOG.md || fail "CHANGELOG.md has no [$version] link line"
  current="$(dev/release-version.sh --version)"
  [[ "$current" == *-SNAPSHOT ]] \
    || fail "the pom is at $current; a release starts from a -SNAPSHOT, which it sets to $version"
  [[ "$next" == *-SNAPSHOT ]] || usage_error "the next version must end in -SNAPSHOT: '$next'"
}

# commit <message>: the project's commit trailer on a commit the script makes.
# Only the two files a release changes: a build that touched another tracked file fails here.
commit() {
  local others
  others="$(git diff --name-only | grep -v -x -e pom.xml -e CHANGELOG.md || true)"
  [ -z "$others" ] || { echo "refusing: the build changed $others" >&2; return 1; }
  if [ -n "$GENERATED_BY" ]; then
    git commit -q pom.xml CHANGELOG.md -m "$1" -m "Generated-by: $GENERATED_BY"
  else
    git commit -q pom.xml CHANGELOG.md -m "$1"
  fi
}

release() {
  local version="$1" next="$2" tag="v$1" start
  command -v mvn > /dev/null || usage_error "mvn is not on the PATH (Java 25 and Maven are needed)"
  # From here on a failure puts the tree back, so a half-made release never stays behind.
  start="$(git rev-parse HEAD)"
  # An interrupt puts the tree back too, not only a failing command.
  trap 'git tag -d "$tag" > /dev/null 2>&1 || true; git reset -q --hard "$start"' ERR
  trap 'trap - ERR; git tag -d "$tag" > /dev/null 2>&1 || true; git reset -q --hard "$start"
        exit 1' INT TERM HUP

  edit_pom "$version" "$tag" "$(date -u +%FT%TZ)"
  # -i.bak and the removal below work in GNU and BSD sed alike.
  sed -i.bak -E "s/^## \[${version//./\\.}\].*/## [$version] - $(date -u +%F)/" CHANGELOG.md
  rm -f CHANGELOG.md.bak
  dev/release-version.sh "$tag" > /dev/null || {
    dev/release-version.sh "$tag" || true
    false
  }
  # Everything, from no stale target; the release profile builds the sources and Javadoc jars,
  # unsigned here: `deploy` on the tag signs them with the owner's key.
  mvn -B -Prelease -Dgpg.skip=true clean verify
  dev/jar-check.sh --release
  commit "Release $version"
  git tag -a "$tag" -m "vfdlibm $version"

  edit_pom "$next" HEAD
  commit "Start $next"
  trap - ERR INT TERM HUP

  cat <<EOF

Release $version is committed and tagged $tag locally; main is at $next.
Review it:   git log --oneline -3 && git show $tag --stat
Then, as the owner (Central token, signing key and GPG_TTY needed, see AGENTS.md, "Releases"):
  git push origin main $tag
  (wait for the Release dry run and CI to go green on the tag)
  git checkout $tag && mvn -B -Prelease clean deploy && git checkout main
  (publish the deployment at the Central portal)
To drop the release before pushing:  git tag -d $tag && git reset --hard $start
EOF
}

self_test() {
  command -v python3 > /dev/null || usage_error "no python3 on PATH, which --self-test needs"
  local root tmp failed=0 here
  root="$(pwd)"
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064  # $tmp is a local, gone when the trap runs: expand it now
  trap "rm -rf '$tmp'" EXIT
  export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com
  export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com
  # A stub mvn and jar check: this tests the script's flow, not a build.
  mkdir "$tmp/bin"
  printf '#!/bin/sh\n[ "${STUB_MVN_TERM:-}" != 1 ] || { kill -TERM "$PPID"; sleep 2; }\n' \
    > "$tmp/bin/mvn"
  printf '[ "${STUB_MVN_FAILS:-}" != 1 ]\n' >> "$tmp/bin/mvn"
  chmod +x "$tmp/bin/mvn"

  # scratch <name>: a bare origin and a clone of it on main, holding the scripts, a minimal pom
  # at 0.1.0-SNAPSHOT and a changelog with a 0.1.0 section; the clone is $tmp/<name>.
  scratch() {
    git init -q --bare -b main "$tmp/$1.git"
    git clone -q "$tmp/$1.git" "$tmp/$1" 2> /dev/null
    here="$tmp/$1"
    mkdir "$here/dev"
    cp "$root/dev/release.sh" "$root/dev/release-version.sh" "$here/dev/"
    printf '#!/bin/sh\nexit 0\n' > "$here/dev/jar-check.sh"
    chmod +x "$here/dev/jar-check.sh"
    cat > "$here/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>g</groupId>
  <artifactId>a</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scm>
    <tag>HEAD</tag>
  </scm>
  <properties>
    <project.build.outputTimestamp>2026-01-01T00:00:00Z</project.build.outputTimestamp>
  </properties>
  <dependencies>
    <dependency>
      <artifactId>d</artifactId>
      <version>9</version>
    </dependency>
  </dependencies>
</project>
POM
    printf '# Changelog\n\n## [Unreleased]\n\n## [0.1.0] - unreleased\n\n' > "$here/CHANGELOG.md"
    printf '[0.1.0]: https://x/v0.1.0\n' >> "$here/CHANGELOG.md"
    (cd "$here" && git checkout -q -b main 2> /dev/null; git add -A && git commit -q -m init \
      && git push -q origin main 2> /dev/null)
  }

  # expect <name> <wanted status> <what it must print> <arguments...>, run in $here.
  expect() {
    local name="$1" want="$2" text="$3" status out
    shift 3
    set +e
    out="$(cd "$here" && PATH="$tmp/bin:$PATH" dev/release.sh "$@" 2>&1)"
    status=$?
    set -e
    if [ "$status" -eq "$want" ] && grep -qF -- "$text" <<< "$out"; then
      echo "ok    $name: exit $status, with \"$text\""
    else
      echo "FAIL  $name: exit $status, wanted $want and \"$text\":"
      sed 's/^/        /' <<< "$out"
      failed=$((failed + 1))
    fi
  }

  scratch pass;       expect "a good main passes" 0 "the checks pass for 0.1.0" --check 0.1.0
  scratch format;     expect "a version that is not MAJOR.MINOR.PATCH" 2 "must be MAJOR" \
                        --check 1.0
  scratch next;       expect "a next version that is no snapshot" 2 "must end in -SNAPSHOT" \
                        --check 0.1.0 0.2.0
  scratch branch;     (cd "$here" && git checkout -q -b topic)
                      expect "a branch that is not main" 1 "not on main" --check 0.1.0
  scratch dirty;      echo x >> "$here/CHANGELOG.md"
                      expect "a dirty tree" 1 "not clean" --check 0.1.0
  scratch ahead;      (cd "$here" && echo x >> CHANGELOG.md && git commit -q -am more)
                      expect "a main that is not at origin/main" 1 "origin/main" --check 0.1.0
  scratch tag;        (cd "$here" && git tag v0.1.0)
                      expect "a tag that exists" 1 "the tag v0.1.0 exists" --check 0.1.0
  scratch remotetag;  (cd "$here" && git tag v0.1.0 && git push -q origin v0.1.0 2> /dev/null \
                        && git tag -d v0.1.0 > /dev/null)
                      expect "a tag that exists on origin" 1 "exists on origin" --check 0.1.0
  scratch nosection;  (cd "$here" && sed -i 's/^## \[0.1.0\].*//' CHANGELOG.md \
                        && git commit -q -am x && git push -q origin main 2> /dev/null)
                      expect "no changelog section" 1 "no '## [0.1.0]' section" --check 0.1.0
  scratch nolink;     (cd "$here" && sed -i 's/^\[0.1.0\]: .*//' CHANGELOG.md \
                        && git commit -q -am x && git push -q origin main 2> /dev/null)
                      expect "no changelog link line" 1 "no [0.1.0] link line" --check 0.1.0
  scratch dots;       (cd "$here" && sed -i 's/^## \[0.1.0\].*/## [0x1x0] - x/' CHANGELOG.md \
                        && git commit -q -am x && git push -q origin main 2> /dev/null)
                      expect "a heading that matches only if dots are any character" 1 \
                        "no '## [0.1.0]' section" --check 0.1.0
  scratch pomver;     (cd "$here" && sed -i 's/>0.1.0-SNAPSHOT</>0.9.9</' pom.xml \
                        && git commit -q -am x && git push -q origin main 2> /dev/null)
                      expect "a pom that is no snapshot" 1 "the pom is at 0.9.9" \
                        --check 0.1.0

  # The release itself, under the stub mvn: a commit, a tag, the next snapshot, nothing pushed.
  scratch release;    expect "a release" 0 "Release 0.1.0 is committed and tagged v0.1.0" 0.1.0
  local log
  log="$(cd "$here" && git log --format=%s -3 | tr '\n' '|')"
  if [ "$log" = "Start 0.1.1-SNAPSHOT|Release 0.1.0|init|" ] \
      && (cd "$here" && git show v0.1.0:pom.xml | grep -q '<version>0.1.0</version>' \
        && git show v0.1.0:pom.xml | grep -q '<tag>v0.1.0</tag>' \
        && ! git show v0.1.0:pom.xml | grep -q '2026-01-01T00:00:00Z' \
        && git show v0.1.0:CHANGELOG.md | grep -q '^## \[0.1.0\] - 20' \
        && git log -1 --format=%B v0.1.0 | grep -q '^Generated-by: ' \
        && git show HEAD:pom.xml | grep -q '<version>0.1.1-SNAPSHOT</version>' \
        && git show HEAD:pom.xml | grep -q '<tag>HEAD</tag>' \
        && git show HEAD:pom.xml | grep -q '<version>9</version>' \
        && [ "$(git rev-parse origin/main)" = "$(git rev-parse HEAD~2)" ]); then
    echo "ok    the release's commits, tag, pom, changelog and trailer are as they should be"
  else
    echo "FAIL  the release's commits, tag, pom, changelog or trailer are not as they should be"
    (cd "$here" && git log --format='%h %s' -4)
    failed=$((failed + 1))
  fi
  scratch failing;    STUB_MVN_FAILS=1 expect "a failing build" 1 "" 0.1.0
  if [ -z "$(cd "$here" && git status --porcelain)" ] \
      && [ "$(cd "$here" && git rev-parse HEAD)" = "$(cd "$here" && git rev-parse origin/main)" ] \
      && ! (cd "$here" && git rev-parse -q --verify refs/tags/v0.1.0 > /dev/null); then
    echo "ok    a failing build leaves the tree, the branch and the tags as they were"
  else
    echo "FAIL  a failing build left something behind"
    failed=$((failed + 1))
  fi

  scratch term;       STUB_MVN_TERM=1 expect "an interrupted build" 1 "" 0.1.0
  if [ -z "$(cd "$here" && git status --porcelain)" ] \
      && [ "$(cd "$here" && git rev-parse HEAD)" = "$(cd "$here" && git rev-parse origin/main)" ] \
      && ! (cd "$here" && git rev-parse -q --verify refs/tags/v0.1.0 > /dev/null); then
    echo "ok    an interrupted build leaves the tree, the branch and the tags as they were"
  else
    echo "FAIL  an interrupted build left something behind"
    failed=$((failed + 1))
  fi

  if [ "$failed" -ne 0 ]; then
    echo "release.sh: self-test: $failed failed"
    exit 1
  fi
  echo "release.sh: self-test passed"
}

case "${1:-}" in
  -h|--help|"") usage; exit 0 ;;
  --self-test) [ $# -eq 1 ] || usage_error "--self-test takes no argument"; self_test; exit 0 ;;
esac
check_only=false
if [ "$1" = "--check" ]; then check_only=true; shift; fi
version="${1:?a version, such as 0.1.0}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || usage_error "version must be MAJOR.MINOR.PATCH, not '$version'"
if [ -n "${2:-}" ]; then
  next="$2"
else
  next="${version%.*}.$(( ${version##*.} + 1 ))-SNAPSHOT"
fi
[[ "$next" == *-SNAPSHOT ]] || usage_error "the next version must end in -SNAPSHOT: '$next'"
command -v python3 > /dev/null || usage_error "python3 is not on the PATH"

checks "$version" "$next"
if $check_only; then
  echo "the checks pass for $version (next: $next); nothing was changed"
  exit 0
fi
release "$version" "$next"
