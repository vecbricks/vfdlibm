; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; expm1's reduction is exp's, and its unreduced lanes keep x with c = +0 (PROOFS.md, L10).
;
; s_expm1.c (reference/fdlibm-5.3) reduces hx > 0x3fd62e42 by a near and a far form (lines
; 162-175) and leaves k = 0 below it (line 181), never reading c there. The port (Expm1)
; computes on every lane k = (int) fl(fl(x*invln2) + h), with +0.0 as the cast's argument
; where hx <= 0x3fd62e42, then t = k, hi = x - fl(t*ln2_hi), lo = fl(t*ln2_lo), r = hi - lo
; and c = (hi - r) - lo.
;
; What is proven:
;   - Section A (H13): Expm1's invln2, ln2_hi, ln2_lo, -ln2_lo, the bits of 0.5 and the
;     two words of the reduction, rendered from Expm1, are Exp's invln2, ln2HI[0],
;     ln2LO[0], ln2LO[1], HALF_BITS and words, rendered from Exp, bit for bit; so
;     exp_near_band.smt2 (L2) and exp_half_sign.smt2 (L5), rendered from Exp's, are about
;     expm1's reduction too. Expm1's LN2_HI_UP, the value the planted fault
;     expm1.constantOffByOneUlp uses, is ln2_hi one ulp up and is not Exp's.
;   - Section B (step 3 of the proof), t = +0: the sign of an exact zero (IEEE 754 6.3)
;     as z.sub, z.add and z.mul, pinned to the JVM over every pair of signed zeros and
;     over +-0 times Expm1's ln2_hi and ln2_lo; so +0 * ln2_hi and +0 * ln2_lo are +0. A
;     nonzero normal x less +0 is x: jdouble.near admits the double for itself in any
;     binade and no other double of its binade or the next ones, every normal binade at
;     once. So hi = x, lo = +0 and r = x, and for a zero x of either sign likewise. Then
;     hi - r is x - x, +0 (the rule for like signs, pinned at sampled doubles while
;     rendering, or a zero difference of like-signed zeros), and c = +0 - (+0) = +0.
;     With t = -0 the first step fails at x = -0 and nowhere else: that check refutes
;     the step as stated for every x, and x = -0 is a lane whose r nothing reads. The
;     ends of the lanes that are read, 0x3c900000_00000000 and 0x3fd62e42_ffffffff, are
;     rendered from Expm1's words, decoded and admitted.
;   - Section C (H14): the two witnesses 0x3fd62e42_ffffffec and 0xbfd62e42_fffffff3
;     have the high word 0x3fd62e42, so line 162's test leaves them unreduced; there the
;     formula's fl(fl(x*invln2) + h) rounds into binade 2^0 with x's sign, so its cast is
;     +-1, not 0. Then each rounding of the C as written (k = 0, line 189's return) and of
;     the C reduced with that k (lines 169-175, 184-188 and 191-196) is evaluated: for
;     every operation, the exact result on the two doubles Java computed before it rounds
;     to the double Java computed and to no other double in its binade or the next ones.
;     The two results differ. At the word's top double, either sign, the formula's cast
;     is +-1 as well and the same two chains end in one double: the witnesses are not an
;     accident of the chains.
;
; What is not machine-checked:
;   - fl(x - (-ln2_hi)) = fl(x + ln2_hi), step 2's use of (R4): both are the rounding of
;     one rational in this encoding, so there is no check to write.
;   - That StrictMath.expm1 is the k = 0 computation at the witnesses: the oracle is in
;     no solver (ProofsTest.l10KIsForcedByTheHighWordTest).
;   - That the witnesses are the first from the word's top, and how many doubles of the
;     word differ: a scalar run of the C's transcription (PROOFS.md, H14).
;   - A subnormal x less +0: the lanes of step 3 that are read, hx >= 0x3c900000, are
;     normal, and the lanes below return line 179's form of x itself.
;   - That D2I truncates toward zero, so a double of binade 2^0 casts to +-1, that D2I of
;     +0.0 is 0 and I2D of 0 is +0.0 (JLS 5.1.3, 5.1.2); that a value rounds to no double
;     in a binade further off than the next one (the arithmetic of rationals); and that
;     the port computes these steps: the code's text.
;
; Constants. Section A compares the rendered constants as ground terms, so a constant
; that moves on one side fails its check; it is as strong as ProofsTest's
; l10ReductionIsExps, and refutes only the planted fault's LN2_HI_UP. No claim of
; section B is tight to ln2_hi or ln2_lo beyond their sign and finiteness. Section C's
; cast holds for a range of invln2 (its margin is that of exp_zero_reduction.smt2's
; section C), and its chains evaluate whatever Q1..Q5 Expm1 holds and compare the
; results with no source value, so with a Q moved by one ulp the file renders anew and
; passes as long as the witnesses still differ and the controls still agree.
; Expm1Test.constantsAreTheSourcesBits pins them all to the source's hex, and
; ProofFilesTest this file to its rendering. The chains are a second transcription of
; the C: while rendering, the k = 0 chain's result is held to StrictMath.expm1 and the
; free k's to Expm1Test.cExpm1(x, true), which the scalar tests hold to the oracle.
;
; The logic is ALL: section B's products S * M * sn are nonlinear. Section C's checks
; carry their own non-vacuity: each asserts that the named double is not admitted, or
; another one is, so a jdouble.near that admitted nothing would make it sat.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

; Section A: H13. Each constant as its raw bits, or the word itself; Expm1's on the left,
; Exp's on the right.

(echo "expm1's invln2, 0x3ff71547_652b82fe, is exp's invln2: expect unsat")
(push 1)
(assert (not (= 4609176140021203710 4609176140021203710)))
(check-sat)
(pop 1)

(echo "expm1's ln2_hi, 0x3fe62e42_fee00000, is exp's ln2HI[0]: expect unsat")
(push 1)
(assert (not (= 4604418534311723008 4604418534311723008)))
(check-sat)
(pop 1)

(echo "expm1's ln2_lo, 0x3dea39ef_35793c76, is exp's ln2LO[0]: expect unsat")
(push 1)
(assert (not (= 4461442080421002358 4461442080421002358)))
(check-sat)
(pop 1)

(echo "expm1's -ln2_lo, 0xbdea39ef_35793c76, is exp's ln2LO[1]: expect unsat")
(push 1)
(assert (not (= (- 4761929956433773450) (- 4761929956433773450))))
(check-sat)
(pop 1)

(echo "expm1's HALF_BITS, 0x3fe00000_00000000, is exp's HALF_BITS: expect unsat")
(push 1)
(assert (not (= 4602678819172646912 4602678819172646912)))
(check-sat)
(pop 1)

(echo "expm1's HX_HALF_LN2, 0x3fd62e42, is exp's HX_HALF_LN2: expect unsat")
(push 1)
(assert (not (= 1071001154 1071001154)))
(check-sat)
(pop 1)

(echo "expm1's HX_THREE_HALVES_LN2, 0x3ff0a2b2, is exp's HX_THREE_HALVES_LN2: expect unsat")
(push 1)
(assert (not (= 1072734898 1072734898)))
(check-sat)
(pop 1)

(echo "refuted: LN2_HI_UP, 0x3fe62e42_fee00001, the fault's, is not exp's ln2HI[0]: expect sat")
(push 1)
(assert (not (= 4604418534311723009 4604418534311723008)))
(check-sat)
(pop 1)

(echo "that value is ln2_hi with M one more, same sign and binade: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.M 4604418534311723009) (+ 1 (jdouble.fromBits.M
  4604418534311723008)))
  (= (jdouble.fromBits.E 4604418534311723009) (jdouble.fromBits.E 4604418534311723008))
  (= (jdouble.fromBits.S 4604418534311723009) 1))))
(check-sat)
(pop 1)

; Section B: step 3, with t = +0. The sign of an exact zero, true for -0. z.sub sx sy is
; the sign of x - y and z.add sx sy that of x + y for zeros x and y; z.mul is a zero
; product's.
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

(echo "ln2_hi and ln2_lo are positive and finite, as the zero product needs: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S 4604418534311723008) 1) (< (jdouble.fromBits.E
  4604418534311723008) 972)
  (= (jdouble.fromBits.S 4461442080421002358) 1) (< (jdouble.fromBits.E 4461442080421002358) 972))))
(check-sat)
(pop 1)

(echo "t = +0: t*ln2_hi and t*ln2_lo are +0: expect unsat")
(push 1)
(assert (z.mul false false))
(check-sat)
(pop 1)

; hi = x - t*ln2_hi and r = hi - lo, for a zero x of sign sx: both keep it. For a nonzero
; x they are x by the checks after these. Then c = (hi - r) - lo: hi - r is x - x, which
; is +0 for a nonzero x (eq, the rule for like signs, Java's x - x while rendering) and
; z.sub sx sx for a zero x; less lo = +0.
(define-fun hi.sign ((sx Bool)) Bool (z.sub sx (z.mul false false)))
(define-fun r.sign ((sx Bool)) Bool (z.sub (hi.sign sx) (z.mul false false)))
(define-fun c.sign ((eq Bool) (sx Bool)) Bool
  (z.sub (ite eq false (z.sub (hi.sign sx) (r.sign sx))) (z.mul false false)))

(echo "a zero x, either sign: hi = x - (+0) and r = hi - (+0) are x: expect unsat")
(push 1)
(declare-const sx Bool)
(assert (not (and (= (hi.sign sx) sx) (= (r.sign sx) sx))))
(check-sat)
(pop 1)

(echo "c = (hi - r) - lo is +0, x nonzero or a zero of either sign: expect unsat")
(push 1)
(declare-const eq Bool) (declare-const sx Bool)
(assert (c.sign eq sx))
(check-sat)
(pop 1)

(echo "refuted: with t = -0, hi = x - t*ln2_hi is not x for some zero x: expect sat")
(push 1)
(declare-const sx Bool)
(assert (not (= (z.sub sx (z.mul true false)) sx)))
(check-sat)
(pop 1)

(echo "that zero is x = -0: -0 - (-0) is +0: expect unsat")
(push 1)
(assert (not (and (z.mul true false) (not (z.sub true (z.mul true false)))
  (not (z.sub false (z.mul true false))))))
(check-sat)
(pop 1)

; A nonzero normal x less +0 is x: the exact difference is the double S * M * 2^E, a / b
; with a = S * M * sn and b = sd.

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

; The ends of the lanes step 3 is read on, from Expm1's words: normal doubles, each one
; admitted for itself in its binade.

(echo "the bottom, 0x3c900000_00000000: S = 1, binade 2^-54, M = 4503599627370496: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S 4363988038922010624) 1)
  (= (jdouble.fromBits.E 4363988038922010624) (- 106))
  (= (jdouble.fromBits.M 4363988038922010624) 4503599627370496))))
(check-sat)
(pop 1)

(echo "not vacuous: the bottom, 0x3c900000_00000000 less +0 is admitted as itself: expect sat")
(push 1)
(declare-const M2 Int)
(assert (jdouble.near 4503599627370496 81129638414606681695789005144064 1 M2 1
  81129638414606681695789005144064))
(assert (= M2 4503599627370496))
(check-sat)
(pop 1)

(echo "the top, 0x3fd62e42_ffffffff: S = 1, binade 2^-2, M = 6243314785320959: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S 4599914934703226879) 1)
  (= (jdouble.fromBits.E 4599914934703226879) (- 54))
  (= (jdouble.fromBits.M 4599914934703226879) 6243314785320959))))
(check-sat)
(pop 1)

(echo "not vacuous: the top, 0x3fd62e42_ffffffff less +0 is admitted as itself: expect sat")
(push 1)
(declare-const M2 Int)
(assert (jdouble.near 6243314785320959 18014398509481984 1 M2 1 18014398509481984))
(assert (= M2 6243314785320959))
(check-sat)
(pop 1)

; Section C: H14. The C's hx, its high word of |x| (lines 140-143).
(define-fun c.hx ((b Int)) Int (jint.and.low (l2i (jlong.shr b 32)) 2147483648))

; The witness 0x3fd62e42_ffffffec.

(echo "at 0x3fd62e42_ffffffec: hx is 0x3fd62e42, not above it: unreduced: expect unsat")
(push 1)
(assert (not (= (c.hx 4599914934703226860) 1071001154)))
(check-sat)
(pop 1)

(echo "at 0x3fd62e42_ffffffec: x*invln2 rounds into 2^-1 only, with x's sign: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (jdouble.near (* 6497320848556798 6243314785320940) 81129638414606681695789005144064 S2
  M2 1 18014398509481984)
  (jdouble.near (* 6497320848556798 6243314785320940) 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near (* 6497320848556798 6243314785320940) 81129638414606681695789005144064 (- 1) M2 1
    9007199254740992)))
(check-sat)
(pop 1)

(echo "at 0x3fd62e42_ffffffec: the sum with h rounds into 2^0, x's sign: k = 1: expect unsat")
(push 1)
(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320940) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (or (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 (- 1) M2 1 4503599627370496)))
(check-sat)
(pop 1)

(echo "not vacuous: at 0x3fd62e42_ffffffec the sum rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320940) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: hfx = 0.5*x = 0x3fc62e42_ffffffec: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 28117390140728093613991646986240 162259276829213363391578010288128 1
  6243314785320940 1 36028797018963968))
  (or (and (jdouble.near 28117390140728093613991646986240 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6243314785320940))))
  (jdouble.near 28117390140728093613991646986240 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 28117390140728093613991646986240 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: hxs = x*hfx = 0x3faebfbe_02588fc9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979508607055119298802483600 649037107316853453566312041152512 1
  8655072105369545 1 144115188075855872))
  (or (and (jdouble.near 38978979508607055119298802483600 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072105369545))))
  (jdouble.near 38978979508607055119298802483600 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979508607055119298802483600 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: hxs*Q5 = 0xbe49ef96_9a6d9d10: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755297546985697507869291209045)
  5444517870735015415413993718908291383296 (- 1) 7300304532774160 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755297546985697507869291209045)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304532774160))))
  (jdouble.near (- 65755297546985697507869291209045) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755297546985697507869291209045) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: Q4+hxs*Q5 = 0x3ed0c2d2_bb991b6a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144599285482095085017342771462144
  713623846352979940529142984724747568191373312 1 4717809973664618 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144599285482095085017342771462144
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973664618))))
  (jdouble.near 2851741144599285482095085017342771462144
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144599285482095085017342771462144
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: hxs*(Q4+...) = 0x3e901b13_efad0dab: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985501498862963998781258810
  170141183460469231731687303715884105728 1 4533372066794923 1 18889465931478580854784))
  (or (and (jdouble.near 40832985501498862963998781258810 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372066794923))))
  (jdouble.near 40832985501498862963998781258810 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985501498862963998781258810 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: Q3+hxs*(...) = 0xbf14bdfe_8abb2ea9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266981669045779349310206579310592)
  1393796574908163946345982392040522594123776 (- 1) 5838400481078953 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266981669045779349310206579310592)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481078953))))
  (jdouble.near (- 110284266981669045779349310206579310592)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266981669045779349310206579310592)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: hxs*(Q3+...) = 0xbed3ee67_d2f8284c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531777143762578118686386686385)
  10633823966279326983230456482242756608 (- 1) 5610154246023244 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531777143762578118686386686385) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154246023244))))
  (jdouble.near (- 50531777143762578118686386686385) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531777143762578118686386686385) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: Q2+hxs*(...) = 0x3f59edb1_b22b5d5d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588157287039236597548706693120
  5444517870735015415413993718908291383296 1 7298221871947101 1 4611686018427387904))
  (or (and (jdouble.near 8616219588157287039236597548706693120
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221871947101))))
  (jdouble.near 8616219588157287039236597548706693120 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588157287039236597548706693120 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: hxs*(Q2+...) = 0x3f18ea33_48ddc983: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166636542687257302386296439045 664613997892457936451903530140172288
  1 7012905427782019 1 73786976294838206464))
  (or (and (jdouble.near 63166636542687257302386296439045 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905427782019))))
  (jdouble.near 63166636542687257302386296439045 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166636542687257302386296439045 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: Q1+hxs*(...) = 0xbfa1049b_f76ca20f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132691292425218555069367058432)
  10633823966279326983230456482242756608 (- 1) 4790142521614863 1 144115188075855872))
  (or (and (jdouble.near (- 353450132691292425218555069367058432)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521614863))))
  (jdouble.near (- 353450132691292425218555069367058432) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132691292425218555069367058432) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: hxs*(Q1+...) = 0xbf605a4a_c780184e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028919573333522846779547335)
  20769187434139310514121985316880384 (- 1) 4602876848511054 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028919573333522846779547335) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876848511054))))
  (jdouble.near (- 41459028919573333522846779547335) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028919573333522846779547335) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: r1 = one+hxs*(...) = 0x3fefefa5_b5387fe8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202609868591300349848977408
  10384593717069655257060992658440192 1 8989219267051496 1 9007199254740992))
  (or (and (jdouble.near 10363864202609868591300349848977408 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267051496))))
  (jdouble.near 10363864202609868591300349848977408 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202609868591300349848977408 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: r1*hfx = 0x3fc622ed_488e4c82: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 56122525558474468364735867126240 324518553658426726783156020576256 1
  6230852007513218 1 36028797018963968))
  (or (and (jdouble.near 56122525558474468364735867126240 324518553658426726783156020576256 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6230852007513218))))
  (jdouble.near 56122525558474468364735867126240 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near 56122525558474468364735867126240 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: t = 3.0-r1*hfx = 0x40069dd1_2b771b38: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 229358283854201427323494363824128 81129638414606681695789005144064 1
  6365971190586168 1 2251799813685248))
  (or (and (jdouble.near 229358283854201427323494363824128 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 6365971190586168))))
  (jdouble.near 229358283854201427323494363824128 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 229358283854201427323494363824128 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: r1-t = 0xbffd43cf_7c51f67c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 37097648692827957678294746267648) 20282409603651670423947251286016
  (- 1) 8237332747646588 1 4503599627370496))
  (or (and (jdouble.near (- 37097648692827957678294746267648) 20282409603651670423947251286016 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 8237332747646588))))
  (jdouble.near (- 37097648692827957678294746267648) 20282409603651670423947251286016 S2 M2 1
    9007199254740992)
  (jdouble.near (- 37097648692827957678294746267648) 20282409603651670423947251286016 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: x*t = 0x3fef5a64_09fca1c2: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 39744762057113770284800604757920 40564819207303340847894502572032 1
  8825109988811202 1 9007199254740992))
  (or (and (jdouble.near 39744762057113770284800604757920 40564819207303340847894502572032 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 8825109988811202))))
  (jdouble.near 39744762057113770284800604757920 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near 39744762057113770284800604757920 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: 6.0-x*t = 0x401414b3_7ec06bc8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 50911038296676568409082691584000 10141204801825835211973625643008 1
  5652260692454344 1 1125899906842624))
  (or (and (jdouble.near 50911038296676568409082691584000 10141204801825835211973625643008 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 5652260692454344))))
  (jdouble.near 50911038296676568409082691584000 10141204801825835211973625643008 S2 M2 1
    2251799813685248)
  (jdouble.near 50911038296676568409082691584000 10141204801825835211973625643008 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: (r1-t)/(6.0-x*t) = 0xbfd75150_3485002d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9274412173206989419573686566912) 25455519148338285330441252634624
  (- 1) 6563329384710189 1 18014398509481984))
  (or (and (jdouble.near (- 9274412173206989419573686566912) 25455519148338285330441252634624 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6563329384710189))))
  (jdouble.near (- 9274412173206989419573686566912) 25455519148338285330441252634624 S2 M2 1
    36028797018963968)
  (jdouble.near (- 9274412173206989419573686566912) 25455519148338285330441252634624 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: e = hxs*(...) = 0xbf9667f2_fc77ff3f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56806089075957415880650571794005)
  2596148429267413814265248164610048 (- 1) 6306742803103551 1 288230376151711744))
  (or (and (jdouble.near (- 56806089075957415880650571794005) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 6306742803103551))))
  (jdouble.near (- 56806089075957415880650571794005) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 56806089075957415880650571794005) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: x*e = 0xbf7f0fb6_c904590f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 39374980589832829879529588657940)
  5192296858534827628530496329220096 (- 1) 8743002009000207 1 1152921504606846976))
  (or (and (jdouble.near (- 39374980589832829879529588657940) 5192296858534827628530496329220096 S2
    M2 1 1152921504606846976) (not (and (= S2 (- 1)) (= M2 8743002009000207))))
  (jdouble.near (- 39374980589832829879529588657940) 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near (- 39374980589832829879529588657940) 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: x*e-hxs = 0xbfb150da_6dbc8d75: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 11238618133078057196535520295911424)
  166153499473114484112975882535043072 (- 1) 4873973678247285 1 72057594037927936))
  (or (and (jdouble.near (- 11238618133078057196535520295911424)
    166153499473114484112975882535043072 S2 M2 1 72057594037927936) (not (and (= S2 (- 1)) (= M2
    4873973678247285))))
  (jdouble.near (- 11238618133078057196535520295911424) 166153499473114484112975882535043072 S2 M2 1
    144115188075855872)
  (jdouble.near (- 11238618133078057196535520295911424) 166153499473114484112975882535043072 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffec: x-(x*e-hxs) = 0x3fda8279_9b6f2349: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 537679946416321811790500756193280 1298074214633706907132624082305024
  1 7461808204882761 1 18014398509481984))
  (or (and (jdouble.near 537679946416321811790500756193280 1298074214633706907132624082305024 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 7461808204882761))))
  (jdouble.near 537679946416321811790500756193280 1298074214633706907132624082305024 S2 M2 1
    36028797018963968)
  (jdouble.near 537679946416321811790500756193280 1298074214633706907132624082305024 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: t*ln2_hi = 0x3fe62e42_fee00000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 1
  6243314766446592 1 9007199254740992))
  (or (and (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 6243314766446592))))
  (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hi = x-t*ln2_hi = 0xbfd62e42_fdc00014: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56234779941446160749339028226048)
  162259276829213363391578010288128 (- 1) 6243314747572244 1 18014398509481984))
  (or (and (jdouble.near (- 56234779941446160749339028226048) 162259276829213363391578010288128 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6243314747572244))))
  (jdouble.near (- 56234779941446160749339028226048) 162259276829213363391578010288128 S2 M2 1
    36028797018963968)
  (jdouble.near (- 56234779941446160749339028226048) 162259276829213363391578010288128 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: lo = t*ln2_lo = 0x3dea39ef_35793c76: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33245792907574618198748633759744
  174224571863520493293247799005065324265472 1 7382048951581814 1 38685626227668133590597632))
  (or (and (jdouble.near 33245792907574618198748633759744 174224571863520493293247799005065324265472
    S2 M2 1 38685626227668133590597632) (not (and (= S2 1) (= M2 7382048951581814))))
  (jdouble.near 33245792907574618198748633759744 174224571863520493293247799005065324265472 S2 M2 1
    77371252455336267181195264)
  (jdouble.near 33245792907574618198748633759744 174224571863520493293247799005065324265472 S2 M2 1
    19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: x = hi-lo = 0xbfd62e42_fdf473f2: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 241526540879251226993468452642291590365184)
  696898287454081973172991196020261297061888 (- 1) 6243314751009778 1 18014398509481984))
  (or (and (jdouble.near (- 241526540879251226993468452642291590365184)
    696898287454081973172991196020261297061888 S2 M2 1 18014398509481984) (not (and (= S2 (- 1)) (=
    M2 6243314751009778))))
  (jdouble.near (- 241526540879251226993468452642291590365184)
    696898287454081973172991196020261297061888 S2 M2 1 36028797018963968)
  (jdouble.near (- 241526540879251226993468452642291590365184)
    696898287454081973172991196020261297061888 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hi-x = 0x3dea39ef_00000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 61925107365893642387456 324518553658426726783156020576256 1
  7382048054444032 1 38685626227668133590597632))
  (or (and (jdouble.near 61925107365893642387456 324518553658426726783156020576256 S2 M2 1
    38685626227668133590597632) (not (and (= S2 1) (= M2 7382048054444032))))
  (jdouble.near 61925107365893642387456 324518553658426726783156020576256 S2 M2 1
    77371252455336267181195264)
  (jdouble.near 61925107365893642387456 324518553658426726783156020576256 S2 M2 1
    19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: c = (hi-x)-lo = 0xbc7abc9e_3b000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 34706336909171216401548455626932224)
  1496577676626844588240573268701473812127674924007424 (- 1) 7525737175187456 1
  324518553658426726783156020576256))
  (or (and (jdouble.near (- 34706336909171216401548455626932224)
    1496577676626844588240573268701473812127674924007424 S2 M2 1 324518553658426726783156020576256)
    (not (and (= S2 (- 1)) (= M2 7525737175187456))))
  (jdouble.near (- 34706336909171216401548455626932224)
    1496577676626844588240573268701473812127674924007424 S2 M2 1 649037107316853453566312041152512)
  (jdouble.near (- 34706336909171216401548455626932224)
    1496577676626844588240573268701473812127674924007424 S2 M2 1
    162259276829213363391578010288128))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hfx = 0.5*x = 0xbfc62e42_fdf473f2: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 28117389986204357216142924709888)
  162259276829213363391578010288128 (- 1) 6243314751009778 1 36028797018963968))
  (or (and (jdouble.near (- 28117389986204357216142924709888) 162259276829213363391578010288128 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6243314751009778))))
  (jdouble.near (- 28117389986204357216142924709888) 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near (- 28117389986204357216142924709888) 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hxs = x*hfx = 0x3faebfbd_fcacfb54: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979080176286264270651609284 649037107316853453566312041152512 1
  8655072010238804 1 144115188075855872))
  (or (and (jdouble.near 38978979080176286264270651609284 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072010238804))))
  (jdouble.near 38978979080176286264270651609284 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979080176286264270651609284 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hxs*Q5 = 0xbe49ef96_95a53f62: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755296824247619284412656331204)
  5444517870735015415413993718908291383296 (- 1) 7300304452534114 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755296824247619284412656331204)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304452534114))))
  (jdouble.near (- 65755296824247619284412656331204) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755296824247619284412656331204) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: Q4+hxs*Q5 = 0x3ed0c2d2_bb9b7f99: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144694016208048664653330433179648
  713623846352979940529142984724747568191373312 1 4717809973821337 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144694016208048664653330433179648
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973821337))))
  (jdouble.near 2851741144694016208048664653330433179648
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144694016208048664653330433179648
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hxs*(Q4+...) = 0x3e901b13_ecb70a26: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985054046518502465800560948
  170141183460469231731687303715884105728 1 4533372017117734 1 18889465931478580854784))
  (or (and (jdouble.near 40832985054046518502465800560948 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372017117734))))
  (jdouble.near 40832985054046518502465800560948 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985054046518502465800560948 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: Q3+hxs*(...) = 0xbf14bdfe_8abe24ad: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266985334575346486507513512460288)
  1393796574908163946345982392040522594123776 (- 1) 5838400481273005 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266985334575346486507513512460288)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481273005))))
  (jdouble.near (- 110284266985334575346486507513512460288)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266985334575346486507513512460288)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hxs*(Q3+...) = 0xbed3ee67_cf4e1966: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531776590030748132619968686020)
  10633823966279326983230456482242756608 (- 1) 5610154184546662 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531776590030748132619968686020) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154184546662))))
  (jdouble.near (- 50531776590030748132619968686020) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531776590030748132619968686020) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: Q2+hxs*(...) = 0x3f59edb1_b22f076c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588440797732906702372232757248
  5444517870735015415413993718908291383296 1 7298221872187244 1 4611686018427387904))
  (or (and (jdouble.near 8616219588440797732906702372232757248
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221872187244))))
  (jdouble.near 8616219588440797732906702372232757248 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588440797732906702372232757248 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hxs*(Q2+...) = 0x3f18ea33_444924aa: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166635850480457599406242616176 664613997892457936451903530140172288
  1 7012905350931626 1 73786976294838206464))
  (or (and (jdouble.near 63166635850480457599406242616176 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905350931626))))
  (jdouble.near 63166635850480457599406242616176 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166635850480457599406242616176 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: Q1+hxs*(...) = 0xbfa1049b_f76eec62: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132702367734059453506941616128)
  10633823966279326983230456482242756608 (- 1) 4790142521764962 1 144115188075855872))
  (or (and (jdouble.near (- 353450132702367734059453506941616128)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521764962))))
  (jdouble.near (- 353450132702367734059453506941616128) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132702367734059453506941616128) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: hxs*(Q1+...) = 0xbf605a4a_c47e53c2: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028465182643599681179985448)
  20769187434139310514121985316880384 (- 1) 4602876798063554 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028465182643599681179985448) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876798063554))))
  (jdouble.near (- 41459028465182643599681179985448) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028465182643599681179985448) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: r1 = one+hxs*(...) = 0x3fefefa5_b53b81ac: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202837063933502122945937408
  10384593717069655257060992658440192 1 8989219267248556 1 9007199254740992))
  (or (and (jdouble.near 10363864202837063933502122945937408 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267248556))))
  (jdouble.near 10363864202837063933502122945937408 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202837063933502122945937408 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: r1*hfx = 0xbfc622ed_4685e1a3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56122525251274217444244712380568)
  324518553658426726783156020576256 (- 1) 6230851973407139 1 36028797018963968))
  (or (and (jdouble.near (- 56122525251274217444244712380568) 324518553658426726783156020576256 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6230851973407139))))
  (jdouble.near (- 56122525251274217444244712380568) 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near (- 56122525251274217444244712380568) 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: t = 3.0-r1*hfx = 0x4009622e_d4685e1a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 257419546556638600513505317617664 81129638414606681695789005144064 1
  7144827689393690 1 2251799813685248))
  (or (and (jdouble.near 257419546556638600513505317617664 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 7144827689393690))))
  (jdouble.near 257419546556638600513505317617664 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 257419546556638600513505317617664 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: r1-t = 0xc0016645_67197daf: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44112964367993508489762902638592) 20282409603651670423947251286016
  (- 1) 4897522872581551 1 2251799813685248))
  (or (and (jdouble.near (- 44112964367993508489762902638592) 20282409603651670423947251286016 S2 M2
    1 2251799813685248) (not (and (= S2 (- 1)) (= M2 4897522872581551))))
  (jdouble.near (- 44112964367993508489762902638592) 20282409603651670423947251286016 S2 M2 1
    4503599627370496)
  (jdouble.near (- 44112964367993508489762902638592) 20282409603651670423947251286016 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: x*t = 0xbff19832_794bd7ae: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44607408106614733148468081500820) 40564819207303340847894502572032
  (- 1) 4952417154881454 1 4503599627370496))
  (or (and (jdouble.near (- 44607408106614733148468081500820) 40564819207303340847894502572032 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 4952417154881454))))
  (jdouble.near (- 44607408106614733148468081500820) 40564819207303340847894502572032 S2 M2 1
    9007199254740992)
  (jdouble.near (- 44607408106614733148468081500820) 40564819207303340847894502572032 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: 6.0-x*t = 0x401c660c_9e52f5ec: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 35999540418804347688379031224320 5070602400912917605986812821504 1
  7993503729776108 1 1125899906842624))
  (or (and (jdouble.near 35999540418804347688379031224320 5070602400912917605986812821504 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 7993503729776108))))
  (jdouble.near 35999540418804347688379031224320 5070602400912917605986812821504 S2 M2 1
    2251799813685248)
  (jdouble.near 35999540418804347688379031224320 5070602400912917605986812821504 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: (r1-t)/(6.0-x*t) = 0xbfd39b23_9e2834bb: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 5514120545999188561220362829824) 17999770209402174970089422454784
  (- 1) 5518601837098171 1 18014398509481984))
  (or (and (jdouble.near (- 5514120545999188561220362829824) 17999770209402174970089422454784 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5518601837098171))))
  (jdouble.near (- 5514120545999188561220362829824) 17999770209402174970089422454784 S2 M2 1
    36028797018963968)
  (jdouble.near (- 5514120545999188561220362829824) 17999770209402174970089422454784 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: e = hxs*(...) = 0xbf92d6eb_c7f7c3e1: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 47763896295920823637400101627484)
  2596148429267413814265248164610048 (- 1) 5302857741353953 1 288230376151711744))
  (or (and (jdouble.near (- 47763896295920823637400101627484) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 5302857741353953))))
  (jdouble.near (- 47763896295920823637400101627484) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 47763896295920823637400101627484) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: e-c = 0xbf92d6eb_c7f7c3da: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 1720875724480574184480355224147607736570246856704)
  93536104789177786765035829293842113257979682750464 (- 1) 5302857741353946 1 288230376151711744))
  (or (and (jdouble.near (- 1720875724480574184480355224147607736570246856704)
    93536104789177786765035829293842113257979682750464 S2 M2 1 288230376151711744) (not (and (= S2
    (- 1)) (= M2 5302857741353946))))
  (jdouble.near (- 1720875724480574184480355224147607736570246856704)
    93536104789177786765035829293842113257979682750464 S2 M2 1 576460752303423488)
  (jdouble.near (- 1720875724480574184480355224147607736570246856704)
    93536104789177786765035829293842113257979682750464 S2 M2 1 144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: x*(e-c) = 0x3f7a1dfc_fa6674d8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33107409959101485116852404883988 5192296858534827628530496329220096 1
  7351321764459736 1 1152921504606846976))
  (or (and (jdouble.near 33107409959101485116852404883988 5192296858534827628530496329220096 S2 M2 1
    1152921504606846976) (not (and (= S2 1) (= M2 7351321764459736))))
  (jdouble.near 33107409959101485116852404883988 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near 33107409959101485116852404883988 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: x*(e-c)-c = 0x3f7a1dfc_fa6674f3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2385640306480195756969234325138343957825136361472
  374144419156711147060143317175368453031918731001856 1 7351321764459763 1 1152921504606846976))
  (or (and (jdouble.near 2385640306480195756969234325138343957825136361472
    374144419156711147060143317175368453031918731001856 S2 M2 1 1152921504606846976) (not (and (= S2
    1) (= M2 7351321764459763))))
  (jdouble.near 2385640306480195756969234325138343957825136361472
    374144419156711147060143317175368453031918731001856 S2 M2 1 2305843009213693952)
  (jdouble.near 2385640306480195756969234325138343957825136361472
    374144419156711147060143317175368453031918731001856 S2 M2 1 576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: e -= hxs = 0xbfab7bfe_5d602cb6: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 8919181525833878197340757313978368)
  166153499473114484112975882535043072 (- 1) 7736156789681334 1 144115188075855872))
  (or (and (jdouble.near (- 8919181525833878197340757313978368) 166153499473114484112975882535043072
    S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2 7736156789681334))))
  (jdouble.near (- 8919181525833878197340757313978368) 166153499473114484112975882535043072 S2 M2 1
    288230376151711744)
  (jdouble.near (- 8919181525833878197340757313978368) 166153499473114484112975882535043072 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: x+0.5 = 0x3fc3a37a_0417181c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 24894858442197967263503155724288 162259276829213363391578010288128 1
  5527769007462428 1 36028797018963968))
  (or (and (jdouble.near 24894858442197967263503155724288 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 5527769007462428))))
  (jdouble.near 24894858442197967263503155724288 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 24894858442197967263503155724288 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: e-(x+0.5) = 0xbfca8279_9b6f234a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 1075359892832643659609798531350528)
  5192296858534827628530496329220096 (- 1) 7461808204882762 1 36028797018963968))
  (or (and (jdouble.near (- 1075359892832643659609798531350528) 5192296858534827628530496329220096
    S2 M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 7461808204882762))))
  (jdouble.near (- 1075359892832643659609798531350528) 5192296858534827628530496329220096 S2 M2 1
    72057594037927936)
  (jdouble.near (- 1075359892832643659609798531350528) 5192296858534827628530496329220096 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffec: -2.0*(e-(x+0.5)) = 0x3fda8279_9b6f234a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33604996651020116614606017789952 81129638414606681695789005144064 1
  7461808204882762 1 18014398509481984))
  (or (and (jdouble.near 33604996651020116614606017789952 81129638414606681695789005144064 S2 M2 1
    18014398509481984) (not (and (= S2 1) (= M2 7461808204882762))))
  (jdouble.near 33604996651020116614606017789952 81129638414606681695789005144064 S2 M2 1
    36028797018963968)
  (jdouble.near 33604996651020116614606017789952 81129638414606681695789005144064 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "at 0x3fd62e42_ffffffec: k = 0 gives 0x3fda8279_9b6f2349, k = 1 another: expect unsat")
(push 1)
(assert (= 4601133428122788681 4601133428122788682))
(check-sat)
(pop 1)

; The witness 0xbfd62e42_fffffff3.

(echo "at 0xbfd62e42_fffffff3: hx is 0x3fd62e42, not above it: unreduced: expect unsat")
(push 1)
(assert (not (= (c.hx (- 4623457102151548941)) 1071001154)))
(check-sat)
(pop 1)

(echo "at 0xbfd62e42_fffffff3: x*invln2 rounds into 2^-1 only, with x's sign: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (jdouble.near (* (- 6497320848556798) 6243314785320947) 81129638414606681695789005144064
  S2 M2 1 18014398509481984)
  (jdouble.near (* (- 6497320848556798) 6243314785320947) 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near (* (- 6497320848556798) 6243314785320947) 81129638414606681695789005144064 1 M2 1
    9007199254740992)))
(check-sat)
(pop 1)

(echo "at 0xbfd62e42_fffffff3: the sum with h rounds into 2^0, x's sign: k = -1: expect unsat")
(push 1)
(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 6243314785320947) 81129638414606681695789005144064 (-
  1) P 1 9007199254740992))
(assert (or (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 1 M2 1 4503599627370496)))
(check-sat)
(pop 1)

(echo "not vacuous: at 0xbfd62e42_fffffff3 the sum rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 6243314785320947) 81129638414606681695789005144064 (-
  1) P 1 9007199254740992))
(assert (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 (- 1) M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: hfx = 0.5*x = 0xbfc62e42_fffffff3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 28117390140728125139189038579712)
  162259276829213363391578010288128 (- 1) 6243314785320947 1 36028797018963968))
  (or (and (jdouble.near (- 28117390140728125139189038579712) 162259276829213363391578010288128 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6243314785320947))))
  (jdouble.near (- 28117390140728125139189038579712) 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near (- 28117390140728125139189038579712) 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: hxs = x*hfx = 0x3faebfbe_02588fdc: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979508607142525705796976809 649037107316853453566312041152512 1
  8655072105369564 1 144115188075855872))
  (or (and (jdouble.near 38978979508607142525705796976809 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072105369564))))
  (jdouble.near 38978979508607142525705796976809 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979508607142525705796976809 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: hxs*Q5 = 0xbe49ef96_9a6d9d20: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755297546985841856828858817964)
  5444517870735015415413993718908291383296 (- 1) 7300304532774176 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755297546985841856828858817964)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304532774176))))
  (jdouble.near (- 65755297546985841856828858817964) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755297546985841856828858817964) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: Q4+hxs*Q5 = 0x3ed0c2d2_bb991b6a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144599285463205619085864190607360
  713623846352979940529142984724747568191373312 1 4717809973664618 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144599285463205619085864190607360
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973664618))))
  (jdouble.near 2851741144599285463205619085864190607360
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144599285463205619085864190607360
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: hxs*(Q4+...) = 0x3e901b13_efad0db5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985501498952602388280886552
  170141183460469231731687303715884105728 1 4533372066794933 1 18889465931478580854784))
  (or (and (jdouble.near 40832985501498952602388280886552 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372066794933))))
  (jdouble.near 40832985501498952602388280886552 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985501498952602388280886552 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: Q3+hxs*(...) = 0xbf14bdfe_8abb2ea9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266981669045041479547258197245952)
  1393796574908163946345982392040522594123776 (- 1) 5838400481078953 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266981669045041479547258197245952)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481078953))))
  (jdouble.near (- 110284266981669045041479547258197245952)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266981669045041479547258197245952)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: hxs*(Q3+...) = 0xbed3ee67_d2f82859: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531777143762689048295527186492)
  10633823966279326983230456482242756608 (- 1) 5610154246023257 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531777143762689048295527186492) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154246023257))))
  (jdouble.near (- 50531777143762689048295527186492) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531777143762689048295527186492) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: Q2+hxs*(...) = 0x3f59edb1_b22b5d5d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588157286979284679309150650368
  5444517870735015415413993718908291383296 1 7298221871947101 1 4611686018427387904))
  (or (and (jdouble.near 8616219588157286979284679309150650368
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221871947101))))
  (jdouble.near 8616219588157286979284679309150650368 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588157286979284679309150650368 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: hxs*(Q2+...) = 0x3f18ea33_48ddc992: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166636542687395968601863433964 664613997892457936451903530140172288
  1 7012905427782034 1 73786976294838206464))
  (or (and (jdouble.near 63166636542687395968601863433964 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905427782034))))
  (jdouble.near 63166636542687395968601863433964 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166636542687395968601863433964 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: Q1+hxs*(...) = 0xbfa1049b_f76ca20f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132691292423056827248229220352)
  10633823966279326983230456482242756608 (- 1) 4790142521614863 1 144115188075855872))
  (or (and (jdouble.near (- 353450132691292423056827248229220352)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521614863))))
  (jdouble.near (- 353450132691292423056827248229220352) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132691292423056827248229220352) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: hxs*(Q1+...) = 0xbf605a4a_c7801858: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028919573424535554690229732)
  20769187434139310514121985316880384 (- 1) 4602876848511064 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028919573424535554690229732) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876848511064))))
  (jdouble.near (- 41459028919573424535554690229732) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028919573424535554690229732) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: r1 = one+hxs*(...) = 0x3fefefa5_b5387fe8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202609868546264353575272448
  10384593717069655257060992658440192 1 8989219267051496 1 9007199254740992))
  (or (and (jdouble.near 10363864202609868546264353575272448 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267051496))))
  (jdouble.near 10363864202609868546264353575272448 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202609868546264353575272448 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: r1*hfx = 0xbfc622ed_488e4c89: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56122525558474531289270736486712)
  324518553658426726783156020576256 (- 1) 6230852007513225 1 36028797018963968))
  (or (and (jdouble.near (- 56122525558474531289270736486712) 324518553658426726783156020576256 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6230852007513225))))
  (jdouble.near (- 56122525558474531289270736486712) 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near (- 56122525558474531289270736486712) 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: t = 3.0-r1*hfx = 0x4009622e_d488e4c9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 257419546633438678613838362836992 81129638414606681695789005144064 1
  7144827691525321 1 2251799813685248))
  (or (and (jdouble.near 257419546633438678613838362836992 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 7144827691525321))))
  (jdouble.near 257419546633438678613838362836992 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 257419546633438678613838362836992 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: r1-t = 0xc0016645_673ac4cf: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016
  (- 1) 4897522874762447 1 2251799813685248))
  (or (and (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016 S2 M2
    1 2251799813685248) (not (and (= S2 (- 1)) (= M2 4897522874762447))))
  (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016 S2 M2 1
    4503599627370496)
  (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: x*t = 0xbff19832_7b01af07: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44607408365070566814282962198987) 40564819207303340847894502572032
  (- 1) 4952417183575815 1 4503599627370496))
  (or (and (jdouble.near (- 44607408365070566814282962198987) 40564819207303340847894502572032 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 4952417183575815))))
  (jdouble.near (- 44607408365070566814282962198987) 40564819207303340847894502572032 S2 M2 1
    9007199254740992)
  (jdouble.near (- 44607408365070566814282962198987) 40564819207303340847894502572032 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: 6.0-x*t = 0x401c660c_9ec06bc2: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 35999540451111326065187654467584 5070602400912917605986812821504 1
  7993503736949698 1 1125899906842624))
  (or (and (jdouble.near 35999540451111326065187654467584 5070602400912917605986812821504 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 7993503736949698))))
  (jdouble.near 35999540451111326065187654467584 5070602400912917605986812821504 S2 M2 1
    2251799813685248)
  (jdouble.near 35999540451111326065187654467584 5070602400912917605986812821504 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: (r1-t)/(6.0-x*t) = 0xbfd39b23_9e022254: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 5514120548454659164453814140928) 17999770225555663595543780655104
  (- 1) 5518601834603092 1 18014398509481984))
  (or (and (jdouble.near (- 5514120548454659164453814140928) 17999770225555663595543780655104 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5518601834603092))))
  (jdouble.near (- 5514120548454659164453814140928) 17999770225555663595543780655104 S2 M2 1
    36028797018963968)
  (jdouble.near (- 5514120548454659164453814140928) 17999770225555663595543780655104 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: e = hxs*(...) = 0xbf92d6eb_cb4c8c18: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 47763896799314521884351917091888)
  2596148429267413814265248164610048 (- 1) 5302857797241880 1 288230376151711744))
  (or (and (jdouble.near (- 47763896799314521884351917091888) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 5302857797241880))))
  (jdouble.near (- 47763896799314521884351917091888) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 47763896799314521884351917091888) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: x*e = 0x3f7a1dfd_016d1ff5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33107410489974697926647189660360 5192296858534827628530496329220096 1
  7351321882337269 1 1152921504606846976))
  (or (and (jdouble.near 33107410489974697926647189660360 5192296858534827628530496329220096 S2 M2 1
    1152921504606846976) (not (and (= S2 1) (= M2 7351321882337269))))
  (jdouble.near 33107410489974697926647189660360 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near 33107410489974697926647189660360 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: x*e-hxs = 0xbfab7bfe_622aebdd: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 8919181618524238203830782337744896)
  166153499473114484112975882535043072 (- 1) 7736156870077405 1 144115188075855872))
  (or (and (jdouble.near (- 8919181618524238203830782337744896) 166153499473114484112975882535043072
    S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2 7736156870077405))))
  (jdouble.near (- 8919181618524238203830782337744896) 166153499473114484112975882535043072 S2 M2 1
    288230376151711744)
  (jdouble.near (- 8919181618524238203830782337744896) 166153499473114484112975882535043072 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_fffffff3: x-(x*e-hxs) = 0xbfd2bec3_33baa277: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 760394271713858789274592701579264)
  2596148429267413814265248164610048 (- 1) 5276295176561271 1 18014398509481984))
  (or (and (jdouble.near (- 760394271713858789274592701579264) 2596148429267413814265248164610048 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5276295176561271))))
  (jdouble.near (- 760394271713858789274592701579264) 2596148429267413814265248164610048 S2 M2 1
    36028797018963968)
  (jdouble.near (- 760394271713858789274592701579264) 2596148429267413814265248164610048 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: t*ln2_hi = 0xbfe62e42_fee00000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032
  (- 1) 6243314766446592 1 9007199254740992))
  (or (and (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032 S2 M2
    1 9007199254740992) (not (and (= S2 (- 1)) (= M2 6243314766446592))))
  (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hi = x-t*ln2_hi = 0x3fd62e42_fdc0000d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 56234779941446097698944245039104 162259276829213363391578010288128 1
  6243314747572237 1 18014398509481984))
  (or (and (jdouble.near 56234779941446097698944245039104 162259276829213363391578010288128 S2 M2 1
    18014398509481984) (not (and (= S2 1) (= M2 6243314747572237))))
  (jdouble.near 56234779941446097698944245039104 162259276829213363391578010288128 S2 M2 1
    36028797018963968)
  (jdouble.near 56234779941446097698944245039104 162259276829213363391578010288128 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: lo = t*ln2_lo = 0xbdea39ef_35793c76: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 33245792907574618198748633759744)
  174224571863520493293247799005065324265472 (- 1) 7382048951581814 1 38685626227668133590597632))
  (or (and (jdouble.near (- 33245792907574618198748633759744)
    174224571863520493293247799005065324265472 S2 M2 1 38685626227668133590597632) (not (and (= S2
    (- 1)) (= M2 7382048951581814))))
  (jdouble.near (- 33245792907574618198748633759744) 174224571863520493293247799005065324265472 S2
    M2 1 77371252455336267181195264)
  (jdouble.near (- 33245792907574618198748633759744) 174224571863520493293247799005065324265472 S2
    M2 1 19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: x = hi-lo = 0x3fd62e42_fdf473eb: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 241526540879250956194084858965356456181760
  696898287454081973172991196020261297061888 1 6243314751009771 1 18014398509481984))
  (or (and (jdouble.near 241526540879250956194084858965356456181760
    696898287454081973172991196020261297061888 S2 M2 1 18014398509481984) (not (and (= S2 1) (= M2
    6243314751009771))))
  (jdouble.near 241526540879250956194084858965356456181760
    696898287454081973172991196020261297061888 S2 M2 1 36028797018963968)
  (jdouble.near 241526540879250956194084858965356456181760
    696898287454081973172991196020261297061888 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hi-x = 0xbdea39ef_00000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 (- 1)
  7382048054444032 1 38685626227668133590597632))
  (or (and (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 S2 M2 1
    38685626227668133590597632) (not (and (= S2 (- 1)) (= M2 7382048054444032))))
  (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 S2 M2 1
    77371252455336267181195264)
  (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 S2 M2 1
    19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: c = (hi-x)-lo = 0x3c7abc9e_3b000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 34706336909171216401548455626932224
  1496577676626844588240573268701473812127674924007424 1 7525737175187456 1
  324518553658426726783156020576256))
  (or (and (jdouble.near 34706336909171216401548455626932224
    1496577676626844588240573268701473812127674924007424 S2 M2 1 324518553658426726783156020576256)
    (not (and (= S2 1) (= M2 7525737175187456))))
  (jdouble.near 34706336909171216401548455626932224
    1496577676626844588240573268701473812127674924007424 S2 M2 1 649037107316853453566312041152512)
  (jdouble.near 34706336909171216401548455626932224
    1496577676626844588240573268701473812127674924007424 S2 M2 1
    162259276829213363391578010288128))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hfx = 0.5*x = 0x3fc62e42_fdf473eb: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 28117389986204325690945533116416 162259276829213363391578010288128 1
  6243314751009771 1 36028797018963968))
  (or (and (jdouble.near 28117389986204325690945533116416 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6243314751009771))))
  (jdouble.near 28117389986204325690945533116416 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 28117389986204325690945533116416 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hxs = x*hfx = 0x3faebfbd_fcacfb40: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979080176198857864137472441 649037107316853453566312041152512 1
  8655072010238784 1 144115188075855872))
  (or (and (jdouble.near 38978979080176198857864137472441 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072010238784))))
  (jdouble.near 38978979080176198857864137472441 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979080176198857864137472441 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hxs*Q5 = 0xbe49ef96_95a53f51: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755296824247467338139427269184)
  5444517870735015415413993718908291383296 (- 1) 7300304452534097 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755296824247467338139427269184)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304452534097))))
  (jdouble.near (- 65755296824247467338139427269184) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755296824247467338139427269184) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: Q4+hxs*Q5 = 0x3ed0c2d2_bb9b7f99: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144694016228118722205526425337856
  713623846352979940529142984724747568191373312 1 4717809973821337 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144694016228118722205526425337856
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973821337))))
  (jdouble.near 2851741144694016228118722205526425337856
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144694016228118722205526425337856
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hxs*(Q4+...) = 0x3e901b13_ecb70a1b: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985054046424146266324134208
  170141183460469231731687303715884105728 1 4533372017117723 1 18889465931478580854784))
  (or (and (jdouble.near 40832985054046424146266324134208 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372017117723))))
  (jdouble.near 40832985054046424146266324134208 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985054046424146266324134208 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: Q3+hxs*(...) = 0xbf14bdfe_8abe24ad: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266985334576158143246756732731392)
  1393796574908163946345982392040522594123776 (- 1) 5838400481273005 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266985334576158143246756732731392)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481273005))))
  (jdouble.near (- 110284266985334576158143246756732731392)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266985334576158143246756732731392)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hxs*(Q3+...) = 0xbed3ee67_cf4e1959: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531776590030631364610343225920)
  10633823966279326983230456482242756608 (- 1) 5610154184546649 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531776590030631364610343225920) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154184546649))))
  (jdouble.near (- 50531776590030631364610343225920) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531776590030631364610343225920) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: Q2+hxs*(...) = 0x3f59edb1_b22f076c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588440797792858620611788800000
  5444517870735015415413993718908291383296 1 7298221872187244 1 4611686018427387904))
  (or (and (jdouble.near 8616219588440797792858620611788800000
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221872187244))))
  (jdouble.near 8616219588440797792858620611788800000 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588440797792858620611788800000 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hxs*(Q2+...) = 0x3f18ea33_4449249a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166635850480311634968798871296 664613997892457936451903530140172288
  1 7012905350931610 1 73786976294838206464))
  (or (and (jdouble.near 63166635850480311634968798871296 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905350931610))))
  (jdouble.near 63166635850480311634968798871296 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166635850480311634968798871296 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: Q1+hxs*(...) = 0xbfa1049b_f76eec62: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132702367736365296516155310080)
  10633823966279326983230456482242756608 (- 1) 4790142521764962 1 144115188075855872))
  (or (and (jdouble.near (- 353450132702367736365296516155310080)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521764962))))
  (jdouble.near (- 353450132702367736365296516155310080) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132702367736365296516155310080) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: hxs*(Q1+...) = 0xbf605a4a_c47e53b7: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028465182547796830744686208)
  20769187434139310514121985316880384 (- 1) 4602876798063543 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028465182547796830744686208) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876798063543))))
  (jdouble.near (- 41459028465182547796830744686208) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028465182547796830744686208) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: r1 = one+hxs*(...) = 0x3fefefa5_b53b81ac: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202837063983041718847012864
  10384593717069655257060992658440192 1 8989219267248556 1 9007199254740992))
  (or (and (jdouble.near 10363864202837063983041718847012864 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267248556))))
  (jdouble.near 10363864202837063983041718847012864 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202837063983041718847012864 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: r1*hfx = 0x3fc622ed_4685e19c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 56122525251274154519709841640676 324518553658426726783156020576256 1
  6230851973407132 1 36028797018963968))
  (or (and (jdouble.near 56122525251274154519709841640676 324518553658426726783156020576256 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6230851973407132))))
  (jdouble.near 56122525251274154519709841640676 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near 56122525251274154519709841640676 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: t = 3.0-r1*hfx = 0x40069dd1_2b97a1e6: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 229358283931001505423827409043456 81129638414606681695789005144064 1
  6365971192717798 1 2251799813685248))
  (or (and (jdouble.near 229358283931001505423827409043456 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 6365971192717798))))
  (jdouble.near 229358283931001505423827409043456 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 229358283931001505423827409043456 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: r1-t = 0xbffd43cf_7c9182f6: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 37097648711584234154393472073728) 20282409603651670423947251286016
  (- 1) 8237332751811318 1 4503599627370496))
  (or (and (jdouble.near (- 37097648711584234154393472073728) 20282409603651670423947251286016 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 8237332751811318))))
  (jdouble.near (- 37097648711584234154393472073728) 20282409603651670423947251286016 S2 M2 1
    9007199254740992)
  (jdouble.near (- 37097648711584234154393472073728) 20282409603651670423947251286016 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: x*t = 0x3fef5a64_0745ac71: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 39744761851998293938162343604258 40564819207303340847894502572032 1
  8825109943266417 1 9007199254740992))
  (or (and (jdouble.near 39744761851998293938162343604258 40564819207303340847894502572032 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 8825109943266417))))
  (jdouble.near 39744761851998293938162343604258 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near 39744761851998293938162343604258 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: 6.0-x*t = 0x401414b3_7f174a72: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 50911038347955437597750030499840 10141204801825835211973625643008 1
  5652260698147442 1 1125899906842624))
  (or (and (jdouble.near 50911038347955437597750030499840 10141204801825835211973625643008 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 5652260698147442))))
  (jdouble.near 50911038347955437597750030499840 10141204801825835211973625643008 S2 M2 1
    2251799813685248)
  (jdouble.near 50911038347955437597750030499840 10141204801825835211973625643008 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: (r1-t)/(6.0-x*t) = 0xbfd75150_3452c34f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9274412177896058538598368018432) 25455519173977719361824968671232
  (- 1) 6563329381417807 1 18014398509481984))
  (or (and (jdouble.near (- 9274412177896058538598368018432) 25455519173977719361824968671232 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6563329381417807))))
  (jdouble.near (- 9274412177896058538598368018432) 25455519173977719361824968671232 S2 M2 1
    36028797018963968)
  (jdouble.near (- 9274412177896058538598368018432) 25455519173977719361824968671232 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: e = hxs*(...) = 0xbf9667f2_f825fde0: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56806088423087093524294539626688)
  2596148429267413814265248164610048 (- 1) 6306742730620384 1 288230376151711744))
  (or (and (jdouble.near (- 56806088423087093524294539626688) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 6306742730620384))))
  (jdouble.near (- 56806088423087093524294539626688) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 56806088423087093524294539626688) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: e-c = 0xbf9667f2_f825fde7: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 2046655029236725949916251748894699500066536685568)
  93536104789177786765035829293842113257979682750464 (- 1) 6306742730620391 1 288230376151711744))
  (or (and (jdouble.near (- 2046655029236725949916251748894699500066536685568)
    93536104789177786765035829293842113257979682750464 S2 M2 1 288230376151711744) (not (and (= S2
    (- 1)) (= M2 6306742730620391))))
  (jdouble.near (- 2046655029236725949916251748894699500066536685568)
    93536104789177786765035829293842113257979682750464 S2 M2 1 576460752303423488)
  (jdouble.near (- 2046655029236725949916251748894699500066536685568)
    93536104789177786765035829293842113257979682750464 S2 M2 1 144115188075855872))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: x*(e-c) = 0xbf7f0fb6_c029ef76: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 39374979920905929694908532840461)
  5192296858534827628530496329220096 (- 1) 8743001860468598 1 1152921504606846976))
  (or (and (jdouble.near (- 39374979920905929694908532840461) 5192296858534827628530496329220096 S2
    M2 1 1152921504606846976) (not (and (= S2 (- 1)) (= M2 8743001860468598))))
  (jdouble.near (- 39374979920905929694908532840461) 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near (- 39374979920905929694908532840461) 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: x*(e-c)-c = 0xbf7f0fb6_c029ef91: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 2837266318392212098730303644689638211880059142144)
  374144419156711147060143317175368453031918731001856 (- 1) 8743001860468625 1 1152921504606846976))
  (or (and (jdouble.near (- 2837266318392212098730303644689638211880059142144)
    374144419156711147060143317175368453031918731001856 S2 M2 1 1152921504606846976) (not (and (= S2
    (- 1)) (= M2 8743001860468625))))
  (jdouble.near (- 2837266318392212098730303644689638211880059142144)
    374144419156711147060143317175368453031918731001856 S2 M2 1 2305843009213693952)
  (jdouble.near (- 2837266318392212098730303644689638211880059142144)
    374144419156711147060143317175368453031918731001856 S2 M2 1 576460752303423488))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: e -= hxs = 0xbfb150da_6a591c99: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 11238618001994100214886752986333184)
  166153499473114484112975882535043072 (- 1) 4873973621398681 1 72057594037927936))
  (or (and (jdouble.near (- 11238618001994100214886752986333184)
    166153499473114484112975882535043072 S2 M2 1 72057594037927936) (not (and (= S2 (- 1)) (= M2
    4873973621398681))))
  (jdouble.near (- 11238618001994100214886752986333184) 166153499473114484112975882535043072 S2 M2 1
    144115188075855872)
  (jdouble.near (- 11238618001994100214886752986333184) 166153499473114484112975882535043072 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: x-e = 0x3fda8279_988abb11: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 537679942919848117858031380725760 1298074214633706907132624082305024
  1 7461808156359441 1 18014398509481984))
  (or (and (jdouble.near 537679942919848117858031380725760 1298074214633706907132624082305024 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 7461808156359441))))
  (jdouble.near 537679942919848117858031380725760 1298074214633706907132624082305024 S2 M2 1
    36028797018963968)
  (jdouble.near 537679942919848117858031380725760 1298074214633706907132624082305024 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: 0.5*(x-e) = 0x3fca8279_988abb11: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33604996432490506240227054452736 162259276829213363391578010288128 1
  7461808156359441 1 36028797018963968))
  (or (and (jdouble.near 33604996432490506240227054452736 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 7461808156359441))))
  (jdouble.near 33604996432490506240227054452736 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 33604996432490506240227054452736 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_fffffff3: 0.5*(x-e)-0.5 = 0xbfd2bec3_33baa278: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 95049283964232350911123901382656)
  324518553658426726783156020576256 (- 1) 5276295176561272 1 18014398509481984))
  (or (and (jdouble.near (- 95049283964232350911123901382656) 324518553658426726783156020576256 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5276295176561272))))
  (jdouble.near (- 95049283964232350911123901382656) 324518553658426726783156020576256 S2 M2 1
    36028797018963968)
  (jdouble.near (- 95049283964232350911123901382656) 324518553658426726783156020576256 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "at 0xbfd62e42_fffffff3: k = 0 gives 0xbfd2bec3_33baa277, k = -1 another: expect unsat")
(push 1)
(assert (= (- 4624424121760308617) (- 4624424121760308616)))
(check-sat)
(pop 1)

; The control 0x3fd62e42_ffffffff, the word's top double.

(echo "at 0x3fd62e42_ffffffff: hx is 0x3fd62e42, not above it: unreduced: expect unsat")
(push 1)
(assert (not (= (c.hx 4599914934703226879) 1071001154)))
(check-sat)
(pop 1)

(echo "at 0x3fd62e42_ffffffff: x*invln2 rounds into 2^-1 only, with x's sign: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 S2
  M2 1 18014398509481984)
  (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 (- 1) M2 1
    9007199254740992)))
(check-sat)
(pop 1)

(echo "at 0x3fd62e42_ffffffff: the sum with h rounds into 2^0, x's sign: k = 1: expect unsat")
(push 1)
(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (or (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 (- 1) M2 1 4503599627370496)))
(check-sat)
(pop 1)

(echo "not vacuous: at 0x3fd62e42_ffffffff the sum rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: hfx = 0.5*x = 0x3fc62e42_ffffffff: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 28117390140728179182384567025664 162259276829213363391578010288128 1
  6243314785320959 1 36028797018963968))
  (or (and (jdouble.near 28117390140728179182384567025664 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6243314785320959))))
  (jdouble.near 28117390140728179182384567025664 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 28117390140728179182384567025664 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: hxs = x*hfx = 0x3faebfbe_02588ffd: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 1
  8655072105369597 1 144115188075855872))
  (or (and (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072105369597))))
  (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: hxs*Q5 = 0xbe49ef96_9a6d9d3c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755297546986092568179686770297)
  5444517870735015415413993718908291383296 (- 1) 7300304532774204 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755297546986092568179686770297)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304532774204))))
  (jdouble.near (- 65755297546986092568179686770297) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755297546986092568179686770297) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: Q4+hxs*Q5 = 0x3ed0c2d2_bb991b6a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144599285430149053705776674111488
  713623846352979940529142984724747568191373312 1 4717809973664618 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144599285430149053705776674111488
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973664618))))
  (jdouble.near 2851741144599285430149053705776674111488
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144599285430149053705776674111488
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: hxs*(Q4+...) = 0x3e901b13_efad0dc6: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985501499108290117411818946
  170141183460469231731687303715884105728 1 4533372066794950 1 18889465931478580854784))
  (or (and (jdouble.near 40832985501499108290117411818946 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372066794950))))
  (jdouble.near 40832985501499108290117411818946 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985501499108290117411818946 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: Q3+hxs*(...) = 0xbf14bdfe_8abb2ea9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266981669043787100950245947736064)
  1393796574908163946345982392040522594123776 (- 1) 5838400481078953 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266981669043787100950245947736064)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481078953))))
  (jdouble.near (- 110284266981669043787100950245947736064)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266981669043787100950245947736064)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: hxs*(Q3+...) = 0xbed3ee67_d2f8286e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531777143762881715511402791941)
  10633823966279326983230456482242756608 (- 1) 5610154246023278 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531777143762881715511402791941) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154246023278))))
  (jdouble.near (- 50531777143762881715511402791941) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531777143762881715511402791941) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: Q2+hxs*(...) = 0x3f59edb1_b22b5d5d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588157286882439272922175504384
  5444517870735015415413993718908291383296 1 7298221871947101 1 4611686018427387904))
  (or (and (jdouble.near 8616219588157286882439272922175504384
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221871947101))))
  (jdouble.near 8616219588157286882439272922175504384 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588157286882439272922175504384 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: hxs*(Q2+...) = 0x3f18ea33_48ddc9ad: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288
  1 7012905427782061 1 73786976294838206464))
  (or (and (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905427782061))))
  (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: Q1+hxs*(...) = 0xbfa1049b_f76ca20f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132691292419165717170181111808)
  10633823966279326983230456482242756608 (- 1) 4790142521614863 1 144115188075855872))
  (or (and (jdouble.near (- 353450132691292419165717170181111808)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521614863))))
  (jdouble.near (- 353450132691292419165717170181111808) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132691292419165717170181111808) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: hxs*(Q1+...) = 0xbf605a4a_c780186a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028919573582610257903520211)
  20769187434139310514121985316880384 (- 1) 4602876848511082 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028919573582610257903520211) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876848511082))))
  (jdouble.near (- 41459028919573582610257903520211) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028919573582610257903520211) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: r1 = one+hxs*(...) = 0x3fefefa5_b5387fe8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202609868465199560282603520
  10384593717069655257060992658440192 1 8989219267051496 1 9007199254740992))
  (or (and (jdouble.near 10363864202609868465199560282603520 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267051496))))
  (jdouble.near 10363864202609868465199560282603520 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202609868465199560282603520 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: r1*hfx = 0x3fc622ed_488e4c95: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 56122525558474639159901941104664 324518553658426726783156020576256 1
  6230852007513237 1 36028797018963968))
  (or (and (jdouble.near 56122525558474639159901941104664 324518553658426726783156020576256 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6230852007513237))))
  (jdouble.near 56122525558474639159901941104664 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near 56122525558474639159901941104664 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: t = 3.0-r1*hfx = 0x40069dd1_2b771b37: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 229358283854201384539297903804416 81129638414606681695789005144064 1
  6365971190586167 1 2251799813685248))
  (or (and (jdouble.near 229358283854201384539297903804416 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 6365971190586167))))
  (jdouble.near 229358283854201384539297903804416 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 229358283854201384539297903804416 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: r1-t = 0xbffd43cf_7c51f67a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 37097648692827948671095491526656) 20282409603651670423947251286016
  (- 1) 8237332747646586 1 4503599627370496))
  (or (and (jdouble.near (- 37097648692827948671095491526656) 20282409603651670423947251286016 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 8237332747646586))))
  (jdouble.near (- 37097648692827948671095491526656) 20282409603651670423947251286016 S2 M2 1
    9007199254740992)
  (jdouble.near (- 37097648692827948671095491526656) 20282409603651670423947251286016 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: x*t = 0x3fef5a64_09fca1db: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 39744762057113884994938440574153 40564819207303340847894502572032 1
  8825109988811227 1 9007199254740992))
  (or (and (jdouble.near 39744762057113884994938440574153 40564819207303340847894502572032 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 8825109988811227))))
  (jdouble.near 39744762057113884994938440574153 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near 39744762057113884994938440574153 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: 6.0-x*t = 0x401414b3_7ec06bc5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 50911038296676540261585020518400 10141204801825835211973625643008 1
  5652260692454341 1 1125899906842624))
  (or (and (jdouble.near 50911038296676540261585020518400 10141204801825835211973625643008 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 5652260692454341))))
  (jdouble.near 50911038296676540261585020518400 10141204801825835211973625643008 S2 M2 1
    2251799813685248)
  (jdouble.near 50911038296676540261585020518400 10141204801825835211973625643008 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: (r1-t)/(6.0-x*t) = 0xbfd75150_3485002f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9274412173206987167773872881664) 25455519148338271819642370523136
  (- 1) 6563329384710191 1 18014398509481984))
  (or (and (jdouble.near (- 9274412173206987167773872881664) 25455519148338271819642370523136 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6563329384710191))))
  (jdouble.near (- 9274412173206987167773872881664) 25455519148338271819642370523136 S2 M2 1
    36028797018963968)
  (jdouble.near (- 9274412173206987167773872881664) 25455519148338271819642370523136 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: e = hxs*(...) = 0xbf9667f2_fc77ff66: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56806089075957774483922787463027)
  2596148429267413814265248164610048 (- 1) 6306742803103590 1 288230376151711744))
  (or (and (jdouble.near (- 56806089075957774483922787463027) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 6306742803103590))))
  (jdouble.near (- 56806089075957774483922787463027) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 56806089075957774483922787463027) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: x*e = 0xbf7f0fb6_c9045960: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 39374980589833193196919475142810)
  5192296858534827628530496329220096 (- 1) 8743002009000288 1 1152921504606846976))
  (or (and (jdouble.near (- 39374980589833193196919475142810) 5192296858534827628530496329220096 S2
    M2 1 1152921504606846976) (not (and (= S2 (- 1)) (= M2 8743002009000288))))
  (jdouble.near (- 39374980589833193196919475142810) 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near (- 39374980589833193196919475142810) 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: x*e-hxs = 0xbfb150da_6dbc8d94: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 11238618133078128821783993996279808)
  166153499473114484112975882535043072 (- 1) 4873973678247316 1 72057594037927936))
  (or (and (jdouble.near (- 11238618133078128821783993996279808)
    166153499473114484112975882535043072 S2 M2 1 72057594037927936) (not (and (= S2 (- 1)) (= M2
    4873973678247316))))
  (jdouble.near (- 11238618133078128821783993996279808) 166153499473114484112975882535043072 S2 M2 1
    144115188075855872)
  (jdouble.near (- 11238618133078128821783993996279808) 166153499473114484112975882535043072 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = 0 at 0x3fd62e42_ffffffff: x-(x*e-hxs) = 0x3fda8279_9b6f2364: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 537679946416323739331141270765568 1298074214633706907132624082305024
  1 7461808204882788 1 18014398509481984))
  (or (and (jdouble.near 537679946416323739331141270765568 1298074214633706907132624082305024 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 7461808204882788))))
  (jdouble.near 537679946416323739331141270765568 1298074214633706907132624082305024 S2 M2 1
    36028797018963968)
  (jdouble.near 537679946416323739331141270765568 1298074214633706907132624082305024 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: t*ln2_hi = 0x3fe62e42_fee00000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 1
  6243314766446592 1 9007199254740992))
  (or (and (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 6243314766446592))))
  (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near 28117390055725586994330580549632 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hi = x-t*ln2_hi = 0xbfd62e42_fdc00001: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56234779941445989612553188147200)
  162259276829213363391578010288128 (- 1) 6243314747572225 1 18014398509481984))
  (or (and (jdouble.near (- 56234779941445989612553188147200) 162259276829213363391578010288128 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6243314747572225))))
  (jdouble.near (- 56234779941445989612553188147200) 162259276829213363391578010288128 S2 M2 1
    36028797018963968)
  (jdouble.near (- 56234779941445989612553188147200) 162259276829213363391578010288128 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: lo = t*ln2_lo = 0x3dea39ef_35793c76: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33245792907574618198748633759744
  174224571863520493293247799005065324265472 1 7382048951581814 1 38685626227668133590597632))
  (or (and (jdouble.near 33245792907574618198748633759744 174224571863520493293247799005065324265472
    S2 M2 1 38685626227668133590597632) (not (and (= S2 1) (= M2 7382048951581814))))
  (jdouble.near 33245792907574618198748633759744 174224571863520493293247799005065324265472 S2 M2 1
    77371252455336267181195264)
  (jdouble.near 33245792907574618198748633759744 174224571863520493293247799005065324265472 S2 M2 1
    19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: x = hi-lo = 0xbfd62e42_fdf473df: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 241526540879250491966570126947753369010176)
  696898287454081973172991196020261297061888 (- 1) 6243314751009759 1 18014398509481984))
  (or (and (jdouble.near (- 241526540879250491966570126947753369010176)
    696898287454081973172991196020261297061888 S2 M2 1 18014398509481984) (not (and (= S2 (- 1)) (=
    M2 6243314751009759))))
  (jdouble.near (- 241526540879250491966570126947753369010176)
    696898287454081973172991196020261297061888 S2 M2 1 36028797018963968)
  (jdouble.near (- 241526540879250491966570126947753369010176)
    696898287454081973172991196020261297061888 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hi-x = 0x3dea39ef_00000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 61925107365893642387456 324518553658426726783156020576256 1
  7382048054444032 1 38685626227668133590597632))
  (or (and (jdouble.near 61925107365893642387456 324518553658426726783156020576256 S2 M2 1
    38685626227668133590597632) (not (and (= S2 1) (= M2 7382048054444032))))
  (jdouble.near 61925107365893642387456 324518553658426726783156020576256 S2 M2 1
    77371252455336267181195264)
  (jdouble.near 61925107365893642387456 324518553658426726783156020576256 S2 M2 1
    19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: c = (hi-x)-lo = 0xbc7abc9e_3b000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 34706336909171216401548455626932224)
  1496577676626844588240573268701473812127674924007424 (- 1) 7525737175187456 1
  324518553658426726783156020576256))
  (or (and (jdouble.near (- 34706336909171216401548455626932224)
    1496577676626844588240573268701473812127674924007424 S2 M2 1 324518553658426726783156020576256)
    (not (and (= S2 (- 1)) (= M2 7525737175187456))))
  (jdouble.near (- 34706336909171216401548455626932224)
    1496577676626844588240573268701473812127674924007424 S2 M2 1 649037107316853453566312041152512)
  (jdouble.near (- 34706336909171216401548455626932224)
    1496577676626844588240573268701473812127674924007424 S2 M2 1
    162259276829213363391578010288128))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hfx = 0.5*x = 0xbfc62e42_fdf473df: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 28117389986204271647750004670464)
  162259276829213363391578010288128 (- 1) 6243314751009759 1 36028797018963968))
  (or (and (jdouble.near (- 28117389986204271647750004670464) 162259276829213363391578010288128 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6243314751009759))))
  (jdouble.near (- 28117389986204271647750004670464) 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near (- 28117389986204271647750004670464) 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hxs = x*hfx = 0x3faebfbd_fcacfb1f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 1
  8655072010238751 1 144115188075855872))
  (or (and (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072010238751))))
  (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hxs*Q5 = 0xbe49ef96_95a53f36: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755296824247216626788599316851)
  5444517870735015415413993718908291383296 (- 1) 7300304452534070 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755296824247216626788599316851)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304452534070))))
  (jdouble.near (- 65755296824247216626788599316851) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755296824247216626788599316851) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: Q4+hxs*Q5 = 0x3ed0c2d2_bb9b7f99: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144694016259994695964896530530304
  713623846352979940529142984724747568191373312 1 4717809973821337 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144694016259994695964896530530304
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973821337))))
  (jdouble.near 2851741144694016259994695964896530530304
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144694016259994695964896530530304
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hxs*(Q4+...) = 0x3e901b13_ecb70a0a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985054046268458537188030087
  170141183460469231731687303715884105728 1 4533372017117706 1 18889465931478580854784))
  (or (and (jdouble.near 40832985054046268458537188030087 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372017117706))))
  (jdouble.near 40832985054046268458537188030087 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985054046268458537188030087 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: Q3+hxs*(...) = 0xbf14bdfe_8abe24ad: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266985334577412521843768982241280)
  1393796574908163946345982392040522594123776 (- 1) 5838400481273005 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266985334577412521843768982241280)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481273005))))
  (jdouble.near (- 110284266985334577412521843768982241280)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266985334577412521843768982241280)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hxs*(Q3+...) = 0xbed3ee67_cf4e1943: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531776590030438697394461216755)
  10633823966279326983230456482242756608 (- 1) 5610154184546627 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531776590030438697394461216755) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154184546627))))
  (jdouble.near (- 50531776590030438697394461216755) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531776590030438697394461216755) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: Q2+hxs*(...) = 0x3f59edb1_b22f076c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588440797894315713017191333888
  5444517870735015415413993718908291383296 1 7298221872187244 1 4611686018427387904))
  (or (and (jdouble.near 8616219588440797894315713017191333888
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221872187244))))
  (jdouble.near 8616219588440797894315713017191333888 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588440797894315713017191333888 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hxs*(Q2+...) = 0x3f18ea33_4449247f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288
  1 7012905350931583 1 73786976294838206464))
  (or (and (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905350931583))))
  (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: Q1+hxs*(...) = 0xbfa1049b_f76eec62: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132702367740256406594203418624)
  10633823966279326983230456482242756608 (- 1) 4790142521764962 1 144115188075855872))
  (or (and (jdouble.near (- 353450132702367740256406594203418624)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521764962))))
  (jdouble.near (- 353450132702367740256406594203418624) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132702367740256406594203418624) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: hxs*(Q1+...) = 0xbf605a4a_c47e53a5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028465182389722127526442462)
  20769187434139310514121985316880384 (- 1) 4602876798063525 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028465182389722127526442462) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876798063525))))
  (jdouble.near (- 41459028465182389722127526442462) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028465182389722127526442462) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: r1 = one+hxs*(...) = 0x3fefefa5_b53b81ac: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202837064064106512139681792
  10384593717069655257060992658440192 1 8989219267248556 1 9007199254740992))
  (or (and (jdouble.near 10363864202837064064106512139681792 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267248556))))
  (jdouble.near 10363864202837064064106512139681792 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202837064064106512139681792 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: r1*hfx = 0xbfc622ed_4685e190: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56122525251274046649078634658004)
  324518553658426726783156020576256 (- 1) 6230851973407120 1 36028797018963968))
  (or (and (jdouble.near (- 56122525251274046649078634658004) 324518553658426726783156020576256 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6230851973407120))))
  (jdouble.near (- 56122525251274046649078634658004) 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near (- 56122525251274046649078634658004) 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: t = 3.0-r1*hfx = 0x4009622e_d4685e19: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 257419546556638557729308857597952 81129638414606681695789005144064 1
  7144827689393689 1 2251799813685248))
  (or (and (jdouble.near 257419546556638557729308857597952 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 7144827689393689))))
  (jdouble.near 257419546556638557729308857597952 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 257419546556638557729308857597952 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: r1-t = 0xc0016645_67197dae: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44112964367993499482563647897600) 20282409603651670423947251286016
  (- 1) 4897522872581550 1 2251799813685248))
  (or (and (jdouble.near (- 44112964367993499482563647897600) 20282409603651670423947251286016 S2 M2
    1 2251799813685248) (not (and (= S2 (- 1)) (= M2 4897522872581550))))
  (jdouble.near (- 44112964367993499482563647897600) 20282409603651670423947251286016 S2 M2 1
    4503599627370496)
  (jdouble.near (- 44112964367993499482563647897600) 20282409603651670423947251286016 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: x*t = 0xbff19832_794bd79e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44607408106614591153427232010951) 40564819207303340847894502572032
  (- 1) 4952417154881438 1 4503599627370496))
  (or (and (jdouble.near (- 44607408106614591153427232010951) 40564819207303340847894502572032 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 4952417154881438))))
  (jdouble.near (- 44607408106614591153427232010951) 40564819207303340847894502572032 S2 M2 1
    9007199254740992)
  (jdouble.near (- 44607408106614591153427232010951) 40564819207303340847894502572032 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: 6.0-x*t = 0x401c660c_9e52f5e8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 35999540418804329673980521742336 5070602400912917605986812821504 1
  7993503729776104 1 1125899906842624))
  (or (and (jdouble.near 35999540418804329673980521742336 5070602400912917605986812821504 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 7993503729776104))))
  (jdouble.near 35999540418804329673980521742336 5070602400912917605986812821504 S2 M2 1
    2251799813685248)
  (jdouble.near 35999540418804329673980521742336 5070602400912917605986812821504 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: (r1-t)/(6.0-x*t) = 0xbfd39b23_9e2834bc: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 5514120545999187435320455987200) 17999770209402165962890167713792
  (- 1) 5518601837098172 1 18014398509481984))
  (or (and (jdouble.near (- 5514120545999187435320455987200) 17999770209402165962890167713792 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5518601837098172))))
  (jdouble.near (- 5514120545999187435320455987200) 17999770209402165962890167713792 S2 M2 1
    36028797018963968)
  (jdouble.near (- 5514120545999187435320455987200) 17999770209402165962890167713792 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: e = hxs*(...) = 0xbf92d6eb_c7f7c3c1: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 47763896295920539806574745663172)
  2596148429267413814265248164610048 (- 1) 5302857741353921 1 288230376151711744))
  (or (and (jdouble.near (- 47763896295920539806574745663172) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 5302857741353921))))
  (jdouble.near (- 47763896295920539806574745663172) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 47763896295920539806574745663172) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: e-c = 0xbf92d6eb_c7f7c3ba: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 1720875724480563799886638154492350675577588416512)
  93536104789177786765035829293842113257979682750464 (- 1) 5302857741353914 1 288230376151711744))
  (or (and (jdouble.near (- 1720875724480563799886638154492350675577588416512)
    93536104789177786765035829293842113257979682750464 S2 M2 1 288230376151711744) (not (and (= S2
    (- 1)) (= M2 5302857741353914))))
  (jdouble.near (- 1720875724480563799886638154492350675577588416512)
    93536104789177786765035829293842113257979682750464 S2 M2 1 576460752303423488)
  (jdouble.near (- 1720875724480563799886638154492350675577588416512)
    93536104789177786765035829293842113257979682750464 S2 M2 1 144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: x*(e-c) = 0x3f7a1dfc_fa667495: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33107409959101184576483286846726 5192296858534827628530496329220096 1
  7351321764459669 1 1152921504606846976))
  (or (and (jdouble.near 33107409959101184576483286846726 5192296858534827628530496329220096 S2 M2 1
    1152921504606846976) (not (and (= S2 1) (= M2 7351321764459669))))
  (jdouble.near 33107409959101184576483286846726 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near 33107409959101184576483286846726 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: x*(e-c)-c = 0x3f7a1dfc_fa6674b0: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2385640306480174014226139210547649486371757752320
  374144419156711147060143317175368453031918731001856 1 7351321764459696 1 1152921504606846976))
  (or (and (jdouble.near 2385640306480174014226139210547649486371757752320
    374144419156711147060143317175368453031918731001856 S2 M2 1 1152921504606846976) (not (and (= S2
    1) (= M2 7351321764459696))))
  (jdouble.near 2385640306480174014226139210547649486371757752320
    374144419156711147060143317175368453031918731001856 S2 M2 1 2305843009213693952)
  (jdouble.near 2385640306480174014226139210547649486371757752320
    374144419156711147060143317175368453031918731001856 S2 M2 1 576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: e -= hxs = 0xbfab7bfe_5d602c89: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 8919181525833826748218614233432064)
  166153499473114484112975882535043072 (- 1) 7736156789681289 1 144115188075855872))
  (or (and (jdouble.near (- 8919181525833826748218614233432064) 166153499473114484112975882535043072
    S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2 7736156789681289))))
  (jdouble.near (- 8919181525833826748218614233432064) 166153499473114484112975882535043072 S2 M2 1
    288230376151711744)
  (jdouble.near (- 8919181525833826748218614233432064) 166153499473114484112975882535043072 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: x+0.5 = 0x3fc3a37a_04171842: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 24894858442198138400288995803136 162259276829213363391578010288128 1
  5527769007462466 1 36028797018963968))
  (or (and (jdouble.near 24894858442198138400288995803136 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 5527769007462466))))
  (jdouble.near 24894858442198138400288995803136 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 24894858442198138400288995803136 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: e-(x+0.5) = 0xbfca8279_9b6f2364: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 1075359892832647514691079560495104)
  5192296858534827628530496329220096 (- 1) 7461808204882788 1 36028797018963968))
  (or (and (jdouble.near (- 1075359892832647514691079560495104) 5192296858534827628530496329220096
    S2 M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 7461808204882788))))
  (jdouble.near (- 1075359892832647514691079560495104) 5192296858534827628530496329220096 S2 M2 1
    72057594037927936)
  (jdouble.near (- 1075359892832647514691079560495104) 5192296858534827628530496329220096 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 1 at 0x3fd62e42_ffffffff: -2.0*(e-(x+0.5)) = 0x3fda8279_9b6f2364: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33604996651020233708196329422848 81129638414606681695789005144064 1
  7461808204882788 1 18014398509481984))
  (or (and (jdouble.near 33604996651020233708196329422848 81129638414606681695789005144064 S2 M2 1
    18014398509481984) (not (and (= S2 1) (= M2 7461808204882788))))
  (jdouble.near 33604996651020233708196329422848 81129638414606681695789005144064 S2 M2 1
    36028797018963968)
  (jdouble.near 33604996651020233708196329422848 81129638414606681695789005144064 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "not a witness: at 0x3fd62e42_ffffffff both give 0x3fda8279_9b6f2364: expect unsat")
(push 1)
(assert (not (= 4601133428122788708 4601133428122788708)))
(check-sat)
(pop 1)

; The control 0xbfd62e42_ffffffff, the word's top double.

(echo "at 0xbfd62e42_ffffffff: hx is 0x3fd62e42, not above it: unreduced: expect unsat")
(push 1)
(assert (not (= (c.hx (- 4623457102151548929)) 1071001154)))
(check-sat)
(pop 1)

(echo "at 0xbfd62e42_ffffffff: x*invln2 rounds into 2^-1 only, with x's sign: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (jdouble.near (* (- 6497320848556798) 6243314785320959) 81129638414606681695789005144064
  S2 M2 1 18014398509481984)
  (jdouble.near (* (- 6497320848556798) 6243314785320959) 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near (* (- 6497320848556798) 6243314785320959) 81129638414606681695789005144064 1 M2 1
    9007199254740992)))
(check-sat)
(pop 1)

(echo "at 0xbfd62e42_ffffffff: the sum with h rounds into 2^0, x's sign: k = -1: expect unsat")
(push 1)
(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 6243314785320959) 81129638414606681695789005144064 (-
  1) P 1 9007199254740992))
(assert (or (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 1 M2 1 4503599627370496)))
(check-sat)
(pop 1)

(echo "not vacuous: at 0xbfd62e42_ffffffff the sum rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 6243314785320959) 81129638414606681695789005144064 (-
  1) P 1 9007199254740992))
(assert (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 (- 1) M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: hfx = 0.5*x = 0xbfc62e42_ffffffff: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 28117390140728179182384567025664)
  162259276829213363391578010288128 (- 1) 6243314785320959 1 36028797018963968))
  (or (and (jdouble.near (- 28117390140728179182384567025664) 162259276829213363391578010288128 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6243314785320959))))
  (jdouble.near (- 28117390140728179182384567025664) 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near (- 28117390140728179182384567025664) 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: hxs = x*hfx = 0x3faebfbe_02588ffd: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 1
  8655072105369597 1 144115188075855872))
  (or (and (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072105369597))))
  (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979508607292365260644679681 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: hxs*Q5 = 0xbe49ef96_9a6d9d3c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755297546986092568179686770297)
  5444517870735015415413993718908291383296 (- 1) 7300304532774204 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755297546986092568179686770297)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304532774204))))
  (jdouble.near (- 65755297546986092568179686770297) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755297546986092568179686770297) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: Q4+hxs*Q5 = 0x3ed0c2d2_bb991b6a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144599285430149053705776674111488
  713623846352979940529142984724747568191373312 1 4717809973664618 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144599285430149053705776674111488
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973664618))))
  (jdouble.near 2851741144599285430149053705776674111488
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144599285430149053705776674111488
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: hxs*(Q4+...) = 0x3e901b13_efad0dc6: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985501499108290117411818946
  170141183460469231731687303715884105728 1 4533372066794950 1 18889465931478580854784))
  (or (and (jdouble.near 40832985501499108290117411818946 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372066794950))))
  (jdouble.near 40832985501499108290117411818946 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985501499108290117411818946 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: Q3+hxs*(...) = 0xbf14bdfe_8abb2ea9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266981669043787100950245947736064)
  1393796574908163946345982392040522594123776 (- 1) 5838400481078953 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266981669043787100950245947736064)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481078953))))
  (jdouble.near (- 110284266981669043787100950245947736064)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266981669043787100950245947736064)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: hxs*(Q3+...) = 0xbed3ee67_d2f8286e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531777143762881715511402791941)
  10633823966279326983230456482242756608 (- 1) 5610154246023278 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531777143762881715511402791941) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154246023278))))
  (jdouble.near (- 50531777143762881715511402791941) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531777143762881715511402791941) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: Q2+hxs*(...) = 0x3f59edb1_b22b5d5d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588157286882439272922175504384
  5444517870735015415413993718908291383296 1 7298221871947101 1 4611686018427387904))
  (or (and (jdouble.near 8616219588157286882439272922175504384
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221871947101))))
  (jdouble.near 8616219588157286882439272922175504384 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588157286882439272922175504384 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: hxs*(Q2+...) = 0x3f18ea33_48ddc9ad: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288
  1 7012905427782061 1 73786976294838206464))
  (or (and (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905427782061))))
  (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166636542687636809923637688297 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: Q1+hxs*(...) = 0xbfa1049b_f76ca20f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132691292419165717170181111808)
  10633823966279326983230456482242756608 (- 1) 4790142521614863 1 144115188075855872))
  (or (and (jdouble.near (- 353450132691292419165717170181111808)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521614863))))
  (jdouble.near (- 353450132691292419165717170181111808) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132691292419165717170181111808) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: hxs*(Q1+...) = 0xbf605a4a_c780186a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028919573582610257903520211)
  20769187434139310514121985316880384 (- 1) 4602876848511082 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028919573582610257903520211) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876848511082))))
  (jdouble.near (- 41459028919573582610257903520211) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028919573582610257903520211) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: r1 = one+hxs*(...) = 0x3fefefa5_b5387fe8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202609868465199560282603520
  10384593717069655257060992658440192 1 8989219267051496 1 9007199254740992))
  (or (and (jdouble.near 10363864202609868465199560282603520 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267051496))))
  (jdouble.near 10363864202609868465199560282603520 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202609868465199560282603520 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: r1*hfx = 0xbfc622ed_488e4c95: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56122525558474639159901941104664)
  324518553658426726783156020576256 (- 1) 6230852007513237 1 36028797018963968))
  (or (and (jdouble.near (- 56122525558474639159901941104664) 324518553658426726783156020576256 S2
    M2 1 36028797018963968) (not (and (= S2 (- 1)) (= M2 6230852007513237))))
  (jdouble.near (- 56122525558474639159901941104664) 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near (- 56122525558474639159901941104664) 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: t = 3.0-r1*hfx = 0x4009622e_d488e4c9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 257419546633438705635436127059968 81129638414606681695789005144064 1
  7144827691525321 1 2251799813685248))
  (or (and (jdouble.near 257419546633438705635436127059968 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 7144827691525321))))
  (jdouble.near 257419546633438705635436127059968 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 257419546633438705635436127059968 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: r1-t = 0xc0016645_673ac4cf: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016
  (- 1) 4897522874762447 1 2251799813685248))
  (or (and (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016 S2 M2
    1 2251799813685248) (not (and (= S2 (- 1)) (= M2 4897522874762447))))
  (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016 S2 M2 1
    4503599627370496)
  (jdouble.near (- 44112964387637273315630513127424) 20282409603651670423947251286016 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: x*t = 0xbff19832_7b01af11: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 44607408365070652552215260502839) 40564819207303340847894502572032
  (- 1) 4952417183575825 1 4503599627370496))
  (or (and (jdouble.near (- 44607408365070652552215260502839) 40564819207303340847894502572032 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 4952417183575825))))
  (jdouble.near (- 44607408365070652552215260502839) 40564819207303340847894502572032 S2 M2 1
    9007199254740992)
  (jdouble.near (- 44607408365070652552215260502839) 40564819207303340847894502572032 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: 6.0-x*t = 0x401c660c_9ec06bc4: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 35999540451111337324186722893824 5070602400912917605986812821504 1
  7993503736949700 1 1125899906842624))
  (or (and (jdouble.near 35999540451111337324186722893824 5070602400912917605986812821504 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 7993503736949700))))
  (jdouble.near 35999540451111337324186722893824 5070602400912917605986812821504 S2 M2 1
    2251799813685248)
  (jdouble.near 35999540451111337324186722893824 5070602400912917605986812821504 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: (r1-t)/(6.0-x*t) = 0xbfd39b23_9e022252: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 5514120548454659164453814140928) 17999770225555668099143408025600
  (- 1) 5518601834603090 1 18014398509481984))
  (or (and (jdouble.near (- 5514120548454659164453814140928) 17999770225555668099143408025600 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5518601834603090))))
  (jdouble.near (- 5514120548454659164453814140928) 17999770225555668099143408025600 S2 M2 1
    36028797018963968)
  (jdouble.near (- 5514120548454659164453814140928) 17999770225555668099143408025600 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: e = hxs*(...) = 0xbf92d6eb_cb4c8c2b: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 47763896799314686688068248254730)
  2596148429267413814265248164610048 (- 1) 5302857797241899 1 288230376151711744))
  (or (and (jdouble.near (- 47763896799314686688068248254730) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 5302857797241899))))
  (jdouble.near (- 47763896799314686688068248254730) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 47763896799314686688068248254730) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: x*e = 0x3f7a1dfd_016d201d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33107410489974880183921677661141 5192296858534827628530496329220096 1
  7351321882337309 1 1152921504606846976))
  (or (and (jdouble.near 33107410489974880183921677661141 5192296858534827628530496329220096 S2 M2 1
    1152921504606846976) (not (and (= S2 1) (= M2 7351321882337309))))
  (jdouble.near 33107410489974880183921677661141 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near 33107410489974880183921677661141 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: x*e-hxs = 0xbfab7bfe_622aebf9: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 8919181618524270485632911329460224)
  166153499473114484112975882535043072 (- 1) 7736156870077433 1 144115188075855872))
  (or (and (jdouble.near (- 8919181618524270485632911329460224) 166153499473114484112975882535043072
    S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2 7736156870077433))))
  (jdouble.near (- 8919181618524270485632911329460224) 166153499473114484112975882535043072 S2 M2 1
    288230376151711744)
  (jdouble.near (- 8919181618524270485632911329460224) 166153499473114484112975882535043072 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 at 0xbfd62e42_ffffffff: x-(x*e-hxs) = 0xbfd2bec3_33baa280: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 760394271713860014253691346354176)
  2596148429267413814265248164610048 (- 1) 5276295176561280 1 18014398509481984))
  (or (and (jdouble.near (- 760394271713860014253691346354176) 2596148429267413814265248164610048 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5276295176561280))))
  (jdouble.near (- 760394271713860014253691346354176) 2596148429267413814265248164610048 S2 M2 1
    36028797018963968)
  (jdouble.near (- 760394271713860014253691346354176) 2596148429267413814265248164610048 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: t*ln2_hi = 0xbfe62e42_fee00000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032
  (- 1) 6243314766446592 1 9007199254740992))
  (or (and (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032 S2 M2
    1 9007199254740992) (not (and (= S2 (- 1)) (= M2 6243314766446592))))
  (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near (- 28117390055725586994330580549632) 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hi = x-t*ln2_hi = 0x3fd62e42_fdc00001: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 56234779941445989612553188147200 162259276829213363391578010288128 1
  6243314747572225 1 18014398509481984))
  (or (and (jdouble.near 56234779941445989612553188147200 162259276829213363391578010288128 S2 M2 1
    18014398509481984) (not (and (= S2 1) (= M2 6243314747572225))))
  (jdouble.near 56234779941445989612553188147200 162259276829213363391578010288128 S2 M2 1
    36028797018963968)
  (jdouble.near 56234779941445989612553188147200 162259276829213363391578010288128 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: lo = t*ln2_lo = 0xbdea39ef_35793c76: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 33245792907574618198748633759744)
  174224571863520493293247799005065324265472 (- 1) 7382048951581814 1 38685626227668133590597632))
  (or (and (jdouble.near (- 33245792907574618198748633759744)
    174224571863520493293247799005065324265472 S2 M2 1 38685626227668133590597632) (not (and (= S2
    (- 1)) (= M2 7382048951581814))))
  (jdouble.near (- 33245792907574618198748633759744) 174224571863520493293247799005065324265472 S2
    M2 1 77371252455336267181195264)
  (jdouble.near (- 33245792907574618198748633759744) 174224571863520493293247799005065324265472 S2
    M2 1 19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: x = hi-lo = 0x3fd62e42_fdf473df: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 241526540879250491966570126947753369010176
  696898287454081973172991196020261297061888 1 6243314751009759 1 18014398509481984))
  (or (and (jdouble.near 241526540879250491966570126947753369010176
    696898287454081973172991196020261297061888 S2 M2 1 18014398509481984) (not (and (= S2 1) (= M2
    6243314751009759))))
  (jdouble.near 241526540879250491966570126947753369010176
    696898287454081973172991196020261297061888 S2 M2 1 36028797018963968)
  (jdouble.near 241526540879250491966570126947753369010176
    696898287454081973172991196020261297061888 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hi-x = 0xbdea39ef_00000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 (- 1)
  7382048054444032 1 38685626227668133590597632))
  (or (and (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 S2 M2 1
    38685626227668133590597632) (not (and (= S2 (- 1)) (= M2 7382048054444032))))
  (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 S2 M2 1
    77371252455336267181195264)
  (jdouble.near (- 61925107365893642387456) 324518553658426726783156020576256 S2 M2 1
    19342813113834066795298816))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: c = (hi-x)-lo = 0x3c7abc9e_3b000000: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 34706336909171216401548455626932224
  1496577676626844588240573268701473812127674924007424 1 7525737175187456 1
  324518553658426726783156020576256))
  (or (and (jdouble.near 34706336909171216401548455626932224
    1496577676626844588240573268701473812127674924007424 S2 M2 1 324518553658426726783156020576256)
    (not (and (= S2 1) (= M2 7525737175187456))))
  (jdouble.near 34706336909171216401548455626932224
    1496577676626844588240573268701473812127674924007424 S2 M2 1 649037107316853453566312041152512)
  (jdouble.near 34706336909171216401548455626932224
    1496577676626844588240573268701473812127674924007424 S2 M2 1
    162259276829213363391578010288128))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hfx = 0.5*x = 0x3fc62e42_fdf473df: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 28117389986204271647750004670464 162259276829213363391578010288128 1
  6243314751009759 1 36028797018963968))
  (or (and (jdouble.near 28117389986204271647750004670464 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6243314751009759))))
  (jdouble.near 28117389986204271647750004670464 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 28117389986204271647750004670464 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hxs = x*hfx = 0x3faebfbd_fcacfb1f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 1
  8655072010238751 1 144115188075855872))
  (or (and (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 8655072010238751))))
  (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 S2 M2 1
    288230376151711744)
  (jdouble.near 38978979080176049018310113238081 649037107316853453566312041152512 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hxs*Q5 = 0xbe49ef96_95a53f36: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 65755296824247216626788599316851)
  5444517870735015415413993718908291383296 (- 1) 7300304452534070 1 604462909807314587353088))
  (or (and (jdouble.near (- 65755296824247216626788599316851)
    5444517870735015415413993718908291383296 S2 M2 1 604462909807314587353088) (not (and (= S2 (-
    1)) (= M2 7300304452534070))))
  (jdouble.near (- 65755296824247216626788599316851) 5444517870735015415413993718908291383296 S2 M2
    1 1208925819614629174706176)
  (jdouble.near (- 65755296824247216626788599316851) 5444517870735015415413993718908291383296 S2 M2
    1 302231454903657293676544))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: Q4+hxs*Q5 = 0x3ed0c2d2_bb9b7f99: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 2851741144694016259994695964896530530304
  713623846352979940529142984724747568191373312 1 4717809973821337 1 1180591620717411303424))
  (or (and (jdouble.near 2851741144694016259994695964896530530304
    713623846352979940529142984724747568191373312 S2 M2 1 1180591620717411303424) (not (and (= S2 1)
    (= M2 4717809973821337))))
  (jdouble.near 2851741144694016259994695964896530530304
    713623846352979940529142984724747568191373312 S2 M2 1 2361183241434822606848)
  (jdouble.near 2851741144694016259994695964896530530304
    713623846352979940529142984724747568191373312 S2 M2 1 590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hxs*(Q4+...) = 0x3e901b13_ecb70a0a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 40832985054046268458537188030087
  170141183460469231731687303715884105728 1 4533372017117706 1 18889465931478580854784))
  (or (and (jdouble.near 40832985054046268458537188030087 170141183460469231731687303715884105728 S2
    M2 1 18889465931478580854784) (not (and (= S2 1) (= M2 4533372017117706))))
  (jdouble.near 40832985054046268458537188030087 170141183460469231731687303715884105728 S2 M2 1
    37778931862957161709568)
  (jdouble.near 40832985054046268458537188030087 170141183460469231731687303715884105728 S2 M2 1
    9444732965739290427392))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: Q3+hxs*(...) = 0xbf14bdfe_8abe24ad: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 110284266985334577412521843768982241280)
  1393796574908163946345982392040522594123776 (- 1) 5838400481273005 1 73786976294838206464))
  (or (and (jdouble.near (- 110284266985334577412521843768982241280)
    1393796574908163946345982392040522594123776 S2 M2 1 73786976294838206464) (not (and (= S2 (- 1))
    (= M2 5838400481273005))))
  (jdouble.near (- 110284266985334577412521843768982241280)
    1393796574908163946345982392040522594123776 S2 M2 1 147573952589676412928)
  (jdouble.near (- 110284266985334577412521843768982241280)
    1393796574908163946345982392040522594123776 S2 M2 1 36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hxs*(Q3+...) = 0xbed3ee67_cf4e1943: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 50531776590030438697394461216755)
  10633823966279326983230456482242756608 (- 1) 5610154184546627 1 1180591620717411303424))
  (or (and (jdouble.near (- 50531776590030438697394461216755) 10633823966279326983230456482242756608
    S2 M2 1 1180591620717411303424) (not (and (= S2 (- 1)) (= M2 5610154184546627))))
  (jdouble.near (- 50531776590030438697394461216755) 10633823966279326983230456482242756608 S2 M2 1
    2361183241434822606848)
  (jdouble.near (- 50531776590030438697394461216755) 10633823966279326983230456482242756608 S2 M2 1
    590295810358705651712))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: Q2+hxs*(...) = 0x3f59edb1_b22f076c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 8616219588440797894315713017191333888
  5444517870735015415413993718908291383296 1 7298221872187244 1 4611686018427387904))
  (or (and (jdouble.near 8616219588440797894315713017191333888
    5444517870735015415413993718908291383296 S2 M2 1 4611686018427387904) (not (and (= S2 1) (= M2
    7298221872187244))))
  (jdouble.near 8616219588440797894315713017191333888 5444517870735015415413993718908291383296 S2 M2
    1 9223372036854775808)
  (jdouble.near 8616219588440797894315713017191333888 5444517870735015415413993718908291383296 S2 M2
    1 2305843009213693952))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hxs*(Q2+...) = 0x3f18ea33_4449247f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288
  1 7012905350931583 1 73786976294838206464))
  (or (and (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288 S2 M2
    1 73786976294838206464) (not (and (= S2 1) (= M2 7012905350931583))))
  (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288 S2 M2 1
    147573952589676412928)
  (jdouble.near 63166635850480070793647016692244 664613997892457936451903530140172288 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: Q1+hxs*(...) = 0xbfa1049b_f76eec62: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 353450132702367740256406594203418624)
  10633823966279326983230456482242756608 (- 1) 4790142521764962 1 144115188075855872))
  (or (and (jdouble.near (- 353450132702367740256406594203418624)
    10633823966279326983230456482242756608 S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    4790142521764962))))
  (jdouble.near (- 353450132702367740256406594203418624) 10633823966279326983230456482242756608 S2
    M2 1 288230376151711744)
  (jdouble.near (- 353450132702367740256406594203418624) 10633823966279326983230456482242756608 S2
    M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: hxs*(Q1+...) = 0xbf605a4a_c47e53a5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 41459028465182389722127526442462)
  20769187434139310514121985316880384 (- 1) 4602876798063525 1 2305843009213693952))
  (or (and (jdouble.near (- 41459028465182389722127526442462) 20769187434139310514121985316880384 S2
    M2 1 2305843009213693952) (not (and (= S2 (- 1)) (= M2 4602876798063525))))
  (jdouble.near (- 41459028465182389722127526442462) 20769187434139310514121985316880384 S2 M2 1
    4611686018427387904)
  (jdouble.near (- 41459028465182389722127526442462) 20769187434139310514121985316880384 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: r1 = one+hxs*(...) = 0x3fefefa5_b53b81ac: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10363864202837064064106512139681792
  10384593717069655257060992658440192 1 8989219267248556 1 9007199254740992))
  (or (and (jdouble.near 10363864202837064064106512139681792 10384593717069655257060992658440192 S2
    M2 1 9007199254740992) (not (and (= S2 1) (= M2 8989219267248556))))
  (jdouble.near 10363864202837064064106512139681792 10384593717069655257060992658440192 S2 M2 1
    18014398509481984)
  (jdouble.near 10363864202837064064106512139681792 10384593717069655257060992658440192 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: r1*hfx = 0x3fc622ed_4685e190: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 56122525251274046649078634658004 324518553658426726783156020576256 1
  6230851973407120 1 36028797018963968))
  (or (and (jdouble.near 56122525251274046649078634658004 324518553658426726783156020576256 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 6230851973407120))))
  (jdouble.near 56122525251274046649078634658004 324518553658426726783156020576256 S2 M2 1
    72057594037927936)
  (jdouble.near 56122525251274046649078634658004 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: t = 3.0-r1*hfx = 0x40069dd1_2b97a1e7: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 229358283931001532445425173266432 81129638414606681695789005144064 1
  6365971192717799 1 2251799813685248))
  (or (and (jdouble.near 229358283931001532445425173266432 81129638414606681695789005144064 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 6365971192717799))))
  (jdouble.near 229358283931001532445425173266432 81129638414606681695789005144064 S2 M2 1
    4503599627370496)
  (jdouble.near 229358283931001532445425173266432 81129638414606681695789005144064 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: r1-t = 0xbffd43cf_7c9182f8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 37097648711584243161592726814720) 20282409603651670423947251286016
  (- 1) 8237332751811320 1 4503599627370496))
  (or (and (jdouble.near (- 37097648711584243161592726814720) 20282409603651670423947251286016 S2 M2
    1 4503599627370496) (not (and (= S2 (- 1)) (= M2 8237332751811320))))
  (jdouble.near (- 37097648711584243161592726814720) 20282409603651670423947251286016 S2 M2 1
    9007199254740992)
  (jdouble.near (- 37097648711584243161592726814720) 20282409603651670423947251286016 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: x*t = 0x3fef5a64_0745ac61: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 39744761851998223789822782000441 40564819207303340847894502572032 1
  8825109943266401 1 9007199254740992))
  (or (and (jdouble.near 39744761851998223789822782000441 40564819207303340847894502572032 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 8825109943266401))))
  (jdouble.near 39744761851998223789822782000441 40564819207303340847894502572032 S2 M2 1
    18014398509481984)
  (jdouble.near 39744761851998223789822782000441 40564819207303340847894502572032 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: 6.0-x*t = 0x401414b3_7f174a74: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 50911038347955455612148539981824 10141204801825835211973625643008 1
  5652260698147444 1 1125899906842624))
  (or (and (jdouble.near 50911038347955455612148539981824 10141204801825835211973625643008 S2 M2 1
    1125899906842624) (not (and (= S2 1) (= M2 5652260698147444))))
  (jdouble.near 50911038347955455612148539981824 10141204801825835211973625643008 S2 M2 1
    2251799813685248)
  (jdouble.near 50911038347955455612148539981824 10141204801825835211973625643008 S2 M2 1
    562949953421312))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: (r1-t)/(6.0-x*t) = 0xbfd75150_3452c34f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9274412177896060790398181703680) 25455519173977728369024223412224
  (- 1) 6563329381417807 1 18014398509481984))
  (or (and (jdouble.near (- 9274412177896060790398181703680) 25455519173977728369024223412224 S2 M2
    1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6563329381417807))))
  (jdouble.near (- 9274412177896060790398181703680) 25455519173977728369024223412224 S2 M2 1
    36028797018963968)
  (jdouble.near (- 9274412177896060790398181703680) 25455519173977728369024223412224 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: e = hxs*(...) = 0xbf9667f2_f825fdc8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56806088423086876934424952839057)
  2596148429267413814265248164610048 (- 1) 6306742730620360 1 288230376151711744))
  (or (and (jdouble.near (- 56806088423086876934424952839057) 2596148429267413814265248164610048 S2
    M2 1 288230376151711744) (not (and (= S2 (- 1)) (= M2 6306742730620360))))
  (jdouble.near (- 56806088423086876934424952839057) 2596148429267413814265248164610048 S2 M2 1
    576460752303423488)
  (jdouble.near (- 56806088423086876934424952839057) 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: e-c = 0xbf9667f2_f825fdcf: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 2046655029236718161470963946653256704322042855424)
  93536104789177786765035829293842113257979682750464 (- 1) 6306742730620367 1 288230376151711744))
  (or (and (jdouble.near (- 2046655029236718161470963946653256704322042855424)
    93536104789177786765035829293842113257979682750464 S2 M2 1 288230376151711744) (not (and (= S2
    (- 1)) (= M2 6306742730620367))))
  (jdouble.near (- 2046655029236718161470963946653256704322042855424)
    93536104789177786765035829293842113257979682750464 S2 M2 1 576460752303423488)
  (jdouble.near (- 2046655029236718161470963946653256704322042855424)
    93536104789177786765035829293842113257979682750464 S2 M2 1 144115188075855872))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: x*(e-c) = 0xbf7f0fb6_c029ef44: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 39374979920905704174441741161553)
  5192296858534827628530496329220096 (- 1) 8743001860468548 1 1152921504606846976))
  (or (and (jdouble.near (- 39374979920905704174441741161553) 5192296858534827628530496329220096 S2
    M2 1 1152921504606846976) (not (and (= S2 (- 1)) (= M2 8743001860468548))))
  (jdouble.near (- 39374979920905704174441741161553) 5192296858534827628530496329220096 S2 M2 1
    2305843009213693952)
  (jdouble.near (- 39374979920905704174441741161553) 5192296858534827628530496329220096 S2 M2 1
    576460752303423488))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: x*(e-c)-c = 0xbf7f0fb6_c029ef5f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 2837266318392195872802620723353299054079030329344)
  374144419156711147060143317175368453031918731001856 (- 1) 8743001860468575 1 1152921504606846976))
  (or (and (jdouble.near (- 2837266318392195872802620723353299054079030329344)
    374144419156711147060143317175368453031918731001856 S2 M2 1 1152921504606846976) (not (and (= S2
    (- 1)) (= M2 8743001860468575))))
  (jdouble.near (- 2837266318392195872802620723353299054079030329344)
    374144419156711147060143317175368453031918731001856 S2 M2 1 2305843009213693952)
  (jdouble.near (- 2837266318392195872802620723353299054079030329344)
    374144419156711147060143317175368453031918731001856 S2 M2 1 576460752303423488))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: e -= hxs = 0xbfb150da_6a591c85: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 11238618001994054962717697167589376)
  166153499473114484112975882535043072 (- 1) 4873973621398661 1 72057594037927936))
  (or (and (jdouble.near (- 11238618001994054962717697167589376)
    166153499473114484112975882535043072 S2 M2 1 72057594037927936) (not (and (= S2 (- 1)) (= M2
    4873973621398661))))
  (jdouble.near (- 11238618001994054962717697167589376) 166153499473114484112975882535043072 S2 M2 1
    144115188075855872)
  (jdouble.near (- 11238618001994054962717697167589376) 166153499473114484112975882535043072 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: x-e = 0x3fda8279_988abb00: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 537679942919846892878932735950848 1298074214633706907132624082305024
  1 7461808156359424 1 18014398509481984))
  (or (and (jdouble.near 537679942919846892878932735950848 1298074214633706907132624082305024 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 7461808156359424))))
  (jdouble.near 537679942919846892878932735950848 1298074214633706907132624082305024 S2 M2 1
    36028797018963968)
  (jdouble.near 537679942919846892878932735950848 1298074214633706907132624082305024 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: 0.5*(x-e) = 0x3fca8279_988abb00: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 33604996432490429679033389154304 162259276829213363391578010288128 1
  7461808156359424 1 36028797018963968))
  (or (and (jdouble.near 33604996432490429679033389154304 162259276829213363391578010288128 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 7461808156359424))))
  (jdouble.near 33604996432490429679033389154304 162259276829213363391578010288128 S2 M2 1
    72057594037927936)
  (jdouble.near 33604996432490429679033389154304 162259276829213363391578010288128 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = -1 at 0xbfd62e42_ffffffff: 0.5*(x-e)-0.5 = 0xbfd2bec3_33baa280: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 95049283964232504033511231979520)
  324518553658426726783156020576256 (- 1) 5276295176561280 1 18014398509481984))
  (or (and (jdouble.near (- 95049283964232504033511231979520) 324518553658426726783156020576256 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 5276295176561280))))
  (jdouble.near (- 95049283964232504033511231979520) 324518553658426726783156020576256 S2 M2 1
    36028797018963968)
  (jdouble.near (- 95049283964232504033511231979520) 324518553658426726783156020576256 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "not a witness: at 0xbfd62e42_ffffffff both give 0xbfd2bec3_33baa280: expect unsat")
(push 1)
(assert (not (= (- 4624424121760308608) (- 4624424121760308608))))
(check-sat)
(pop 1)
