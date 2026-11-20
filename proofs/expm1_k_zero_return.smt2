; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; expm1's k == 0 return through the k != 0 inner term, with c = +0 (PROOFS.md, L11).
;
; s_expm1.c (reference/fdlibm-5.3) returns x - (x*e-hxs) when k == 0 (line 189) and
; starts from e = (x*(e-c)-c); e -= hxs when k != 0 (lines 191-192). The port (Expm1)
; computes E = fl(fl(fl(r*fl(e - c)) - c) - hxs) on every lane, where on the k == 0 lanes
; r = x and c = +0 (L10, expm1_reduction.smt2's section B). The claim: with c = +0,
; fl(e - c) = e and fl(v - c) = v for v = fl(r*e), so E = fl(fl(r*e) - hxs), line 189's
; inner term from the same operands.
;
; What is proven:
;   - Section A: the sign of an exact zero (IEEE 754 6.3), z.sub, z.add and z.mul,
;     pinned to the JVM over every pair of signed zeros. A zero e less +0 keeps its sign,
;     either sign; so does a zero v; and the product r*fl(e - (+0)) has the sign r*e has.
;     With c = -0 the step fails at e = -0, where e - c is +0: the (R3) step as stated,
;     for every e, needs c = +0. No lane has a zero e (below), so on the lanes this is
;     not the lemma's hinge.
;   - Section B: a nonzero normal double less +0 is itself: jdouble.near admits it for
;     itself in any binade, and no other double of its binade or the next ones, every
;     normal binade at once. That is e - (+0) = e and v - (+0) = v for normal e and v.
;     The hinge, H15, is that c is a zero: a double less one step of its binade is
;     another double (checked in binade 2^-1), so with a c of one step of e's binade
;     the first step fails.
;
; What is not machine-checked:
;   - That e and v = fl(r*e) are nonzero and normal on the lanes: for
;     2^-54 <= |x| < 0.35, hxs is at least 2^-109 and e and r*e are of the order of x^2
;     and x^3 times constants near one, far above 2^-1022. Those bounds rest on the
;     polynomial and are sampled (ProofsTest.l11KZeroReturnIsTheInnerTermsForm).
;   - That c = +0 on those lanes: L10, whose step 3 is expm1_reduction.smt2's section B.
;   - That a value rounds to no double in a binade further off than the next one: the
;     arithmetic of rationals, as in sign_symmetry.smt2.
;   - That the port computes these forms, and line 189's last operation from them: the
;     code's text.
;
; Constants: none of Expm1's is used. The claim is about c alone; a c of one step of
; e's binade refutes it, and c = -0 its step at a zero e.
;
; The logic is ALL: section B's products S * M * sn are nonlinear.
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

(echo "the zero signs are Java's, on 12 operand sets: expect unsat")
(push 1)
(assert (not (and
  (= (z.sub false false) false)
  (= (z.add false false) false)
  (= (z.mul false false) false)
  (= (z.sub false true) false)
  (= (z.add false true) false)
  (= (z.mul false true) true)
  (= (z.sub true false) true)
  (= (z.add true false) false)
  (= (z.mul true false) true)
  (= (z.sub true true) false)
  (= (z.add true true) true)
  (= (z.mul true true) false)
)))
(check-sat)
(pop 1)

(echo "a zero e less c = +0 is e, either sign; so a zero v less c is v: expect unsat")
(push 1)
(declare-const se Bool)
(assert (not (= (z.sub se false) se)))
(check-sat)
(pop 1)

(echo "a zero product r*(e - c) has the sign of r*e, c = +0, either signs: expect unsat")
(push 1)
(declare-const sr Bool) (declare-const se Bool)
(assert (not (= (z.mul sr (z.sub se false)) (z.mul sr se))))
(check-sat)
(pop 1)

(echo "refuted: with c = -0, e - c is not e for some zero e: expect sat")
(push 1)
(declare-const se Bool)
(assert (not (= (z.sub se true) se)))
(check-sat)
(pop 1)

(echo "that zero is e = -0: -0 - (-0) is +0, and with c = +0 it stays -0: expect unsat")
(push 1)
(assert (not (and (not (z.sub true true)) (z.sub true false))))
(check-sat)
(pop 1)

; Section B: a nonzero normal double less +0 is itself. The exact difference is the double
; S * M * 2^E, a / b with a = S * M * sn and b = sd.

(echo "a double, either sign, is a double jdouble.near admits for itself, any binade: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 sn) (< 0 sd) (jdouble.S.in S) (jdouble.M.in M)))
(assert (not (jdouble.near (* S M sn) sd S M sn sd)))
(check-sat)
(pop 1)

(echo "a double, either sign, rounds to no other of its binade, binades up to 2^52: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sn 1) (< 0 sd) (<= sd jdouble.SUB) (jdouble.S.in S) (jdouble.M.in M)))
(assert (and (jdouble.near (* S M sn) sd S2 M2 sn sd) (not (and (= S2 S) (= M2 M)))))
(check-sat)
(pop 1)

(echo "nor in its binade from 2^52 up: with the one before, every binade: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sd 1) (< 0 sn) (jdouble.S.in S) (jdouble.M.in M)))
(assert (and (jdouble.near (* S M sn) sd S2 M2 sn sd) (not (and (= S2 S) (= M2 M)))))
(check-sat)
(pop 1)

(echo "nor to one of the binade above it, binades up to 2^52: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sn 1) (< 0 sd) (<= sd jdouble.SUB) (jdouble.S.in S) (jdouble.M.in M)))
(assert (jdouble.near (* S M sn) sd S2 M2 (* 2 sn) sd))
(check-sat)
(pop 1)

(echo "nor to one of the binade above it, binades from 2^52 up: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sd 1) (< 0 sn) (jdouble.S.in S) (jdouble.M.in M)))
(assert (jdouble.near (* S M sn) sd S2 M2 (* 2 sn) sd))
(check-sat)
(pop 1)

(echo "nor to one of the binade below it, binades above the lowest up to 2^52: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sn 1) (< 0 sd) (<= (* 2 sd) jdouble.SUB) (jdouble.S.in S) (jdouble.M.in M)))
(assert (jdouble.near (* S M sn) sd S2 M2 sn (* 2 sd)))
(check-sat)
(pop 1)

(echo "nor to one of the binade below it, binades from 2^52 up: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const S2 Int) (declare-const M2 Int)
  (declare-const sn Int) (declare-const sd Int)
(assert (and (= sd 1) (< 0 sn) (jdouble.S.in S) (jdouble.M.in M)))
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
