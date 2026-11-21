#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Runs the worked client example of README.md's "Use" section as written, against the latest
# release of the library on Maven Central, and compares the output with the README's:
#
#   dev/readme-check.sh [<readme>]     the example against Central's latest release
#   dev/readme-check.sh --self-test    the script's own checks, offline, with planted defects
#
# The README's fenced blocks, in order, are the client's pom.xml, its Main.java, the commands
# that build and run it on the class path, the output they print, a module-info.java, and the
# commands for the module path. The script writes the pom (its vfdlibm version replaced by the
# latest release in Central's maven-metadata.xml, not the pom.xml of this checkout), the
# sources and the commands into an empty directory, and runs each command block unchanged in it
# with `mvn` replaced by a shim that adds an empty local Maven repository, so the jar can only
# come from Central; the repository's own record of where the jar came from must say Central.
# The JVM's warning that an incubator module is in use goes to stderr and is not compared; the
# vector width is the host's, so `at 512 bits` in the README stands for any width.
#
# It checks the README only after a release: it cannot check a README whose version is not on
# Central yet. A weekly workflow runs it (.github/workflows/readme-check.yml), and a person can
# run it by hand. It needs python3, mvn and a JDK 25 (java and javac) on the PATH, and writes
# under a temporary directory it removes. Exit status: 0 when the output is the README's, 1 when
# it is not or a command of the README failed (for whatever cause, a failed download of Maven's
# included), 2 when it could not run (Central's metadata unreachable, a tool missing, a README
# that is not in the shape above).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

# The program that reads the README, as Python: the blocks, the files they make, the comparison.
PY="$(cat <<'PYEOF'
import http.client
import re
import sys
import time
import urllib.request

METADATA = 'https://repo1.maven.org/maven2/io/github/vecbricks/vfdlibm/maven-metadata.xml'
KINDS = ['xml', 'java', 'sh', '', 'java', 'sh']


def die(message, status=2):
    print('readme-check: ' + message, file=sys.stderr)
    sys.exit(status)


def blocks(readme):
    text = open(readme).read()
    use = re.search(r'^## Use$', text, re.M)
    why = re.search(r'^## Why$', text[use.end():], re.M) if use else None
    if not use or not why:
        die('%s has no "## Use" heading followed by "## Why"' % readme)
    found = re.findall(r'```(\w*)\n(.*?)```', text[use.end():use.end() + why.start()], re.S)
    kinds = [kind for kind, _ in found]
    if kinds != KINDS:
        die('the fenced blocks of "Use" are %s, not %s' % (kinds, KINDS))
    return [body for _, body in found]


def prepare(readme, directory, version):
    pom, main, sh1, expected, module, sh2 = blocks(readme)
    dependency = r'(<artifactId>vfdlibm</artifactId>\s*<version>)[^<]*(</version>)'
    pom, n = re.subn(dependency, lambda m: m.group(1) + version + m.group(2), pom)
    if n != 1:
        die("the README's pom.xml has no vfdlibm dependency with a version")
    import os
    os.makedirs(directory + '/src/main/java/example', exist_ok=True)
    files = {
        'pom.xml': pom,
        'src/main/java/example/Main.java': main,
        'sh1.sh': sh1,
        'expected.txt': expected,
        'module-info.java': module,
        'sh2.sh': sh2,
    }
    for name, body in files.items():
        open(directory + '/' + name, 'w').write(body)


def latest():
    # Central answers 403 and 429 at times: try a few times before giving up.
    last = None
    request = urllib.request.Request(METADATA, headers={'User-Agent': 'vfdlibm-readme-check'})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                xml = response.read().decode()
            m = re.search(r'<release>([^<]+)</release>', xml)
            if m:
                return m.group(1)
            die('Central\'s maven-metadata.xml has no <release>')
        except (OSError, http.client.HTTPException) as e:
            last = e
            if attempt < 3:
                time.sleep(10 * (attempt + 1))
    die('cannot read %s: %s' % (METADATA, last))


def lines(text):
    return [line.rstrip() for line in text.splitlines() if line.strip()]


def compare(expected_file, actual_file):
    expected = lines(open(expected_file).read())
    actual = lines(open(actual_file).read())
    m = re.search(r' at (\d+) bits', actual[0]) if actual else None
    if m:
        expected = [line.replace(' at 512 bits', ' at ' + m.group(1) + ' bits')
                    for line in expected]
    if expected == actual:
        print('ok    the output is the README\'s (%d lines)' % len(actual))
        return
    print('FAIL  the output is not the README\'s:')
    print('  README says:')
    for line in expected:
        print('    ' + line)
    print('  the run printed:')
    for line in actual:
        print('    ' + line)
    sys.exit(1)


command = sys.argv[1]
if command == 'latest':
    print(latest())
elif command == 'prepare':
    prepare(sys.argv[2], sys.argv[3], sys.argv[4])
elif command == 'compare':
    compare(sys.argv[2], sys.argv[3])
else:
    die('unknown command ' + command)
PYEOF
)"

py() { python3 -I -c "$PY" "$@"; }

die() { echo "readme-check: $*" >&2; exit 2; }

# A mvn that adds an empty local repository to every call, so that the README's commands run as
# written and the jar can only come from Central. $1 is the directory of the shim.
make_shim() {
  local real
  real="$(command -v mvn)" || die "no mvn on PATH"
  mkdir -p "$1/bin"
  cat > "$1/bin/mvn" <<EOF
#!/usr/bin/env bash
exec "$real" -B --no-transfer-progress -Dmaven.repo.local="$1/m2-empty" "\$@"
EOF
  chmod +x "$1/bin/mvn"
}

self_test() {
  command -v python3 > /dev/null || die "no python3 on PATH"
  local tmp failed=0
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064  # $tmp is local, gone when the trap runs: expand it now
  trap "rm -rf '$tmp'" EXIT
  expect() { # <what> <wanted status> <text the output must hold> <command...>
    local what="$1" want="$2" text="$3" status out
    shift 3
    set +e
    out="$("$@" 2>&1)"
    status=$?
    set -e
    if [ "$status" -eq "$want" ] && grep -qF -- "$text" <<< "$out"; then
      echo "ok    $what: exit $status, with \"$text\""
    else
      echo "FAIL  $what: exit $status, wanted $want and \"$text\":"
      sed 's/^/        /' <<< "$out"
      failed=$((failed + 1))
    fi
  }

  # The README as it is: its blocks make the files, and the version is the one given.
  expect "the README's blocks are read" 0 "" py prepare "$root/README.md" "$tmp/good" 9.9.9
  if grep -q '<version>9.9.9</version>' "$tmp/good/pom.xml" \
      && grep -q 'VFdlibm.exp' "$tmp/good/src/main/java/example/Main.java" \
      && grep -q 'maven-dependency-plugin' "$tmp/good/sh1.sh" \
      && grep -q 'requires jdk.incubator.vector' "$tmp/good/module-info.java"; then
    echo "ok    the pom takes the version given, and the sources and commands are the README's"
  else
    echo "FAIL  the files made from the README are not what they should be"
    failed=$((failed + 1))
  fi

  # The same output, at another width, passes; one planted digit does not.
  sed 's/ at 512 bits/ at 256 bits/' "$tmp/good/expected.txt" > "$tmp/at256.txt"
  expect "the README's output at another width passes" 0 "ok    the output is the README" \
    py compare "$tmp/good/expected.txt" "$tmp/at256.txt"
  sed 's/1\.6487212707001282/1.6487212707001283/' "$tmp/at256.txt" > "$tmp/digit.txt"
  expect "an output with one digit changed fails" 1 "FAIL  the output is not the README" \
    py compare "$tmp/good/expected.txt" "$tmp/digit.txt"
  sed '$d' "$tmp/at256.txt" > "$tmp/short.txt"
  expect "an output with a line missing fails" 1 "FAIL  the output is not the README" \
    py compare "$tmp/good/expected.txt" "$tmp/short.txt"

  # A README with one planted defect in a block: the README's own output no longer matches what
  # the unchanged program prints, and a README that is not in the shape is refused.
  sed 's/the computed lanes have StrictMath/every lane has StrictMath/' "$root/README.md" \
    > "$tmp/README-wrong-output.md"
  py prepare "$tmp/README-wrong-output.md" "$tmp/wrong" 9.9.9
  expect "a README whose output block was changed fails against the real output" 1 \
    "FAIL  the output is not the README" py compare "$tmp/wrong/expected.txt" "$tmp/at256.txt"
  sed '0,/^```java$/s//```kotlin/' "$root/README.md" > "$tmp/README-wrong-fence.md"
  expect "a README whose Main.java block has another kind is refused" 2 \
    "the fenced blocks of \"Use\"" py prepare "$tmp/README-wrong-fence.md" "$tmp/x" 9.9.9
  sed 's/<artifactId>vfdlibm<\/artifactId>/<artifactId>other<\/artifactId>/' "$root/README.md" \
    > "$tmp/README-no-dependency.md"
  expect "a README whose pom has no vfdlibm dependency is refused" 2 \
    "no vfdlibm dependency" py prepare "$tmp/README-no-dependency.md" "$tmp/y" 9.9.9

  # The shim: every mvn call of the README gets the empty repository. A stand-in for mvn is
  # enough, so the self-test needs no Maven.
  mkdir "$tmp/fake"
  printf '#!/bin/sh\nexit 0\n' > "$tmp/fake/mvn"
  chmod +x "$tmp/fake/mvn"
  PATH="$tmp/fake:$PATH" make_shim "$tmp/shim"
  if grep -q 'Dmaven.repo.local=' "$tmp/shim/bin/mvn" && [ -x "$tmp/shim/bin/mvn" ]; then
    echo "ok    the mvn shim adds an empty local repository"
  else
    echo "FAIL  the mvn shim is wrong"
    failed=$((failed + 1))
  fi

  if [ "$failed" -ne 0 ]; then
    echo "readme-check: self-test: $failed failed"
    exit 1
  fi
  echo "readme-check: self-test passed"
}

case "${1:-}" in
  -h|--help) sed -n '/^# Runs the worked/,/^[^#]/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
             exit 0 ;;
  --self-test) [ $# -eq 1 ] || die "--self-test takes no argument"; self_test; exit 0 ;;
  -*) die "unknown option $1" ;;
esac
[ $# -le 1 ] || die "usage: dev/readme-check.sh [<readme>]"
readme="${1:-$root/README.md}"
[ -f "$readme" ] || die "no such file: $readme"
for tool in python3 java javac mvn; do
  command -v "$tool" > /dev/null || die "no $tool on PATH"
done

version="$(py latest)"
echo "readme-check: the latest release on Central is $version"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
make_shim "$work"
py prepare "$readme" "$work" "$version"
export PATH="$work/bin:$PATH"

run() { # <commands file> <output file>
  (cd "$work" && bash -e "$1" > "$2") || {
    echo "FAIL  a command of the README failed (see above); it printed:"
    sed 's/^/        /' "$2"
    exit 1
  }
}

echo "== the class path"
run "$work/sh1.sh" "$work/out1.txt"
py compare "$work/expected.txt" "$work/out1.txt"
remote="$work/m2-empty/io/github/vecbricks/vfdlibm/$version/_remote.repositories"
if grep -qF "vfdlibm-$version.jar>central=" "$remote" 2> /dev/null; then
  echo "ok    the jar came from Central (its _remote.repositories says central)"
else
  echo "FAIL  the jar did not come from Central: $remote says:"
  sed 's/^/        /' "$remote" 2> /dev/null || echo "        (no such file)"
  exit 1
fi

echo "== the module path"
cp "$work/module-info.java" "$work/src/main/java/module-info.java"
run "$work/sh2.sh" "$work/out2.txt"
py compare "$work/expected.txt" "$work/out2.txt"
echo "readme-check: passed against vfdlibm $version"
