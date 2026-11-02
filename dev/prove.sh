#!/usr/bin/env bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Adapted from vecbricks/varka's dev/varka_prove.sh, the runner of its proofs.
#
# The SMT proofs under proofs/, run by a solver with every verdict checked (proofs/README.md).
#
#   dev/prove.sh                          # every proof under Z3
#   dev/prove.sh --lint                   # as CI runs them: CVC5_IN_LINT's under cvc5, every
#                                         # other under Z3
#   dev/prove.sh --solver both            # under Z3 and cvc5, each held to every expectation
#   dev/prove.sh --solver cvc5 sign_symmetry.smt2
#   dev/prove.sh --install z3|cvc5|both   # the pinned solvers, into target/solvers/
#   dev/prove.sh --self-test              # one planted failure of each kind, every one caught
#
# A proof opens with its (set-logic ...) line, and java.smt2, Java's operators, is inserted after
# it, since SMT-LIB has no include. Each check is an (echo "<what it shows>: expect sat|unsat")
# followed by its (check-sat), so every verdict is held to the expectation before it. A run fails
# on a verdict other than the expected one; on unknown, which is also what a check past its time
# limit answers; on any output line that is neither an expectation nor a verdict, since Z3 goes on
# answering after an error, and an assertion it dropped can turn a refutation into sat; on fewer
# verdicts than the file has checks; and on a solver that is asked for and is missing or not at
# its pinned version. Under --solver both every check must meet its expectation under each
# solver, so the two agree.
#
# A solver is taken from $VFDLIBM_Z3 or $VFDLIBM_CVC5, else from target/solvers/, else from PATH.
# VFDLIBM_PROVE_LIMIT_MS bounds each check (default 10000) and VFDLIBM_PROVE_RUN_LIMIT each file's
# run under one solver, in seconds (default 300). Exit status 0 when every proof holds.
#
# The solvers are development tools: nothing here is compiled, packaged or shipped.
set -uo pipefail
# Usage text is found rather than numbered: from the line that opens it to the first line that is
# not a comment.
usage() { sed -n '/^# The SMT proofs/,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

root="$(cd "$(dirname "$0")/.." && pwd)"
proofs="$root/proofs"
solvers="$root/target/solvers"
# The pinned solvers, Varka's pins. Z3 from PyPI, whose wheel ships the z3 binary; cvc5 from its
# release archive, since its wheel has the Python bindings only.
Z3_VERSION=5.1.0
# The SHA-256 of each z3-solver 5.1.0.0 wheel pinned, from PyPI's JSON API
# (https://pypi.org/pypi/z3-solver/5.1.0.0/json), by the platform `uname -s`-`uname -m` names:
# z3_solver-5.1.0.0-py3-none-<tag>.whl with tag manylinux_2_27_x86_64, manylinux_2_38_aarch64,
# macosx_13_0_arm64 and macosx_13_0_x86_64. pip takes any of them that matches.
declare -A Z3_WHEEL_SHA256=(
  [Linux-x86_64]=dfad9e309d7010b1ff6bdb33f21570a1603ef4727373221c7117a74448f0cfef
  [Linux-aarch64]=1dcfcccb4b027951d7adf267b5b236e7a67b8433cc358d802dcb0280151587bf
  [Darwin-arm64]=399a38a85d784105e5df5a05c04a581481bfdb80af7424779cf76fa843b4e66c
  [Darwin-x86_64]=069feb3ecfa0cbf40524b6a06bf0a02d604e1710bd42012ac197e2e343e39cc0
)
CVC5_VERSION=1.4.1
CVC5_ZIP=cvc5-Linux-x86_64-static.zip
CVC5_SHA256=2f8efe58fe27ba7bccbb504533f690b9312d69da14192712460e4a19231f02a1
# The proofs --lint runs under cvc5 rather than Z3, for a file one solver proves much faster than
# the other. None yet; --lint needs cvc5 installed only when this names a file.
CVC5_IN_LINT=()
limit_ms="${VFDLIBM_PROVE_LIMIT_MS:-10000}"
run_limit="${VFDLIBM_PROVE_RUN_LIMIT:-300}"

# ---------------------------------------------------------------------------------------------
# The solvers.
# ---------------------------------------------------------------------------------------------

find_solver() {
  local name="$1" given pinned
  case "$name" in
    z3) given="${VFDLIBM_Z3:-}"; pinned="$solvers/z3-$Z3_VERSION/bin/z3" ;;
    cvc5) given="${VFDLIBM_CVC5:-}"; pinned="$solvers/cvc5-$CVC5_VERSION/bin/cvc5" ;;
  esac
  if [ -n "$given" ]; then
    [ -x "$given" ] && echo "$given"
    return 0
  fi
  if [ -x "$pinned" ]; then
    echo "$pinned"
    return 0
  fi
  command -v "$name" || true
}

# Prints the solver's path, or says why there is none and fails.
require_solver() {
  local name="$1" path version want
  path="$(find_solver "$name")"
  if [ -z "$path" ]; then
    echo "prove: $name is not installed; run dev/prove.sh --install $name," \
      "or point VFDLIBM_${name^^} at one" >&2
    return 1
  fi
  case "$name" in
    z3) version="$("$path" --version | awk '{print $3}')"; want="$Z3_VERSION" ;;
    cvc5) version="$("$path" --version | awk 'NR == 1 {print $2}')"; want="$CVC5_VERSION" ;;
  esac
  if [ "$version" != "$want" ]; then
    echo "prove: $path is $name $version, and the proofs are pinned to $want;" \
      "run dev/prove.sh --install $name" >&2
    return 1
  fi
  echo "$path"
}

# A wheel only, never a build from source, and only the wheel whose hash is pinned.
install_z3() {
  local dir="$solvers/z3-$Z3_VERSION"
  [ -x "$dir/bin/z3" ] && return 0
  local platform hashes="" h
  platform="$(uname -s)-$(uname -m)"
  if [ -z "${Z3_WHEEL_SHA256[$platform]:-}" ]; then
    echo "prove: no Z3 wheel is pinned for $platform; install Z3 $Z3_VERSION" \
      "and point VFDLIBM_Z3 at it" >&2
    return 1
  fi
  for h in "${Z3_WHEEL_SHA256[@]}"; do hashes+=" --hash=sha256:$h"; done
  python3 -m venv "$dir" \
    && echo "z3-solver==$Z3_VERSION.0$hashes" > "$dir/requirements.txt" \
    && "$dir/bin/pip" install --quiet --disable-pip-version-check --only-binary=:all: \
      --require-hashes -r "$dir/requirements.txt"
}

install_cvc5() {
  local dir="$solvers/cvc5-$CVC5_VERSION" tmp status
  [ -x "$dir/bin/cvc5" ] && return 0
  if [ "$(uname -s)-$(uname -m)" != Linux-x86_64 ]; then
    echo "prove: the pinned cvc5 build is Linux x86_64's; install cvc5 $CVC5_VERSION" \
      "and point VFDLIBM_CVC5 at it" >&2
    return 1
  fi
  tmp="$(mktemp -d)"
  curl -sSfL -o "$tmp/$CVC5_ZIP" \
      "https://github.com/cvc5/cvc5/releases/download/cvc5-$CVC5_VERSION/$CVC5_ZIP" \
    && echo "$CVC5_SHA256  $tmp/$CVC5_ZIP" | sha256sum -c --quiet \
    && unzip -q "$tmp/$CVC5_ZIP" -d "$tmp" \
    && mkdir -p "$dir/bin" && mv "$tmp/${CVC5_ZIP%.zip}/bin/cvc5" "$dir/bin/cvc5"
  status=$?
  rm -rf "$tmp"
  return "$status"
}

# ---------------------------------------------------------------------------------------------
# One file under one solver.
# ---------------------------------------------------------------------------------------------

# The verdicts against the expectations. Reads the solver's output; prints one line per failure
# and then "checks <n>"; fails when anything failed.
read_verdicts() {
  awk -v expected="$1" -v status="$2" '
    BEGIN { checks = 0; bad = 0; pending = "" }
    {
      line = $0
      if (line == "") next
      # cvc5 prints an echo in quotes, Z3 bare.
      if (line ~ /^".*"$/) line = substr(line, 2, length(line) - 2)
      if (line ~ /: expect (sat|unsat)$/) {
        if (pending != "") { print "    no verdict for \"" pending "\""; bad++ }
        pending = line
        next
      }
      if (line == "sat" || line == "unsat") {
        if (pending == "") {
          print "    a verdict with no expectation before it: " line
          bad++
          next
        }
        want = pending
        sub(/.*: expect /, "", want)
        checks++
        if (line != want) {
          print "    " line " where " want " was expected: \"" pending "\""
          bad++
        }
        pending = ""
        next
      }
      # unknown, a timeout, an error, a warning: none of them is a verdict.
      print "    " (pending != "" ? "at \"" pending "\": " : "") $0
      bad++
      if (line == "unknown" || line == "timeout") { checks++; pending = "" }
    }
    END {
      if (pending != "") { print "    no verdict for \"" pending "\""; bad++ }
      if (checks != expected) { print "    " checks " verdicts for " expected " checks"; bad++ }
      if (status != 0) {
        print "    the solver exited with status " status \
          (status == 124 ? ", past the run limit" : "")
        bad++
      }
      print "checks " checks
      exit (bad > 0)
    }'
}

# run_file SOLVER PATH FILE [PRELUDE]: runs FILE, with PRELUDE (java.smt2 unless given, none when
# empty) inserted after its set-logic line, prints a line for it, and fails if anything failed.
run_file() {
  local name="$1" solver="$2" file="$3" prelude="${4-$proofs/java.smt2}"
  local input expected start out status report rc ms checks
  input="$(mktemp --suffix=.smt2)"
  if ! awk -v prelude="$prelude" '
      { print }
      /^\(set-logic / && !done {
        if (prelude != "") { while ((getline l < prelude) > 0) print l }
        done = 1
      }
      END { exit !done }' "$file" > "$input"; then
    printf '%-5s %-28s no (set-logic ...) line to insert the prelude after\n' \
      "$name" "$(basename "$file")"
    rm -f "$input"
    return 1
  fi
  expected="$(grep -c -E '^\(echo ".*: expect (sat|unsat)"\)$' "$file")"
  local -a args
  case "$name" in
    z3) args=(-smt2 "-t:$limit_ms" "$input") ;;
    cvc5) args=(--lang=smt2 --incremental "--tlimit-per=$limit_ms" "$input") ;;
  esac
  start="$(date +%s%N)"
  out="$(timeout "$run_limit" "$solver" "${args[@]}" 2>&1)"
  status=$?
  ms=$((($(date +%s%N) - start) / 1000000))
  rm -f "$input"
  report="$(printf '%s\n' "$out" | read_verdicts "$expected" "$status")"
  rc=$?
  checks="$(printf '%s\n' "$report" | tail -1 | awk '{print $2}')"
  printf '%-5s %-28s %4s checks %4d.%02d s  %s\n' "$name" "$(basename "$file")" "$checks" \
    $((ms / 1000)) $((ms % 1000 / 10)) "$([ "$rc" -eq 0 ] && echo holds || echo FAILED)"
  printf '%s\n' "$report" | sed '$d'
  return "$rc"
}

# ---------------------------------------------------------------------------------------------
# The self-test: the checker of verdicts, checked.
# ---------------------------------------------------------------------------------------------

self_test() {
  local name="$1" solver="$2" dir bad=0
  dir="$(mktemp -d)"
  cat > "$dir/control.smt2" <<'EOF'
(set-logic QF_NIA)
(echo "int addition wraps at the top of the range: expect unsat")
(push 1)
(assert (not (= (jint.add jint.MAX 1) jint.MIN)))
(check-sat)
(pop 1)
(echo "an int can be zero: expect sat")
(push 1)
(declare-const x Int)
(assert (and (jint.in x) (= x 0)))
(check-sat)
(pop 1)
EOF
  cat > "$dir/wrong.smt2" <<'EOF'
(set-logic QF_NIA)
(declare-const x Int)
(echo "a satisfiable check, expected to be refuted: expect unsat")
(push 1)
(assert (= x 1))
(check-sat)
(pop 1)
EOF
  # An undeclared symbol: Z3 reports the error, drops the assertion and answers sat - which
  # meets the expectation, so only the error line can fail this run.
  cat > "$dir/error.smt2" <<'EOF'
(set-logic QF_NIA)
(declare-const x Int)
(echo "an assertion the solver rejects: expect sat")
(push 1)
(assert (= y 1))
(check-sat)
(pop 1)
EOF
  # The bit-vector form of a multiply-high division by 12, which no solver Varka tried finishes in
  # five minutes, under a one-second limit: the solver answers unknown.
  cat > "$dir/limit.smt2" <<'EOF'
(set-logic QF_BV)
(declare-const n (_ BitVec 32))
(echo "a check no solver finishes inside the limit: expect unsat")
(push 1)
(assert (not (= (bvadd ((_ extract 31 0) (bvashr (bvmul ((_ sign_extend 32) n)
  #x000000002aaaaaab) #x0000000000000021)) (bvlshr n #x0000001f)) (bvsdiv n #x0000000c))))
(check-sat)
(pop 1)
EOF
  cat > "$dir/missing.smt2" <<'EOF'
(set-logic QF_NIA)
(echo "a check whose verdict never comes: expect sat")
EOF
  # A check that holds, then a check-sat with no expectation before it, whose sat is unmatched.
  cat > "$dir/unexpected.smt2" <<'EOF'
(set-logic QF_NIA)
(echo "an int can be zero: expect sat")
(push 1)
(declare-const x Int)
(assert (and (jint.in x) (= x 0)))
(check-sat)
(pop 1)
(push 1)
(check-sat)
(pop 1)
EOF
  # The real solver, but answering --version with another version in the field require_solver
  # reads, so only the version check can fail a run with it.
  local old
  case "$name" in
    z3) old="Z3 version 4.8.0 - 64 bit" ;;
    cvc5) old="cvc5 1.0.0 [git 0000000 on branch HEAD]" ;;
  esac
  printf '#!/bin/sh\n[ "$1" = --version ] && { echo "%s"; exit 0; }\nexec "%s" "$@"\n' \
    "$old" "$solver" > "$dir/old-solver"
  chmod +x "$dir/old-solver"
  expect_run() {
    local what="$1" want="$2"
    shift 2
    if "$@" > "$dir/out" 2>&1; then got=holds; else got=fails; fi
    if [ "$got" = "$want" ]; then
      printf '%-5s ok    %s\n' "$name" "$what"
    else
      printf '%-5s WRONG %s: the run %s\n' "$name" "$what" "$got"
      sed 's/^/      /' "$dir/out"
      bad=$((bad + 1))
    fi
  }
  expect_run "a file whose checks hold, holds" holds run_file "$name" "$solver" "$dir/control.smt2"
  expect_run "a verdict against its expectation fails" fails \
    run_file "$name" "$solver" "$dir/wrong.smt2"
  expect_run "a solver error fails, whatever verdict follows it" fails \
    run_file "$name" "$solver" "$dir/error.smt2"
  # Without the prelude, whose integers a QF_BV file cannot declare.
  past_limit() {
    local limit_ms=1000
    run_file "$name" "$solver" "$dir/limit.smt2" ""
  }
  expect_run "a check past its time limit fails" fails past_limit
  expect_run "an expectation with no verdict fails" fails \
    run_file "$name" "$solver" "$dir/missing.smt2"
  expect_run "a verdict with no expectation before it fails" fails \
    run_file "$name" "$solver" "$dir/unexpected.smt2"
  expect_run "a missing solver fails" fails \
    env "VFDLIBM_${name^^}=$dir/no-such-solver" "$root/dev/prove.sh" --solver "$name" \
    "$dir/control.smt2"
  expect_run "a solver at another version fails" fails \
    env "VFDLIBM_${name^^}=$dir/old-solver" "$root/dev/prove.sh" --solver "$name" \
    "$dir/control.smt2"
  rm -rf "$dir"
  return "$bad"
}

# ---------------------------------------------------------------------------------------------
# The command line.
# ---------------------------------------------------------------------------------------------

mode=prove
which=z3
files=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --solver) which="${2:-}"; shift 2 || usage ;;
    --install) mode=install; which="${2:-}"; shift 2 || usage ;;
    --self-test) mode=self-test; shift ;;
    --lint) mode=lint; which=lint; shift ;;
    -*) usage ;;
    *) files+=("$1"); shift ;;
  esac
done
case "$which" in
  z3|cvc5) names=("$which") ;;
  both) names=(z3 cvc5) ;;
  # Z3, and cvc5 only when CVC5_IN_LINT names a file, so CI installs no more than it runs.
  lint) names=(z3); [ "${#CVC5_IN_LINT[@]}" -gt 0 ] && names+=(cvc5) ;;
  *) usage ;;
esac

if [ "$mode" = install ]; then
  for name in "${names[@]}"; do
    "install_$name" || { echo "prove: installing $name failed" >&2; exit 1; }
    path="$(require_solver "$name")" || exit 1
    echo "$name $("$path" --version | head -1 | tr -s ' ') at $path"
  done
  exit 0
fi

declare -A paths
for name in "${names[@]}"; do
  paths[$name]="$(require_solver "$name")" || exit 1
done

if [ "$mode" = self-test ]; then
  failed=0
  for name in "${names[@]}"; do
    self_test "$name" "${paths[$name]}" || failed=$((failed + 1))
  done
  [ "$failed" -eq 0 ] && echo "prove: the self-test caught every planted failure"
  exit "$failed"
fi

if [ "${#files[@]}" -eq 0 ]; then
  for f in "$proofs"/*.smt2; do
    [ "$(basename "$f")" = java.smt2 ] || files+=("$f")
  done
fi
failed=0
if [ "$mode" = lint ]; then
  for f in "${files[@]}"; do
    [ -f "$f" ] || f="$proofs/$f"
    name=z3
    for c in "${CVC5_IN_LINT[@]}"; do [ "$(basename "$f")" = "$c" ] && name=cvc5; done
    run_file "$name" "${paths[$name]}" "$f" || failed=$((failed + 1))
  done
else
  for name in "${names[@]}"; do
    for f in "${files[@]}"; do
      [ -f "$f" ] || f="$proofs/$f"
      run_file "$name" "${paths[$name]}" "$f" || failed=$((failed + 1))
    done
  done
fi
if [ "$failed" -eq 0 ]; then
  echo "prove: every proof holds under ${names[*]}"
else
  echo "prove: $failed run(s) failed"
fi
exit "$((failed > 0))"
