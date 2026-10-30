# Working in vfdlibm, for agents and people alike

vfdlibm ports fdlibm's double functions to the Java Vector API, each lane bit-identical to
`java.lang.StrictMath`. `PLAN.md` is the plan and the rules; read it before changing anything.

## The rules that matter most

- **Licence.** Port from Sun's fdlibm 5.3 C sources only, and keep each file's Sun notice. Never
  read OpenJDK's `FdLibm.java`, HotSpot's math stubs or glibc in order to write code here; they are
  GPL or LGPL. `StrictMath` is the oracle, called from tests, never a source to copy.
- **Bit identity.** A function is done when every test compares its lanes with `StrictMath` by
  `Double.doubleToRawLongBits` and passes, at every vector width the host has.
- **fdlibm's order of operations.** No fused multiply-add, no reassociation, no algebraic
  shortcuts: the rounding of every step must be fdlibm's.

## Build and test

- `mvn -B verify` builds and runs the tests; the Vector API module is added by `pom.xml`.
- One test: `mvn -q test -Dtest=ExpTest`.

## Changes

Changes go through pull requests on branches, never straight to `main`. Each function comes in its
own PR, paired with the Varka PR that emits it. Commit messages and PR descriptions end with
`Generated-by: <tool> (<model and version>)` when AI tooling was used (Apache's convention); no
`Co-Authored-By`.
