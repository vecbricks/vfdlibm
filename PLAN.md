# vfdlibm: the plan

## 1. Goal

Vector API versions of fdlibm's double functions, each lane bit-identical to
`java.lang.StrictMath`, released as a small library under `io.github.vecbricks`.

## 2. How it grows: one function at a time, with Varka

The library is filled step by step. When Varka needs an exact vector form of a function, that
function is ported here and in Varka's kernel together, and the two are cross-checked:

1. **Port it here.** Port the function from fdlibm 5.3's C file (`e_exp.c`, `s_expm1.c`, ...) into
   a static method over `DoubleVector`, with that file's Sun notice in the source header.
2. **Test it here** against `StrictMath`, lane by lane and bit for bit (section 4).
3. **Build Varka's side.** Varka's kernel emits the same sequence of operations as its own IR
   nodes. A large function body is not inlined by C2, so a call into the library from a kernel
   would box the vectors, and Varka uses this library as its reference rather than calling it.
4. **Cross-check the two.** Varka's tests run the library's method and the emitted kernel on the
   same inputs and require identical bits. Both are also checked against `StrictMath`.

Each function arrives in its own pull request here, paired with the Varka pull request that emits
it.

## 3. The functions, and the order they are expected in

The set is the functions Spark computes with fdlibm on every host:

- `StrictMath` calls: `exp`, `expm1`, `log`, `log10`, `log1p`, `pow`;
- `Math` methods that HotSpot does not replace with a stub on any host: `asin`, `acos`, `atan`,
  `atan2`, `sinh`, `cosh`, `hypot`.

That is thirteen functions, about 1,000 lines of fdlibm code in all; `pow` is the largest. Varka's
row order decides which comes first; `exp` is the likely first port, since `pow`, `sinh` and
`cosh` build on it.

`tan`, `tanh` and `cbrt` are fdlibm on aarch64 but Intel LIBM stubs on x86, so a port of them
matches the JVM only on Arm. `sin` and `cos` are HotSpot's own stubs on both architectures, so
`Math.sin` and `Math.cos` are not fdlibm anywhere; a port of them matches `StrictMath` only. These
five are a later, optional set; the large-argument reduction of the trigonometric ones
(`__ieee754_rem_pio2`, `__kernel_rem_pio2`) stays a per-lane `StrictMath` call.

## 4. Testing

- **The oracle** is `StrictMath` on the same JVM, compared by `doubleToRawLongBits`.
- **Inputs:**
  - sampled bit patterns over the whole `double` domain;
  - dense sweeps around each threshold fdlibm branches on;
  - fdlibm's special cases (NaN, the infinities, `+-0.0`, subnormals, overflow and underflow
    edges);
  - published hard cases.
- **Every check proves it can fail:** a test-only flag plants a known fault (a dropped correction
  term, a constant off by one ulp, a branch threshold moved), and the suite must catch it.
- **Every vector width:** each test runs at the preferred species and at 128, 256 and 512 bits
  where the host supports them.

## 5. API shape

- **One final class** `VFdlibm`, with static methods such as `DoubleVector exp(DoubleVector x)`.
- **Masks:** each method takes the lanes as they are. A masked variant leaves inactive lanes
  untouched and never lets them select a slow path.
- **Branches:** fdlibm's branches on the argument's range become lane masks. The common paths are
  computed for every lane and blended. A rare path is finished per lane by `StrictMath`, so the
  vector form never trades exactness for speed. Whether a given branch is blended or finished per
  lane is decided by measurement and recorded in the function's pull request.

## 6. Rules

- Port from fdlibm 5.3's C sources only (netlib). Never copy from or translate OpenJDK's
  `FdLibm.java`, HotSpot's Intel LIBM stubs or glibc, which are GPL or LGPL.
- No fused multiply-add, no reassociation, no `Math.*` call inside a vector body except the per-lane
  `StrictMath` fallback.
- Java 25, the Vector API module, no preview features.
- Lines at most 100 columns; ASCII only, except for string literals that need it.
- Commits and pull requests end with `Generated-by: <tool> (<model>)` when AI tooling was used.

## 7. Open

- Benchmarks (JMH under `src/jmh`) arrive with the first function, measured against the scalar
  `StrictMath` loop at each vector width.
- The first release is cut once Varka's first exact kernel lands and needs a pinned version.
