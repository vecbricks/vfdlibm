; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License. You may obtain a copy of the
; License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
; is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
; express or implied. See the License for the specific language governing permissions and
; limitations under the License.

; Round to nearest commutes with negation, and exp's two quotients are one (README.md).
;
; e_exp.c (reference/fdlibm-5.3) finishes with one of two quotients of the same operands, a and
; c, both doubles, with a = x*c rounded:
;
;   k == 0:  one-((x*c)/(c-2.0)-x)              a / (c - 2.0)
;   k != 0:  y = one-((lo-(x*c)/(2.0-c))-hi)    a / (2.0 - c)
;
; The claim: for every double a and every double c with -2 <= c <= 1, Java's a / (c - 2.0) has
; the bits of -(a / (2.0 - c)), unless the quotient rounds to zero. So a port may compute either
; quotient from the other with a negation, which flips the sign bit only (JLS 15.15.4). It is
; four steps, each a check below or the arithmetic of rationals:
;
;   1. fl(c - 2) = -fl(2 - c), and that double is negative, normal and in [-4, -1]: section B;
;   2. a / -b = -(a / b) exactly, for b != 0, so fl(a / -b) = fl(-(a / b)): rationals;
;   3. fl(-v) = -fl(v) for every rational v whose double is normal or subnormal: section A;
;   4. the S, M and E of a double name its bits one to one, so equal S, M, E are equal bits and
;      -y is y with its sign bit flipped: section C.
;
; What is proven and what is not:
;   - Section A holds for every rational and every binade at once: the scale 2^E is a symbolic
;     positive rational sn / sd, which jdouble.near does not need to be a power of two, so one
;     check covers the 2046 normal binades, and one more the subnormals.
;   - That jdouble.near names one double for a value - the rounding is a function - is the
;     prelude's correctness, pinned by java_check.smt2, which refuses both neighbours of every
;     double it checks; it is not proven in general here. Stated over a symbolic denominator it
;     multiplies two unknowns, M * b, and both solvers answer unknown to it in a minute.
;   - Zero is not in the prelude. A quotient that rounds to zero, |a / b| <= 2^-1075, takes the
;     sign of the exact quotient under IEEE 754 6.3, which is the same symmetry by the sign rule
;     rather than by a rounding; it is stated here, not checked. On exp's k == 0 path
;     |x| >= 2^-28 (the 0x3e300000 filter) keeps |a| near x^2 >= 2^-56 and the quotient normal;
;     on the k != 0 path the reduced x = hi - lo has no floor that e_exp.c states, and none is
;     established here, so that path relies on the zero case's sign rule as well.
;   - The domain -2 <= c <= 1 is wider than exp needs and is what section B checks. That exp's c
;     lies in it is not checked here: sampled, |x| <= 0.5 ln 2 gives c in about [-0.367, 0.327],
;     so 2.0 - c in about [1.673, 2.367] and |a| <= 0.128.
;
; A double c is an integer multiple of 2^-1074, normal, subnormal or zero alike, so section B
; writes c = n / 2^1074 for an integer n, and 2 - c = (2^1075 - n) / 2^1074 exactly: each check
; is linear, and holds for every rational of that form in the domain, doubles among them.
;
; Written by hand. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line. The
; logic is ALL, not QF_NIA: under QF_NIA cvc5 answers unknown to two of the satisfiable checks
; and to section C's read-back, and under ALL it decides the file in a tenth of a second.

(set-logic ALL)

; Section A: fl(-v) = -fl(v), for every rational v = a / b with b > 0 and every binade.

(echo "a / b rounds to S M 2^E only if -a / b rounds to -S M 2^E, any binade: expect unsat")
(push 1)
(declare-const a Int) (declare-const b Int) (declare-const S Int) (declare-const M Int)
(declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 b) (< 0 sn) (< 0 sd)))
(assert (jdouble.near a b S M sn sd))
(assert (not (jdouble.near (- a) b (- S) M sn sd)))
(check-sat)
(pop 1)

(echo "a / b rounds to the subnormal S M 2^-1074 only if -a / b rounds to -S M: expect unsat")
(push 1)
(declare-const a Int) (declare-const b Int) (declare-const S Int) (declare-const M Int)
(assert (< 0 b))
(assert (jdouble.near.sub a b S M))
(assert (not (jdouble.near.sub (- a) b (- S) M)))
(check-sat)
(pop 1)

; Section A's claim is not vacuous, and it rests on the half step toward zero at the bottom of
; a binade, mirrored for negatives. Each check below is in binade 2^0, E = -52; over a
; symbolic scale cvc5 answers unknown to a satisfiable one.

(echo "a / 2^60 in binade 2^0 can round to a negative double: expect sat")
(push 1)
(declare-const a Int) (declare-const M Int)
(assert (jdouble.near a 1152921504606846976 (- 1) M 1 4503599627370496))
(check-sat)
(pop 1)

(echo "refuted: -a / b rounds to the negated double with M moved by one: expect sat")
(push 1)
(declare-const a Int) (declare-const b Int) (declare-const S Int) (declare-const M Int)
(assert (< 0 b))
(assert (jdouble.near a b S M 1 4503599627370496))
(assert (not (jdouble.near (- a) b (- S) (+ M 1) 1 4503599627370496)))
(check-sat)
(pop 1)

; The rounding with the negative side's half step dropped: the next double toward zero from
; -2^52 * 2^E taken a whole step away, as if the doubles were spaced evenly across a binade's
; bottom on that side only.
(define-fun near.lopsided ((a Int) (b Int) (S Int) (M Int) (sn Int) (sd Int)) Bool
  (ite (= S 1) (jdouble.near a b S M sn sd)
    (let ((A (* 4 a sd))
          (lo (* (- (* 4 S M) 2) b sn))
          (up (* (+ (* 4 S M) 2) b sn)))
      (and (jdouble.M.in M)
           (ite (= (mod M 2) 0) (and (<= lo A) (<= A up)) (and (< lo A) (< A up)))))))

(echo "refuted: the symmetry without the negative side's half step: expect sat")
(push 1)
(declare-const a Int) (declare-const M Int)
(assert (near.lopsided (- a) 1152921504606846976 (- 1) M 1 4503599627370496))
(assert (not (jdouble.near a 1152921504606846976 1 M 1 4503599627370496)))
(check-sat)
(pop 1)

; Section B: for every double c with -2 <= c <= 1, fl(c - 2) = -fl(2 - c), negative, normal and
; in [-4, -1]. c = n / 2^1074. 2 - c lies in [1, 4], and since 1 and 4 are doubles and rounding
; is monotone, fl(2 - c) does too: in binade 2^0 or 2^1, or 4 at the bottom of binade 2^2. The
; first check shows the exact values lie in those binades' span, [2^0, 2^3); the next three
; give the claim in each binade.

(echo "for -2 <= c <= 1 the exact 2 - c lies in [2^0, 2^3), binades 2^0 to 2^2: expect unsat")
(push 1)
(declare-const n Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (not (and (<= jdouble.SUB (- (* 2 jdouble.SUB) n))
                  (< (- (* 2 jdouble.SUB) n) (* 8 jdouble.SUB)))))
(check-sat)
(pop 1)

(echo "-2 <= c <= 1: fl(2 - c) in binade 2^0 is positive, fl(c - 2) its negation: expect unsat")
(push 1)
(declare-const n Int) (declare-const S Int) (declare-const M Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (jdouble.near (- (* 2 jdouble.SUB) n) jdouble.SUB S M 1 4503599627370496))
(assert (not (and (= S 1)
  (jdouble.near (- n (* 2 jdouble.SUB)) jdouble.SUB (- 1) M 1 4503599627370496))))
(check-sat)
(pop 1)

(echo "-2 <= c <= 1: fl(2 - c) in binade 2^1 is positive, fl(c - 2) its negation: expect unsat")
(push 1)
(declare-const n Int) (declare-const S Int) (declare-const M Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (jdouble.near (- (* 2 jdouble.SUB) n) jdouble.SUB S M 1 2251799813685248))
(assert (not (and (= S 1)
  (jdouble.near (- n (* 2 jdouble.SUB)) jdouble.SUB (- 1) M 1 2251799813685248))))
(check-sat)
(pop 1)

(echo "-2 <= c <= 1: fl(2 - c) in binade 2^2 is positive, fl(c - 2) its negation: expect unsat")
(push 1)
(declare-const n Int) (declare-const S Int) (declare-const M Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (jdouble.near (- (* 2 jdouble.SUB) n) jdouble.SUB S M 1 1125899906842624))
(assert (not (and (= S 1)
  (jdouble.near (- n (* 2 jdouble.SUB)) jdouble.SUB (- 1) M 1 1125899906842624))))
(check-sat)
(pop 1)

; Each of the three binades is reached from the domain, so no check above holds for want of a
; value; the binade 2^2 only by c = -2, where 2 - c = 4 exactly.

(echo "some c in [-2, 1] has fl(2 - c) in binade 2^0: expect sat")
(push 1)
(declare-const n Int) (declare-const M Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (jdouble.near (- (* 2 jdouble.SUB) n) jdouble.SUB 1 M 1 4503599627370496))
(check-sat)
(pop 1)

(echo "some c in [-2, 1] has fl(2 - c) in binade 2^1: expect sat")
(push 1)
(declare-const n Int) (declare-const M Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (jdouble.near (- (* 2 jdouble.SUB) n) jdouble.SUB 1 M 1 2251799813685248))
(check-sat)
(pop 1)

(echo "some c in [-2, 1] has fl(2 - c) in binade 2^2: expect sat")
(push 1)
(declare-const n Int) (declare-const M Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (jdouble.near (- (* 2 jdouble.SUB) n) jdouble.SUB 1 M 1 1125899906842624))
(check-sat)
(pop 1)

; The constant changed by one: with 2.0 replaced by its next double up, 2 + 2^-51, the claim
; fl(c - 2') = -fl(2 - c) is refuted - some c in the domain tells the two apart.

(echo "refuted: fl(c - (2 + 2^-51)) = -fl(2 - c) in binade 2^1: expect sat")
(push 1)
(declare-const n Int) (declare-const M Int)
(assert (and (<= (* (- 2) jdouble.SUB) n) (<= n jdouble.SUB)))
(assert (jdouble.near (- (* 2 jdouble.SUB) n) jdouble.SUB 1 M 1 2251799813685248))
(assert (not (jdouble.near (- n (+ (* 2 jdouble.SUB) (div jdouble.SUB 2251799813685248)))
  jdouble.SUB (- 1) M 1 2251799813685248)))
(check-sat)
(pop 1)

; Section C: a normal double's S, M and E name its bits one to one, so the doubles above that
; agree in S, M and E agree in every bit, and -y, the same M and E with S negated, is y with
; the sign bit flipped, Java's unary minus on a double (JLS 15.15.4).

(echo "a normal double's bits read back give its S, M and E: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int) (declare-const E Int)
(assert (and (jdouble.S.in S) (jdouble.M.in M) (<= (- 1074) E) (<= E 971)))
(define-fun x () Int (jdouble.bits S M E))
(assert (not (and (jlong.in x) (= (jdouble.fromBits.S x) S) (= (jdouble.fromBits.M x) M)
                  (= (jdouble.fromBits.E x) E))))
(check-sat)
(pop 1)

(echo "negating S flips the sign bit and no other: expect unsat")
(push 1)
(declare-const M Int) (declare-const E Int)
(assert (and (jdouble.M.in M) (<= (- 1074) E) (<= E 971)))
(assert (not (= (jdouble.bits (- 1) M E) (+ (jdouble.bits 1 M E) jlong.MIN))))
(check-sat)
(pop 1)
