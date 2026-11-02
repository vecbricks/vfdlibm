# readings

Reading notes on other vector math libraries: what they promise, how they test it, and how their
kernels are built, kept so a port can compare its own choices with theirs. They are notes, not
code to copy. vfdlibm ports fdlibm 5.3 only (`PLAN.md` section 6); a constant or a polynomial
from these libraries would break bit identity with `StrictMath` as well as their licences' terms.

Each note names the source's licence and the commit it was read at, and marks every claim
**[V]** (the author read the code) or **[I]** (inferred). Nothing in them was built or run.

- `SLEEF.md`: SLEEF, the vectorized libm behind the Vector API's math operators on Arm (SVML on
  x86). Boost Software License 1.0.
- `HIGHWAY.md`: Google Highway, a portable C++ SIMD library with a `contrib/math` set. Apache-2.0
  or BSD-3-Clause, at the user's choice.

Both were first written for Varka (its `sql/varka/plans/m8/READING_SLEEF.md` and
`READING_HIGHWAY.md`, 15 October 2026) and cut here to the parts about vector math: error bounds
and how they are tested, special cases, bound checks and the structure of the kernels.

Never add notes on GPL or LGPL code here (OpenJDK's `FdLibm.java`, HotSpot's math stubs,
glibc): vfdlibm must not be derived from them, and a note on them is a step towards that.
