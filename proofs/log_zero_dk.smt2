; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; log's k == 0 forms are its k != 0 forms with dk = +0 (PROOFS.md, L6).
;
; e_log.c (reference/fdlibm-5.3) returns f-B when k == 0 and
; dk*ln2_hi-((B-dk*ln2_lo)-f) when k != 0, for B = s*(f-R) (lines 136-137) and B = R
; (lines 118-119); for the band (lines 133-134) the k != 0 form adds dk*ln2_lo to
; s*(hfsq+R) inside B = hfsq-s*(hfsq+R); and for f == 0 it returns zero against
; dk*ln2_hi+dk*ln2_lo (lines 115-116). The port (Log) computes the k != 0 forms on every
; lane, with dk = +0 where k == 0. The claim: fl(+0 - fl(fl(B - (+0)) - f)) = fl(f - B)
; for every double B and every finite f that is not -0, and the band's and f == 0's forms
; likewise.
;
; What is proven:
;   - Section A: the sign of an exact zero (IEEE 754 6.3), as three functions of the
;     operands' sign bits, z.sub, z.add and z.mul, each pinned to the JVM over all four
;     pairs of signed zeros, and the product of +0 with ln2_hi and ln2_lo (rendered from
;     Java). Over them, every case where B - f is exactly zero: B = f nonzero, whose
;     difference is +0 under the same rule (pinned at sampled doubles), or B and f zeros
;     of any signs with f not -0: the fold's sign is the C's. With f = -0 allowed, the
;     solver finds B = +0, f = -0, where they differ (the hinge, H9). The band's step, hfsq
;     - (shr + (+0)) against hfsq - shr for a zero shr and the zero hfsq a zero f gives,
;     either sign (H10); and +0 + +0 = +0, the f == 0 form.
;   - Section B: the nonzero case. That fl(B - f) = -fl(f - B) is sign_symmetry.smt2's
;     section A, for every rational and every binade. Here: a nonzero double, either sign,
;     is admitted by jdouble.near for itself in any binade at once, and rounds to no other
;     double in its own binade or the next ones, every normal binade at once, the lowest
;     one's next below being the subnormals; so B - (+0) = B and +0 - v = -v for every
;     normal B and v.
;   - Section C: xn, whose high word is hx|(i^0x3ff00000) for hx < 2^20 and i either 0 or
;     0x100000 (lines 109-111), has sign 1 and binade 2^-1 or 2^0, for every low word: so
;     xn is in [0.5, 2), and xn = 1 exactly at one pattern. Its bits are built over
;     SMT-LIB's bit-vectors, Java's | and ^ on ints being bvor and bvxor (JLS 15.22.1),
;     then read as a double over java.smt2's integers. ln2_hi and ln2_lo are positive and
;     finite.
;
; What is not machine-checked:
;   - That f = xn - 1.0 is exact (Sterbenz's lemma, R6) and zero only at xn = 1, where it
;     is +0: the steps from section C to H9. ProofsTest.l6FIsNeverNegativeZero checks the
;     difference exactly, by BigDecimal, over sampled patterns of every kind.
;   - That B and v, where nonzero, are normal: on the k == 0 lanes |f| is in [2^-53,
;     0.415) or zero, B is about f^2/2 or smaller than f, and v is about log(1+f), all far
;     above 2^-1022; these bounds, like exp's H2, rest on the polynomial and are sampled.
;   - That the sign of a nonzero product of like signs is positive (hfsq not -0 for f
;     nonzero), and that a rounding of a nonzero value gives a nonzero double where the
;     values are normal: zero is not in the prelude.
;   - That a value rounds to no double in a binade further off than the next one: the
;     arithmetic of rationals, as in sign_symmetry.smt2.
;   - That D2I is not used and I2D of 0, or L7's conversion, gives +0.0 (JLS 5.1.2,
;     log_exact_dk.smt2), and that the port computes these forms: the code's text.
;
; Constants: ln2_hi and ln2_lo are rendered from Log; no claim here is tight to their
; values beyond their sign and finiteness, so none is refuted by changing it by one.
; LogTest.constantsAreTheSourcesBits pins them to the source's hex, and ProofFilesTest
; this file to its rendering.
;
; The logic is ALL: section B's products S * M * sn are nonlinear, and section C's
; bit-vectors sit beside java.smt2's integers.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

; Section A: the sign of an exact zero, true for -0. z.sub sx sy is the sign of x - y and
; z.add sx sy that of x + y for zeros x and y; z.mul is a zero product's.
(define-fun z.sub ((sx Bool) (sy Bool)) Bool (and sx (not sy)))
(define-fun z.add ((sx Bool) (sy Bool)) Bool (and sx sy))
(define-fun z.mul ((sx Bool) (sy Bool)) Bool (xor sx sy))

(echo "the zero signs are Java's, and +-0 * ln2_hi, ln2_lo, on 16 operand sets: expect unsat")
(push 1)
(assert (not (and
  (= (z.sub false false) false)
  (= (z.add false false) false)
  (= (z.mul false false) false)
  (= (z.sub false true) false)
  (= (z.add false true) false)
  (= (z.mul false true) true)
  (= (z.mul false false) false)
  (= (z.mul false false) false)
  (= (z.sub true false) true)
  (= (z.add true false) false)
  (= (z.mul true false) true)
  (= (z.sub true true) false)
  (= (z.add true true) true)
  (= (z.mul true true) false)
  (= (z.mul true false) true)
  (= (z.mul true false) true)
)))
(check-sat)
(pop 1)

; The fold's zero cases. B - f exactly zero: either B = f nonzero (eq), whose difference
; is +0 under the rule for like signs (Java's x - x above), or B and f zeros of signs sb
; and sf. B - (+0) keeps B's zero sign (z.sub sb false is sb). v = B - f; the fold is
; +0 - v; the C is f - B.
(define-fun v.sign ((eq Bool) (sb Bool) (sf Bool)) Bool
  (ite eq false (z.sub (z.sub sb false) sf)))
(define-fun fold.sign ((eq Bool) (sb Bool) (sf Bool)) Bool
  (z.sub false (v.sign eq sb sf)))
(define-fun c.sign ((eq Bool) (sb Bool) (sf Bool)) Bool (ite eq false (z.sub sf sb)))

(echo "B - (+0) keeps a zero B's sign, either sign: expect unsat")
(push 1)
(declare-const sb Bool)
(assert (not (= (z.sub sb false) sb)))
(check-sat)
(pop 1)

(echo "B - f exactly zero, f not -0: the fold's zero is the C's: expect unsat")
(push 1)
(declare-const eq Bool) (declare-const sb Bool) (declare-const sf Bool)
(assert (or eq (not sf)))
(assert (not (= (fold.sign eq sb sf) (c.sign eq sb sf))))
(check-sat)
(pop 1)

(echo "refuted: with f = -0 allowed the fold differs (B = +0, f = -0): expect sat")
(push 1)
(declare-const sb Bool) (declare-const sf Bool)
(assert (not (= (fold.sign false sb sf) (c.sign false sb sf))))
(check-sat)
(pop 1)

(echo "f = -0 and B = +0 is that case: the C gives -0, the fold +0: expect unsat")
(push 1)
(assert (not (and (c.sign false false true) (not (fold.sign false false true)))))
(check-sat)
(pop 1)

(echo "a zero f's hfsq = (0.5 * f) * f is +0, either sign of f (H10): expect unsat")
(push 1)
(declare-const sf Bool)
(assert (z.mul (z.mul false sf) sf))
(check-sat)
(pop 1)

(echo "band: hfsq - (shr + (+0)) is hfsq - shr, shr and hfsq zeros, hfsq +0: expect unsat")
(push 1)
(declare-const ss Bool)
(assert (not (= (z.sub false (z.add ss false)) (z.sub false ss))))
(check-sat)
(pop 1)

(echo "refuted: with hfsq = -0 the band's step differs (shr = -0): expect sat")
(push 1)
(declare-const ss Bool)
(assert (not (= (z.sub true (z.add ss false)) (z.sub true ss))))
(check-sat)
(pop 1)

(echo "f == 0: dk*ln2_hi + dk*ln2_lo with dk = +0 is +0, the C's zero: expect unsat")
(push 1)
(assert (z.add (z.mul false false) (z.mul false false)))
(check-sat)
(pop 1)

; Section B: a nonzero double less +0 is itself, and +0 less a nonzero double v is -v. The
; exact difference is the double S * M * 2^E or its negation, a / b with a = S * M * sn
; and b = sd.

(echo "a double, either sign, is a double jdouble.near admits for itself, any binade: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 sn) (< 0 sd) (jdouble.S.in S) (jdouble.M.in M)))
(assert (not (jdouble.near (* S M sn) sd S M sn sd)))
(check-sat)
(pop 1)

(echo "a double, either sign, rounds to no other double of its binade, every binade: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 sn) (< 0 sd) (or (= sn 1) (= sd 1)) (<= sd (* jdouble.SUB sn)) (jdouble.S.in S)
  (jdouble.M.in M)))
(assert (and (jdouble.near (* S M sn) sd S2 M2 sn sd) (not (and (= S2 S) (= M2 M)))))
(check-sat)
(pop 1)

(echo "nor to one of the binade above it, every binade: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 sn) (< 0 sd) (or (= sn 1) (= sd 1)) (<= sd (* jdouble.SUB sn)) (jdouble.S.in S)
  (jdouble.M.in M)))
(assert (jdouble.near (* S M sn) sd S2 M2 (* 2 sn) sd))
(check-sat)
(pop 1)

(echo "nor to one of the binade below it, every binade above the lowest: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 sn) (< 0 sd) (or (= sn 1) (= sd 1)) (<= (* 2 sd) (* jdouble.SUB sn)) (jdouble.S.in
  S) (jdouble.M.in M)))
(assert (jdouble.near (* S M sn) sd S2 M2 sn (* 2 sd)))
(check-sat)
(pop 1)

(echo "nor, in the lowest binade, to a subnormal: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sn 1) (= sd jdouble.SUB) (jdouble.S.in S) (jdouble.M.in M)))
(assert (jdouble.near.sub (* S M sn) sd S2 M2))
(check-sat)
(pop 1)

(echo "not vacuous: the lowest binade's doubles are admitted for themselves: expect sat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sn 1) (= sd jdouble.SUB) (jdouble.S.in S) (jdouble.M.in M)))
(assert (jdouble.near (* S M sn) sd S M sn sd))
(check-sat)
(pop 1)

(echo "refuted: a double less one step of its binade 2^-1 is itself: expect sat")
(push 1)
(declare-const M Int) (declare-const M2 Int)
(assert (jdouble.M.in M))
(assert (jdouble.near (- M 1) 9007199254740992 1 M2 1 9007199254740992))
(assert (not (= M2 M)))
(check-sat)
(pop 1)

; Section C: xn's bits, over bit-vectors, for every 20-bit hx, either i and every low
; word lo: the top 12 bits (sign and biased exponent) are 0x3fe or 0x3ff, and xn is 1.0
; only for hx = 0, lo = 0 and i = 0. Then, over java.smt2's integers, every long in
; [0x3fe00000_00000000, 0x40000000_00000000) is a double of sign 1 and binade 2^-1 or 2^0.
(define-fun xn.bits ((hx (_ BitVec 32)) (i (_ BitVec 32)) (lo (_ BitVec 32)))
  (_ BitVec 64) (concat (bvor hx (bvxor i #x3ff00000)) lo))

(echo "xn's sign and biased exponent are 0x3fe or 0x3ff: expect unsat")
(push 1)
(declare-const hx (_ BitVec 32)) (declare-const i (_ BitVec 32)) (declare-const lo (_ BitVec 32))
(assert (and (bvult hx #x00100000) (or (= i #x00000000) (= i #x00100000))))
(assert (not (or (= ((_ extract 63 52) (xn.bits hx i lo)) #x3fe) (= ((_ extract 63 52) (xn.bits hx i
  lo)) #x3ff))))
(check-sat)
(pop 1)

(echo "xn = 1 only at hx = 0, lo = 0, i = 0: expect unsat")
(push 1)
(declare-const hx (_ BitVec 32)) (declare-const i (_ BitVec 32)) (declare-const lo (_ BitVec 32))
(assert (and (bvult hx #x00100000) (or (= i #x00000000) (= i #x00100000))))
(assert (= (xn.bits hx i lo) #x3ff0000000000000))
(assert (not (and (= hx #x00000000) (= lo #x00000000) (= i #x00000000))))
(check-sat)
(pop 1)

(echo "not vacuous: some xn is 1: expect sat")
(push 1)
(declare-const hx (_ BitVec 32)) (declare-const i (_ BitVec 32)) (declare-const lo (_ BitVec 32))
(assert (and (bvult hx #x00100000) (or (= i #x00000000) (= i #x00100000))))
(assert (= (xn.bits hx i lo) #x3ff0000000000000))
(check-sat)
(pop 1)

(echo "those bits are doubles of sign 1 in binade 2^-1 or 2^0: expect unsat")
(push 1)
(declare-const b Int)
(assert (and (<= 4602678819172646912 b) (< b 4611686018427387904)))
(assert (not (and (= (jdouble.fromBits.S b) 1)
  (or (= (jdouble.fromBits.E b) (- 53)) (= (jdouble.fromBits.E b) (- 52))))))
(check-sat)
(pop 1)

(echo "ln2_hi and ln2_lo are positive and finite, as the zero product needs: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S 4604418534311723008) 1) (< (jdouble.fromBits.E
  4604418534311723008) 972)
  (= (jdouble.fromBits.S 4461442080421002358) 1) (< (jdouble.fromBits.E 4461442080421002358) 972))))
(check-sat)
(pop 1)
