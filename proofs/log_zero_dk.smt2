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
;     double in its own binade or the next ones, for every binade from 2^-110 to 2^-1, so
;     B - (+0) = B and +0 - v = -v for every such B and v.
;   - Section C: xn, whose high word is hx|(i^0x3ff00000) for hx < 2^20 and i either 0 or
;     0x100000 (lines 109-111), has sign 1 and binade 2^-1 or 2^0, for every low word: so
;     xn is in [0.5, 2), and xn = 1 exactly at one pattern. ln2_hi and ln2_lo are positive
;     and finite.
;
; What is not machine-checked:
;   - That f = xn - 1.0 is exact (Sterbenz's lemma, R6) and zero only at xn = 1, where it
;     is +0: the steps from section C to H9. ProofsTest.l6FIsNeverNegativeZero checks the
;     difference exactly, by BigDecimal, over sampled patterns of every kind.
;   - That the values of B and v lie in the binades of section B: on the k == 0 lanes |f|
;     is in [2^-53, 0.415) or zero, B is about f^2/2 or smaller than f, and v is about
;     log(1+f); these bounds, like exp's H2, rest on the polynomial and are sampled.
;   - That the sign of a nonzero product of like signs is positive (hfsq not -0 for f
;     nonzero), and that a rounding of a nonzero value gives a nonzero double where the
;     values are normal: zero is not in the prelude.
;   - That a value rounds to no double in a binade further off than the next one: the
;     arithmetic of rationals, as in sign_symmetry.smt2.
;   - That D2I is not used and I2D of 0, or L8's conversion, gives +0.0 (JLS 5.1.2,
;     log_exact_dk.smt2), and that the port computes these forms: the code's text.
;
; Constants: ln2_hi and ln2_lo are rendered from Log; no claim here is tight to their
; values beyond their sign and finiteness, so none is refuted by changing it by one.
; LogTest.constantsAreTheSourcesBits pins them to the source's hex, and ProofFilesTest
; this file to its rendering.
;
; The logic is ALL, as for exp_zero_reduction.smt2's per-binade checks.
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

(echo "a double of binade 2^-110, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M))
  5846006549323611672814739330865132078623730171904 S2 M2 1
  5846006549323611672814739330865132078623730171904) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5846006549323611672814739330865132078623730171904 S2 M2 1
    11692013098647223345629478661730264157247460343808)
  (jdouble.near (ite (= S 1) M (- M)) 5846006549323611672814739330865132078623730171904 S2 M2 1
    2923003274661805836407369665432566039311865085952)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-109, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M))
  2923003274661805836407369665432566039311865085952 S2 M2 1
  2923003274661805836407369665432566039311865085952) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2923003274661805836407369665432566039311865085952 S2 M2 1
    5846006549323611672814739330865132078623730171904)
  (jdouble.near (ite (= S 1) M (- M)) 2923003274661805836407369665432566039311865085952 S2 M2 1
    1461501637330902918203684832716283019655932542976)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-108, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M))
  1461501637330902918203684832716283019655932542976 S2 M2 1
  1461501637330902918203684832716283019655932542976) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1461501637330902918203684832716283019655932542976 S2 M2 1
    2923003274661805836407369665432566039311865085952)
  (jdouble.near (ite (= S 1) M (- M)) 1461501637330902918203684832716283019655932542976 S2 M2 1
    730750818665451459101842416358141509827966271488)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-107, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M))
  730750818665451459101842416358141509827966271488 S2 M2 1
  730750818665451459101842416358141509827966271488) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 730750818665451459101842416358141509827966271488 S2 M2 1
    1461501637330902918203684832716283019655932542976)
  (jdouble.near (ite (= S 1) M (- M)) 730750818665451459101842416358141509827966271488 S2 M2 1
    365375409332725729550921208179070754913983135744)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-106, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M))
  365375409332725729550921208179070754913983135744 S2 M2 1
  365375409332725729550921208179070754913983135744) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 365375409332725729550921208179070754913983135744 S2 M2 1
    730750818665451459101842416358141509827966271488)
  (jdouble.near (ite (= S 1) M (- M)) 365375409332725729550921208179070754913983135744 S2 M2 1
    182687704666362864775460604089535377456991567872)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-105, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M))
  182687704666362864775460604089535377456991567872 S2 M2 1
  182687704666362864775460604089535377456991567872) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 182687704666362864775460604089535377456991567872 S2 M2 1
    365375409332725729550921208179070754913983135744)
  (jdouble.near (ite (= S 1) M (- M)) 182687704666362864775460604089535377456991567872 S2 M2 1
    91343852333181432387730302044767688728495783936)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-104, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 91343852333181432387730302044767688728495783936
  S2 M2 1 91343852333181432387730302044767688728495783936) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 91343852333181432387730302044767688728495783936 S2 M2 1
    182687704666362864775460604089535377456991567872)
  (jdouble.near (ite (= S 1) M (- M)) 91343852333181432387730302044767688728495783936 S2 M2 1
    45671926166590716193865151022383844364247891968)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-103, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 45671926166590716193865151022383844364247891968
  S2 M2 1 45671926166590716193865151022383844364247891968) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 45671926166590716193865151022383844364247891968 S2 M2 1
    91343852333181432387730302044767688728495783936)
  (jdouble.near (ite (= S 1) M (- M)) 45671926166590716193865151022383844364247891968 S2 M2 1
    22835963083295358096932575511191922182123945984)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-102, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 22835963083295358096932575511191922182123945984
  S2 M2 1 22835963083295358096932575511191922182123945984) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 22835963083295358096932575511191922182123945984 S2 M2 1
    45671926166590716193865151022383844364247891968)
  (jdouble.near (ite (= S 1) M (- M)) 22835963083295358096932575511191922182123945984 S2 M2 1
    11417981541647679048466287755595961091061972992)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-101, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 11417981541647679048466287755595961091061972992
  S2 M2 1 11417981541647679048466287755595961091061972992) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 11417981541647679048466287755595961091061972992 S2 M2 1
    22835963083295358096932575511191922182123945984)
  (jdouble.near (ite (= S 1) M (- M)) 11417981541647679048466287755595961091061972992 S2 M2 1
    5708990770823839524233143877797980545530986496)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-100, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5708990770823839524233143877797980545530986496
  S2 M2 1 5708990770823839524233143877797980545530986496) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5708990770823839524233143877797980545530986496 S2 M2 1
    11417981541647679048466287755595961091061972992)
  (jdouble.near (ite (= S 1) M (- M)) 5708990770823839524233143877797980545530986496 S2 M2 1
    2854495385411919762116571938898990272765493248)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-99, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2854495385411919762116571938898990272765493248
  S2 M2 1 2854495385411919762116571938898990272765493248) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2854495385411919762116571938898990272765493248 S2 M2 1
    5708990770823839524233143877797980545530986496)
  (jdouble.near (ite (= S 1) M (- M)) 2854495385411919762116571938898990272765493248 S2 M2 1
    1427247692705959881058285969449495136382746624)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-98, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1427247692705959881058285969449495136382746624
  S2 M2 1 1427247692705959881058285969449495136382746624) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1427247692705959881058285969449495136382746624 S2 M2 1
    2854495385411919762116571938898990272765493248)
  (jdouble.near (ite (= S 1) M (- M)) 1427247692705959881058285969449495136382746624 S2 M2 1
    713623846352979940529142984724747568191373312)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-97, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 713623846352979940529142984724747568191373312
  S2 M2 1 713623846352979940529142984724747568191373312) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 713623846352979940529142984724747568191373312 S2 M2 1
    1427247692705959881058285969449495136382746624)
  (jdouble.near (ite (= S 1) M (- M)) 713623846352979940529142984724747568191373312 S2 M2 1
    356811923176489970264571492362373784095686656)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-96, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 356811923176489970264571492362373784095686656
  S2 M2 1 356811923176489970264571492362373784095686656) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 356811923176489970264571492362373784095686656 S2 M2 1
    713623846352979940529142984724747568191373312)
  (jdouble.near (ite (= S 1) M (- M)) 356811923176489970264571492362373784095686656 S2 M2 1
    178405961588244985132285746181186892047843328)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-95, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 178405961588244985132285746181186892047843328
  S2 M2 1 178405961588244985132285746181186892047843328) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 178405961588244985132285746181186892047843328 S2 M2 1
    356811923176489970264571492362373784095686656)
  (jdouble.near (ite (= S 1) M (- M)) 178405961588244985132285746181186892047843328 S2 M2 1
    89202980794122492566142873090593446023921664)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-94, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 89202980794122492566142873090593446023921664 S2
  M2 1 89202980794122492566142873090593446023921664) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 89202980794122492566142873090593446023921664 S2 M2 1
    178405961588244985132285746181186892047843328)
  (jdouble.near (ite (= S 1) M (- M)) 89202980794122492566142873090593446023921664 S2 M2 1
    44601490397061246283071436545296723011960832)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-93, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 44601490397061246283071436545296723011960832 S2
  M2 1 44601490397061246283071436545296723011960832) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 44601490397061246283071436545296723011960832 S2 M2 1
    89202980794122492566142873090593446023921664)
  (jdouble.near (ite (= S 1) M (- M)) 44601490397061246283071436545296723011960832 S2 M2 1
    22300745198530623141535718272648361505980416)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-92, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 22300745198530623141535718272648361505980416 S2
  M2 1 22300745198530623141535718272648361505980416) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 22300745198530623141535718272648361505980416 S2 M2 1
    44601490397061246283071436545296723011960832)
  (jdouble.near (ite (= S 1) M (- M)) 22300745198530623141535718272648361505980416 S2 M2 1
    11150372599265311570767859136324180752990208)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-91, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 11150372599265311570767859136324180752990208 S2
  M2 1 11150372599265311570767859136324180752990208) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 11150372599265311570767859136324180752990208 S2 M2 1
    22300745198530623141535718272648361505980416)
  (jdouble.near (ite (= S 1) M (- M)) 11150372599265311570767859136324180752990208 S2 M2 1
    5575186299632655785383929568162090376495104)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-90, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5575186299632655785383929568162090376495104 S2
  M2 1 5575186299632655785383929568162090376495104) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5575186299632655785383929568162090376495104 S2 M2 1
    11150372599265311570767859136324180752990208)
  (jdouble.near (ite (= S 1) M (- M)) 5575186299632655785383929568162090376495104 S2 M2 1
    2787593149816327892691964784081045188247552)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-89, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2787593149816327892691964784081045188247552 S2
  M2 1 2787593149816327892691964784081045188247552) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2787593149816327892691964784081045188247552 S2 M2 1
    5575186299632655785383929568162090376495104)
  (jdouble.near (ite (= S 1) M (- M)) 2787593149816327892691964784081045188247552 S2 M2 1
    1393796574908163946345982392040522594123776)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-88, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1393796574908163946345982392040522594123776 S2
  M2 1 1393796574908163946345982392040522594123776) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1393796574908163946345982392040522594123776 S2 M2 1
    2787593149816327892691964784081045188247552)
  (jdouble.near (ite (= S 1) M (- M)) 1393796574908163946345982392040522594123776 S2 M2 1
    696898287454081973172991196020261297061888)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-87, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 696898287454081973172991196020261297061888 S2
  M2 1 696898287454081973172991196020261297061888) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 696898287454081973172991196020261297061888 S2 M2 1
    1393796574908163946345982392040522594123776)
  (jdouble.near (ite (= S 1) M (- M)) 696898287454081973172991196020261297061888 S2 M2 1
    348449143727040986586495598010130648530944)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-86, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 348449143727040986586495598010130648530944 S2
  M2 1 348449143727040986586495598010130648530944) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 348449143727040986586495598010130648530944 S2 M2 1
    696898287454081973172991196020261297061888)
  (jdouble.near (ite (= S 1) M (- M)) 348449143727040986586495598010130648530944 S2 M2 1
    174224571863520493293247799005065324265472)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-85, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 174224571863520493293247799005065324265472 S2
  M2 1 174224571863520493293247799005065324265472) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 174224571863520493293247799005065324265472 S2 M2 1
    348449143727040986586495598010130648530944)
  (jdouble.near (ite (= S 1) M (- M)) 174224571863520493293247799005065324265472 S2 M2 1
    87112285931760246646623899502532662132736)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-84, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 87112285931760246646623899502532662132736 S2 M2
  1 87112285931760246646623899502532662132736) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 87112285931760246646623899502532662132736 S2 M2 1
    174224571863520493293247799005065324265472)
  (jdouble.near (ite (= S 1) M (- M)) 87112285931760246646623899502532662132736 S2 M2 1
    43556142965880123323311949751266331066368)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-83, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 43556142965880123323311949751266331066368 S2 M2
  1 43556142965880123323311949751266331066368) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 43556142965880123323311949751266331066368 S2 M2 1
    87112285931760246646623899502532662132736)
  (jdouble.near (ite (= S 1) M (- M)) 43556142965880123323311949751266331066368 S2 M2 1
    21778071482940061661655974875633165533184)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-82, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 21778071482940061661655974875633165533184 S2 M2
  1 21778071482940061661655974875633165533184) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 21778071482940061661655974875633165533184 S2 M2 1
    43556142965880123323311949751266331066368)
  (jdouble.near (ite (= S 1) M (- M)) 21778071482940061661655974875633165533184 S2 M2 1
    10889035741470030830827987437816582766592)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-81, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 10889035741470030830827987437816582766592 S2 M2
  1 10889035741470030830827987437816582766592) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 10889035741470030830827987437816582766592 S2 M2 1
    21778071482940061661655974875633165533184)
  (jdouble.near (ite (= S 1) M (- M)) 10889035741470030830827987437816582766592 S2 M2 1
    5444517870735015415413993718908291383296)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-80, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5444517870735015415413993718908291383296 S2 M2
  1 5444517870735015415413993718908291383296) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5444517870735015415413993718908291383296 S2 M2 1
    10889035741470030830827987437816582766592)
  (jdouble.near (ite (= S 1) M (- M)) 5444517870735015415413993718908291383296 S2 M2 1
    2722258935367507707706996859454145691648)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-79, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2722258935367507707706996859454145691648 S2 M2
  1 2722258935367507707706996859454145691648) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2722258935367507707706996859454145691648 S2 M2 1
    5444517870735015415413993718908291383296)
  (jdouble.near (ite (= S 1) M (- M)) 2722258935367507707706996859454145691648 S2 M2 1
    1361129467683753853853498429727072845824)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-78, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1361129467683753853853498429727072845824 S2 M2
  1 1361129467683753853853498429727072845824) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1361129467683753853853498429727072845824 S2 M2 1
    2722258935367507707706996859454145691648)
  (jdouble.near (ite (= S 1) M (- M)) 1361129467683753853853498429727072845824 S2 M2 1
    680564733841876926926749214863536422912)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-77, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 680564733841876926926749214863536422912 S2 M2 1
  680564733841876926926749214863536422912) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 680564733841876926926749214863536422912 S2 M2 1
    1361129467683753853853498429727072845824)
  (jdouble.near (ite (= S 1) M (- M)) 680564733841876926926749214863536422912 S2 M2 1
    340282366920938463463374607431768211456)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-76, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 340282366920938463463374607431768211456 S2 M2 1
  340282366920938463463374607431768211456) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 340282366920938463463374607431768211456 S2 M2 1
    680564733841876926926749214863536422912)
  (jdouble.near (ite (= S 1) M (- M)) 340282366920938463463374607431768211456 S2 M2 1
    170141183460469231731687303715884105728)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-75, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 170141183460469231731687303715884105728 S2 M2 1
  170141183460469231731687303715884105728) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 170141183460469231731687303715884105728 S2 M2 1
    340282366920938463463374607431768211456)
  (jdouble.near (ite (= S 1) M (- M)) 170141183460469231731687303715884105728 S2 M2 1
    85070591730234615865843651857942052864)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-74, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 85070591730234615865843651857942052864 S2 M2 1
  85070591730234615865843651857942052864) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 85070591730234615865843651857942052864 S2 M2 1
    170141183460469231731687303715884105728)
  (jdouble.near (ite (= S 1) M (- M)) 85070591730234615865843651857942052864 S2 M2 1
    42535295865117307932921825928971026432)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-73, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 42535295865117307932921825928971026432 S2 M2 1
  42535295865117307932921825928971026432) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 42535295865117307932921825928971026432 S2 M2 1
    85070591730234615865843651857942052864)
  (jdouble.near (ite (= S 1) M (- M)) 42535295865117307932921825928971026432 S2 M2 1
    21267647932558653966460912964485513216)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-72, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 21267647932558653966460912964485513216 S2 M2 1
  21267647932558653966460912964485513216) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 21267647932558653966460912964485513216 S2 M2 1
    42535295865117307932921825928971026432)
  (jdouble.near (ite (= S 1) M (- M)) 21267647932558653966460912964485513216 S2 M2 1
    10633823966279326983230456482242756608)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-71, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 10633823966279326983230456482242756608 S2 M2 1
  10633823966279326983230456482242756608) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 10633823966279326983230456482242756608 S2 M2 1
    21267647932558653966460912964485513216)
  (jdouble.near (ite (= S 1) M (- M)) 10633823966279326983230456482242756608 S2 M2 1
    5316911983139663491615228241121378304)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-70, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5316911983139663491615228241121378304 S2 M2 1
  5316911983139663491615228241121378304) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5316911983139663491615228241121378304 S2 M2 1
    10633823966279326983230456482242756608)
  (jdouble.near (ite (= S 1) M (- M)) 5316911983139663491615228241121378304 S2 M2 1
    2658455991569831745807614120560689152)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-69, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2658455991569831745807614120560689152 S2 M2 1
  2658455991569831745807614120560689152) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2658455991569831745807614120560689152 S2 M2 1
    5316911983139663491615228241121378304)
  (jdouble.near (ite (= S 1) M (- M)) 2658455991569831745807614120560689152 S2 M2 1
    1329227995784915872903807060280344576)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-68, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1329227995784915872903807060280344576 S2 M2 1
  1329227995784915872903807060280344576) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1329227995784915872903807060280344576 S2 M2 1
    2658455991569831745807614120560689152)
  (jdouble.near (ite (= S 1) M (- M)) 1329227995784915872903807060280344576 S2 M2 1
    664613997892457936451903530140172288)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-67, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 664613997892457936451903530140172288 S2 M2 1
  664613997892457936451903530140172288) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 664613997892457936451903530140172288 S2 M2 1
    1329227995784915872903807060280344576)
  (jdouble.near (ite (= S 1) M (- M)) 664613997892457936451903530140172288 S2 M2 1
    332306998946228968225951765070086144)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-66, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 332306998946228968225951765070086144 S2 M2 1
  332306998946228968225951765070086144) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 332306998946228968225951765070086144 S2 M2 1
    664613997892457936451903530140172288)
  (jdouble.near (ite (= S 1) M (- M)) 332306998946228968225951765070086144 S2 M2 1
    166153499473114484112975882535043072)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-65, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 166153499473114484112975882535043072 S2 M2 1
  166153499473114484112975882535043072) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 166153499473114484112975882535043072 S2 M2 1
    332306998946228968225951765070086144)
  (jdouble.near (ite (= S 1) M (- M)) 166153499473114484112975882535043072 S2 M2 1
    83076749736557242056487941267521536)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-64, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 83076749736557242056487941267521536 S2 M2 1
  83076749736557242056487941267521536) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 83076749736557242056487941267521536 S2 M2 1
    166153499473114484112975882535043072)
  (jdouble.near (ite (= S 1) M (- M)) 83076749736557242056487941267521536 S2 M2 1
    41538374868278621028243970633760768)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-63, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 41538374868278621028243970633760768 S2 M2 1
  41538374868278621028243970633760768) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 41538374868278621028243970633760768 S2 M2 1
    83076749736557242056487941267521536)
  (jdouble.near (ite (= S 1) M (- M)) 41538374868278621028243970633760768 S2 M2 1
    20769187434139310514121985316880384)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-62, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 20769187434139310514121985316880384 S2 M2 1
  20769187434139310514121985316880384) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 20769187434139310514121985316880384 S2 M2 1
    41538374868278621028243970633760768)
  (jdouble.near (ite (= S 1) M (- M)) 20769187434139310514121985316880384 S2 M2 1
    10384593717069655257060992658440192)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-61, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 10384593717069655257060992658440192 S2 M2 1
  10384593717069655257060992658440192) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 10384593717069655257060992658440192 S2 M2 1
    20769187434139310514121985316880384)
  (jdouble.near (ite (= S 1) M (- M)) 10384593717069655257060992658440192 S2 M2 1
    5192296858534827628530496329220096)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-60, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5192296858534827628530496329220096 S2 M2 1
  5192296858534827628530496329220096) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5192296858534827628530496329220096 S2 M2 1
    10384593717069655257060992658440192)
  (jdouble.near (ite (= S 1) M (- M)) 5192296858534827628530496329220096 S2 M2 1
    2596148429267413814265248164610048)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-59, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2596148429267413814265248164610048 S2 M2 1
  2596148429267413814265248164610048) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2596148429267413814265248164610048 S2 M2 1
    5192296858534827628530496329220096)
  (jdouble.near (ite (= S 1) M (- M)) 2596148429267413814265248164610048 S2 M2 1
    1298074214633706907132624082305024)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-58, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1298074214633706907132624082305024 S2 M2 1
  1298074214633706907132624082305024) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1298074214633706907132624082305024 S2 M2 1
    2596148429267413814265248164610048)
  (jdouble.near (ite (= S 1) M (- M)) 1298074214633706907132624082305024 S2 M2 1
    649037107316853453566312041152512)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-57, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 649037107316853453566312041152512 S2 M2 1
  649037107316853453566312041152512) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 649037107316853453566312041152512 S2 M2 1
    1298074214633706907132624082305024)
  (jdouble.near (ite (= S 1) M (- M)) 649037107316853453566312041152512 S2 M2 1
    324518553658426726783156020576256)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-56, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 324518553658426726783156020576256 S2 M2 1
  324518553658426726783156020576256) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 324518553658426726783156020576256 S2 M2 1
    649037107316853453566312041152512)
  (jdouble.near (ite (= S 1) M (- M)) 324518553658426726783156020576256 S2 M2 1
    162259276829213363391578010288128)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-55, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 162259276829213363391578010288128 S2 M2 1
  162259276829213363391578010288128) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 162259276829213363391578010288128 S2 M2 1
    324518553658426726783156020576256)
  (jdouble.near (ite (= S 1) M (- M)) 162259276829213363391578010288128 S2 M2 1
    81129638414606681695789005144064)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-54, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 81129638414606681695789005144064 S2 M2 1
  81129638414606681695789005144064) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 81129638414606681695789005144064 S2 M2 1
    162259276829213363391578010288128)
  (jdouble.near (ite (= S 1) M (- M)) 81129638414606681695789005144064 S2 M2 1
    40564819207303340847894502572032)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-53, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 40564819207303340847894502572032 S2 M2 1
  40564819207303340847894502572032) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 40564819207303340847894502572032 S2 M2 1
    81129638414606681695789005144064)
  (jdouble.near (ite (= S 1) M (- M)) 40564819207303340847894502572032 S2 M2 1
    20282409603651670423947251286016)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-52, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 20282409603651670423947251286016 S2 M2 1
  20282409603651670423947251286016) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 20282409603651670423947251286016 S2 M2 1
    40564819207303340847894502572032)
  (jdouble.near (ite (= S 1) M (- M)) 20282409603651670423947251286016 S2 M2 1
    10141204801825835211973625643008)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-51, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 10141204801825835211973625643008 S2 M2 1
  10141204801825835211973625643008) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 10141204801825835211973625643008 S2 M2 1
    20282409603651670423947251286016)
  (jdouble.near (ite (= S 1) M (- M)) 10141204801825835211973625643008 S2 M2 1
    5070602400912917605986812821504)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-50, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 5070602400912917605986812821504 S2 M2 1
  5070602400912917605986812821504) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 5070602400912917605986812821504 S2 M2 1
    10141204801825835211973625643008)
  (jdouble.near (ite (= S 1) M (- M)) 5070602400912917605986812821504 S2 M2 1
    2535301200456458802993406410752)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-49, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2535301200456458802993406410752 S2 M2 1
  2535301200456458802993406410752) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2535301200456458802993406410752 S2 M2 1
    5070602400912917605986812821504)
  (jdouble.near (ite (= S 1) M (- M)) 2535301200456458802993406410752 S2 M2 1
    1267650600228229401496703205376)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-48, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1267650600228229401496703205376 S2 M2 1
  1267650600228229401496703205376) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1267650600228229401496703205376 S2 M2 1
    2535301200456458802993406410752)
  (jdouble.near (ite (= S 1) M (- M)) 1267650600228229401496703205376 S2 M2 1
    633825300114114700748351602688)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-47, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 633825300114114700748351602688 S2 M2 1
  633825300114114700748351602688) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 633825300114114700748351602688 S2 M2 1
    1267650600228229401496703205376)
  (jdouble.near (ite (= S 1) M (- M)) 633825300114114700748351602688 S2 M2 1
    316912650057057350374175801344)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-46, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 316912650057057350374175801344 S2 M2 1
  316912650057057350374175801344) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 316912650057057350374175801344 S2 M2 1
    633825300114114700748351602688)
  (jdouble.near (ite (= S 1) M (- M)) 316912650057057350374175801344 S2 M2 1
    158456325028528675187087900672)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-45, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 158456325028528675187087900672 S2 M2 1
  158456325028528675187087900672) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 158456325028528675187087900672 S2 M2 1
    316912650057057350374175801344)
  (jdouble.near (ite (= S 1) M (- M)) 158456325028528675187087900672 S2 M2 1
    79228162514264337593543950336)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-44, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 79228162514264337593543950336 S2 M2 1
  79228162514264337593543950336) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 79228162514264337593543950336 S2 M2 1
    158456325028528675187087900672)
  (jdouble.near (ite (= S 1) M (- M)) 79228162514264337593543950336 S2 M2 1
    39614081257132168796771975168)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-43, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 39614081257132168796771975168 S2 M2 1
  39614081257132168796771975168) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 39614081257132168796771975168 S2 M2 1
    79228162514264337593543950336)
  (jdouble.near (ite (= S 1) M (- M)) 39614081257132168796771975168 S2 M2 1
    19807040628566084398385987584)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-42, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 19807040628566084398385987584 S2 M2 1
  19807040628566084398385987584) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 19807040628566084398385987584 S2 M2 1
    39614081257132168796771975168)
  (jdouble.near (ite (= S 1) M (- M)) 19807040628566084398385987584 S2 M2 1
    9903520314283042199192993792)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-41, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9903520314283042199192993792 S2 M2 1
  9903520314283042199192993792) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9903520314283042199192993792 S2 M2 1
    19807040628566084398385987584)
  (jdouble.near (ite (= S 1) M (- M)) 9903520314283042199192993792 S2 M2 1
    4951760157141521099596496896)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-40, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4951760157141521099596496896 S2 M2 1
  4951760157141521099596496896) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4951760157141521099596496896 S2 M2 1
    9903520314283042199192993792)
  (jdouble.near (ite (= S 1) M (- M)) 4951760157141521099596496896 S2 M2 1
    2475880078570760549798248448)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-39, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2475880078570760549798248448 S2 M2 1
  2475880078570760549798248448) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2475880078570760549798248448 S2 M2 1
    4951760157141521099596496896)
  (jdouble.near (ite (= S 1) M (- M)) 2475880078570760549798248448 S2 M2 1
    1237940039285380274899124224)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-38, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1237940039285380274899124224 S2 M2 1
  1237940039285380274899124224) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1237940039285380274899124224 S2 M2 1
    2475880078570760549798248448)
  (jdouble.near (ite (= S 1) M (- M)) 1237940039285380274899124224 S2 M2 1
    618970019642690137449562112)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-37, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 618970019642690137449562112 S2 M2 1
  618970019642690137449562112) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 618970019642690137449562112 S2 M2 1
    1237940039285380274899124224)
  (jdouble.near (ite (= S 1) M (- M)) 618970019642690137449562112 S2 M2 1
    309485009821345068724781056)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-36, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 309485009821345068724781056 S2 M2 1
  309485009821345068724781056) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 309485009821345068724781056 S2 M2 1
    618970019642690137449562112)
  (jdouble.near (ite (= S 1) M (- M)) 309485009821345068724781056 S2 M2 1
    154742504910672534362390528)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-35, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 154742504910672534362390528 S2 M2 1
  154742504910672534362390528) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 154742504910672534362390528 S2 M2 1
    309485009821345068724781056)
  (jdouble.near (ite (= S 1) M (- M)) 154742504910672534362390528 S2 M2 1
    77371252455336267181195264)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-34, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 77371252455336267181195264 S2 M2 1
  77371252455336267181195264) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 77371252455336267181195264 S2 M2 1
    154742504910672534362390528)
  (jdouble.near (ite (= S 1) M (- M)) 77371252455336267181195264 S2 M2 1
    38685626227668133590597632)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-33, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 38685626227668133590597632 S2 M2 1
  38685626227668133590597632) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 38685626227668133590597632 S2 M2 1 77371252455336267181195264)
  (jdouble.near (ite (= S 1) M (- M)) 38685626227668133590597632 S2 M2 1
    19342813113834066795298816)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-32, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 19342813113834066795298816 S2 M2 1
  19342813113834066795298816) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 19342813113834066795298816 S2 M2 1 38685626227668133590597632)
  (jdouble.near (ite (= S 1) M (- M)) 19342813113834066795298816 S2 M2 1
    9671406556917033397649408)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-31, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9671406556917033397649408 S2 M2 1
  9671406556917033397649408) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9671406556917033397649408 S2 M2 1 19342813113834066795298816)
  (jdouble.near (ite (= S 1) M (- M)) 9671406556917033397649408 S2 M2 1 4835703278458516698824704)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-30, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4835703278458516698824704 S2 M2 1
  4835703278458516698824704) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4835703278458516698824704 S2 M2 1 9671406556917033397649408)
  (jdouble.near (ite (= S 1) M (- M)) 4835703278458516698824704 S2 M2 1 2417851639229258349412352)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-29, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2417851639229258349412352 S2 M2 1
  2417851639229258349412352) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2417851639229258349412352 S2 M2 1 4835703278458516698824704)
  (jdouble.near (ite (= S 1) M (- M)) 2417851639229258349412352 S2 M2 1 1208925819614629174706176)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-28, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1208925819614629174706176 S2 M2 1
  1208925819614629174706176) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1208925819614629174706176 S2 M2 1 2417851639229258349412352)
  (jdouble.near (ite (= S 1) M (- M)) 1208925819614629174706176 S2 M2 1 604462909807314587353088)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-27, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 604462909807314587353088 S2 M2 1
  604462909807314587353088) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 604462909807314587353088 S2 M2 1 1208925819614629174706176)
  (jdouble.near (ite (= S 1) M (- M)) 604462909807314587353088 S2 M2 1 302231454903657293676544)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-26, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 302231454903657293676544 S2 M2 1
  302231454903657293676544) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 302231454903657293676544 S2 M2 1 604462909807314587353088)
  (jdouble.near (ite (= S 1) M (- M)) 302231454903657293676544 S2 M2 1 151115727451828646838272)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-25, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 151115727451828646838272 S2 M2 1
  151115727451828646838272) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 151115727451828646838272 S2 M2 1 302231454903657293676544)
  (jdouble.near (ite (= S 1) M (- M)) 151115727451828646838272 S2 M2 1 75557863725914323419136)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-24, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 75557863725914323419136 S2 M2 1
  75557863725914323419136) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 75557863725914323419136 S2 M2 1 151115727451828646838272)
  (jdouble.near (ite (= S 1) M (- M)) 75557863725914323419136 S2 M2 1 37778931862957161709568)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-23, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 37778931862957161709568 S2 M2 1
  37778931862957161709568) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 37778931862957161709568 S2 M2 1 75557863725914323419136)
  (jdouble.near (ite (= S 1) M (- M)) 37778931862957161709568 S2 M2 1 18889465931478580854784)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-22, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 18889465931478580854784 S2 M2 1
  18889465931478580854784) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 18889465931478580854784 S2 M2 1 37778931862957161709568)
  (jdouble.near (ite (= S 1) M (- M)) 18889465931478580854784 S2 M2 1 9444732965739290427392)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-21, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9444732965739290427392 S2 M2 1
  9444732965739290427392) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9444732965739290427392 S2 M2 1 18889465931478580854784)
  (jdouble.near (ite (= S 1) M (- M)) 9444732965739290427392 S2 M2 1 4722366482869645213696)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-20, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4722366482869645213696 S2 M2 1
  4722366482869645213696) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4722366482869645213696 S2 M2 1 9444732965739290427392)
  (jdouble.near (ite (= S 1) M (- M)) 4722366482869645213696 S2 M2 1 2361183241434822606848)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-19, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2361183241434822606848 S2 M2 1
  2361183241434822606848) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2361183241434822606848 S2 M2 1 4722366482869645213696)
  (jdouble.near (ite (= S 1) M (- M)) 2361183241434822606848 S2 M2 1 1180591620717411303424)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-18, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1180591620717411303424 S2 M2 1
  1180591620717411303424) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1180591620717411303424 S2 M2 1 2361183241434822606848)
  (jdouble.near (ite (= S 1) M (- M)) 1180591620717411303424 S2 M2 1 590295810358705651712)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-17, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 590295810358705651712 S2 M2 1
  590295810358705651712) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 590295810358705651712 S2 M2 1 1180591620717411303424)
  (jdouble.near (ite (= S 1) M (- M)) 590295810358705651712 S2 M2 1 295147905179352825856)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-16, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 295147905179352825856 S2 M2 1
  295147905179352825856) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 295147905179352825856 S2 M2 1 590295810358705651712)
  (jdouble.near (ite (= S 1) M (- M)) 295147905179352825856 S2 M2 1 147573952589676412928)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-15, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 147573952589676412928 S2 M2 1
  147573952589676412928) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 147573952589676412928 S2 M2 1 295147905179352825856)
  (jdouble.near (ite (= S 1) M (- M)) 147573952589676412928 S2 M2 1 73786976294838206464)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-14, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 73786976294838206464 S2 M2 1
  73786976294838206464) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 73786976294838206464 S2 M2 1 147573952589676412928)
  (jdouble.near (ite (= S 1) M (- M)) 73786976294838206464 S2 M2 1 36893488147419103232)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-13, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 36893488147419103232 S2 M2 1
  36893488147419103232) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 36893488147419103232 S2 M2 1 73786976294838206464)
  (jdouble.near (ite (= S 1) M (- M)) 36893488147419103232 S2 M2 1 18446744073709551616)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-12, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 18446744073709551616 S2 M2 1
  18446744073709551616) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 18446744073709551616 S2 M2 1 36893488147419103232)
  (jdouble.near (ite (= S 1) M (- M)) 18446744073709551616 S2 M2 1 9223372036854775808)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-11, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9223372036854775808 S2 M2 1
  9223372036854775808) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9223372036854775808 S2 M2 1 18446744073709551616)
  (jdouble.near (ite (= S 1) M (- M)) 9223372036854775808 S2 M2 1 4611686018427387904)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-10, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 4611686018427387904 S2 M2 1
  4611686018427387904) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 4611686018427387904 S2 M2 1 9223372036854775808)
  (jdouble.near (ite (= S 1) M (- M)) 4611686018427387904 S2 M2 1 2305843009213693952)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-9, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 2305843009213693952 S2 M2 1
  2305843009213693952) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 2305843009213693952 S2 M2 1 4611686018427387904)
  (jdouble.near (ite (= S 1) M (- M)) 2305843009213693952 S2 M2 1 1152921504606846976)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-8, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 1152921504606846976 S2 M2 1
  1152921504606846976) (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 1152921504606846976 S2 M2 1 2305843009213693952)
  (jdouble.near (ite (= S 1) M (- M)) 1152921504606846976 S2 M2 1 576460752303423488)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-7, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 576460752303423488 S2 M2 1 576460752303423488)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 576460752303423488 S2 M2 1 1152921504606846976)
  (jdouble.near (ite (= S 1) M (- M)) 576460752303423488 S2 M2 1 288230376151711744)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-6, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 288230376151711744 S2 M2 1 288230376151711744)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 288230376151711744 S2 M2 1 576460752303423488)
  (jdouble.near (ite (= S 1) M (- M)) 288230376151711744 S2 M2 1 144115188075855872)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-5, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 144115188075855872 S2 M2 1 144115188075855872)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 144115188075855872 S2 M2 1 288230376151711744)
  (jdouble.near (ite (= S 1) M (- M)) 144115188075855872 S2 M2 1 72057594037927936)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-4, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 72057594037927936 S2 M2 1 72057594037927936)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 72057594037927936 S2 M2 1 144115188075855872)
  (jdouble.near (ite (= S 1) M (- M)) 72057594037927936 S2 M2 1 36028797018963968)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-3, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 36028797018963968 S2 M2 1 36028797018963968)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 36028797018963968 S2 M2 1 72057594037927936)
  (jdouble.near (ite (= S 1) M (- M)) 36028797018963968 S2 M2 1 18014398509481984)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-2, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 18014398509481984 S2 M2 1 18014398509481984)
  (not (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 18014398509481984 S2 M2 1 36028797018963968)
  (jdouble.near (ite (= S 1) M (- M)) 18014398509481984 S2 M2 1 9007199254740992)))
(check-sat)
(pop 1)

(echo "a double of binade 2^-1, either sign, rounds to no other double: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const S2 Int) (declare-const M2 Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M)))
(assert (or (and (jdouble.near (ite (= S 1) M (- M)) 9007199254740992 S2 M2 1 9007199254740992) (not
  (and (= S2 S) (= M2 M))))
  (jdouble.near (ite (= S 1) M (- M)) 9007199254740992 S2 M2 1 18014398509481984)
  (jdouble.near (ite (= S 1) M (- M)) 9007199254740992 S2 M2 1 4503599627370496)))
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

; Java's &, | and ^ on longs and ints: bit-vector operators on the two's-complement pattern,
; read back signed. Pinned to the JVM below.
(define-fun long.of ((r (_ BitVec 64))) Int
  (- (bv2nat r) (ite (bvslt r #x0000000000000000) 18446744073709551616 0)))
(define-fun int.of ((r (_ BitVec 32))) Int
  (- (bv2nat r) (ite (bvslt r #x00000000) 4294967296 0)))
(define-fun jlong.and ((a Int) (b Int)) Int
  (long.of (bvand ((_ int2bv 64) a) ((_ int2bv 64) b))))
(define-fun jlong.or ((a Int) (b Int)) Int
  (long.of (bvor ((_ int2bv 64) a) ((_ int2bv 64) b))))
(define-fun jlong.xor ((a Int) (b Int)) Int
  (long.of (bvxor ((_ int2bv 64) a) ((_ int2bv 64) b))))
(define-fun jint.and ((a Int) (b Int)) Int
  (int.of (bvand ((_ int2bv 32) a) ((_ int2bv 32) b))))
(define-fun jint.or ((a Int) (b Int)) Int
  (int.of (bvor ((_ int2bv 32) a) ((_ int2bv 32) b))))
(define-fun jint.xor ((a Int) (b Int)) Int
  (int.of (bvxor ((_ int2bv 32) a) ((_ int2bv 32) b))))

; Section C: xn's bits, for every 20-bit hx, either i and every low word lo: sign 1, and
; binade 2^-1 (E = -53) or 2^0 (E = -52); xn = 1 (M = 2^52, E = -52) only for hx = 0 and
; lo = 0 with i = 0x100000, the lanes 1.0 itself reduces to.
(define-fun xn.bits ((hx Int) (i Int) (lo Int)) Int
  (jlong.or (jlong.shl (i2l (jint.or hx (jint.xor i 1072693248))) 32) lo))

(echo "xn has sign 1 and binade 2^-1 or 2^0: it lies in [0.5, 2): expect unsat")
(push 1)
(declare-const hx Int) (declare-const i Int) (declare-const lo Int)
(assert (and (<= 0 hx) (< hx 1048576) (or (= i 0) (= i 1048576)) (<= 0 lo) (< lo 4294967296)))
(assert (not (and (= (jdouble.fromBits.S (xn.bits hx i lo)) 1)
  (or (= (jdouble.fromBits.E (xn.bits hx i lo)) (- 53)) (= (jdouble.fromBits.E (xn.bits hx i lo)) (-
    52))))))
(check-sat)
(pop 1)

(echo "xn = 1 only at hx = 0, lo = 0, i = 0x100000: expect unsat")
(push 1)
(declare-const hx Int) (declare-const i Int) (declare-const lo Int)
(assert (and (<= 0 hx) (< hx 1048576) (or (= i 0) (= i 1048576)) (<= 0 lo) (< lo 4294967296)))
(assert (= (jdouble.fromBits.M (xn.bits hx i lo)) 4503599627370496))
(assert (= (jdouble.fromBits.E (xn.bits hx i lo)) (- 52)))
(assert (not (and (= hx 0) (= lo 0) (= i 1048576))))
(check-sat)
(pop 1)

(echo "not vacuous: some xn is 1: expect sat")
(push 1)
(declare-const hx Int) (declare-const i Int) (declare-const lo Int)
(assert (and (<= 0 hx) (< hx 1048576) (or (= i 0) (= i 1048576)) (<= 0 lo) (< lo 4294967296)))
(assert (= (xn.bits hx i lo) 4607182418800017408))
(check-sat)
(pop 1)

(echo "ln2_hi and ln2_lo are positive and finite, as the zero product needs: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S 4604418534311723008) 1) (< (jdouble.fromBits.E
  4604418534311723008) 972)
  (= (jdouble.fromBits.S 4461442080421002358) 1) (< (jdouble.fromBits.E 4461442080421002358) 972))))
(check-sat)
(pop 1)
