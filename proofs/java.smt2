; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License. You may obtain a copy of the
; License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
; is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
; express or implied. See the License for the specific language governing permissions and
; limitations under the License.
;
; Adapted from vecbricks/varka's sql/varka/proofs/java.smt2, the prelude of its proofs.

; Java's int and long arithmetic and its doubles over SMT-LIB's integers: the prelude every proof
; in this directory is stated in (README.md).
;
; A Java int or long is an SMT-LIB Int inside its type's range, and each operator below is
; defined the way the Java Language Specification defines it, in mathematical integers, with the
; section cited. That is the point of stating them here once: an SMT operator whose name matches
; Java's need not mean what Java means - a bit-vector shift does not mask its count - and a proof
; that leaned on one would prove the wrong thing. java_check.smt2 holds every definition to the
; JVM's own results over boundary operands.
;
; Why integers, and not bit-vectors or SMT-LIB's floating-point theory: a solver bit-blasts both,
; and Varka measured the difference - a multiply-high division timed out over bit-vectors under
; every solver it tried, and a double division by 1000 took one of them eight minutes in
; floating-point theory, where the same statements over integers take milliseconds.
;
; dev/prove.sh inserts this file after each proof's (set-logic ...) line, since SMT-LIB has no
; include. It declares no logic, asserts nothing and checks nothing.

; The ranges (JLS 4.2.1).
(define-fun jint.MIN () Int (- 2147483648))
(define-fun jint.MAX () Int 2147483647)
(define-fun jlong.MIN () Int (- 9223372036854775808))
(define-fun jlong.MAX () Int 9223372036854775807)
(define-fun jint.in ((x Int)) Bool (and (<= jint.MIN x) (<= x jint.MAX)))
(define-fun jlong.in ((x Int)) Bool (and (<= jlong.MIN x) (<= x jlong.MAX)))

; An overflowing sum or product is the low-order bits of the mathematical one in two's complement
; (JLS 15.18.2, 15.17.1): the value in range that is congruent to it modulo 2^32 or 2^64.
(define-fun jint.wrap ((v Int)) Int (- (mod (+ v 2147483648) 4294967296) 2147483648))
(define-fun jlong.wrap ((v Int)) Int
  (- (mod (+ v 9223372036854775808) 18446744073709551616) 9223372036854775808))

; I2L sign-extends, which keeps the value (JLS 5.1.2); L2I "discards all but the n lowest order
; bits" (JLS 5.1.3). fdlibm's __HI(x) is the high int of the raw bits: l2i of a shift by 32.
(define-fun i2l ((x Int)) Int x)
(define-fun l2i ((x Int)) Int (jint.wrap x))

; +, -, * (JLS 15.18.2, 15.17.1), unary - (15.15.4) and ~, where "~x equals (-x)-1" (15.15.5).
(define-fun jint.add ((x Int) (y Int)) Int (jint.wrap (+ x y)))
(define-fun jint.sub ((x Int) (y Int)) Int (jint.wrap (- x y)))
(define-fun jint.mul ((x Int) (y Int)) Int (jint.wrap (* x y)))
(define-fun jint.neg ((x Int)) Int (jint.wrap (- x)))
(define-fun jint.not ((x Int)) Int (- (- x) 1))
(define-fun jlong.add ((x Int) (y Int)) Int (jlong.wrap (+ x y)))
(define-fun jlong.sub ((x Int) (y Int)) Int (jlong.wrap (- x y)))
(define-fun jlong.mul ((x Int) (y Int)) Int (jlong.wrap (* x y)))
(define-fun jlong.neg ((x Int)) Int (jlong.wrap (- x)))
(define-fun jlong.not ((x Int)) Int (- (- x) 1))

; Shifts (JLS 15.19). Only the five lowest-order bits of an int shift's count are used, and the six
; lowest of a long's: the count modulo 32 or 64. "n << s" is multiplication by 2^s, "n >> s" is
; floor(n / 2^s), and "n >>> s" is n >> s for a non-negative n and (n >> s) + (2 << ~s), or
; (2L << ~s), for a negative one.
(define-fun pow2 ((k Int)) Int
  (ite (= k 0) 1
  (ite (= k 1) 2
  (ite (= k 2) 4
  (ite (= k 3) 8
  (ite (= k 4) 16
  (ite (= k 5) 32
  (ite (= k 6) 64
  (ite (= k 7) 128
  (ite (= k 8) 256
  (ite (= k 9) 512
  (ite (= k 10) 1024
  (ite (= k 11) 2048
  (ite (= k 12) 4096
  (ite (= k 13) 8192
  (ite (= k 14) 16384
  (ite (= k 15) 32768
  (ite (= k 16) 65536
  (ite (= k 17) 131072
  (ite (= k 18) 262144
  (ite (= k 19) 524288
  (ite (= k 20) 1048576
  (ite (= k 21) 2097152
  (ite (= k 22) 4194304
  (ite (= k 23) 8388608
  (ite (= k 24) 16777216
  (ite (= k 25) 33554432
  (ite (= k 26) 67108864
  (ite (= k 27) 134217728
  (ite (= k 28) 268435456
  (ite (= k 29) 536870912
  (ite (= k 30) 1073741824
  (ite (= k 31) 2147483648
  (ite (= k 32) 4294967296
  (ite (= k 33) 8589934592
  (ite (= k 34) 17179869184
  (ite (= k 35) 34359738368
  (ite (= k 36) 68719476736
  (ite (= k 37) 137438953472
  (ite (= k 38) 274877906944
  (ite (= k 39) 549755813888
  (ite (= k 40) 1099511627776
  (ite (= k 41) 2199023255552
  (ite (= k 42) 4398046511104
  (ite (= k 43) 8796093022208
  (ite (= k 44) 17592186044416
  (ite (= k 45) 35184372088832
  (ite (= k 46) 70368744177664
  (ite (= k 47) 140737488355328
  (ite (= k 48) 281474976710656
  (ite (= k 49) 562949953421312
  (ite (= k 50) 1125899906842624
  (ite (= k 51) 2251799813685248
  (ite (= k 52) 4503599627370496
  (ite (= k 53) 9007199254740992
  (ite (= k 54) 18014398509481984
  (ite (= k 55) 36028797018963968
  (ite (= k 56) 72057594037927936
  (ite (= k 57) 144115188075855872
  (ite (= k 58) 288230376151711744
  (ite (= k 59) 576460752303423488
  (ite (= k 60) 1152921504606846976
  (ite (= k 61) 2305843009213693952
  (ite (= k 62) 4611686018427387904
  9223372036854775808
  ))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))
(define-fun jint.shl ((x Int) (s Int)) Int (jint.wrap (* x (pow2 (mod s 32)))))
(define-fun jint.shr ((x Int) (s Int)) Int (div x (pow2 (mod s 32))))
(define-fun jint.ushr ((x Int) (s Int)) Int
  (ite (>= x 0) (jint.shr x s) (jint.add (jint.shr x s) (jint.shl 2 (jint.not s)))))
(define-fun jlong.shl ((x Int) (s Int)) Int (jlong.wrap (* x (pow2 (mod s 64)))))
(define-fun jlong.shr ((x Int) (s Int)) Int (div x (pow2 (mod s 64))))
(define-fun jlong.ushr ((x Int) (s Int)) Int
  (ite (>= x 0) (jlong.shr x s) (jlong.add (jlong.shr x s) (jlong.shl 2 (jlong.not s)))))

; x & (2^k - 1) keeps the low k bits of a two's-complement x, which for any int or long is x
; modulo 2^k, the non-negative residue; k2 = 2^k. The masks the ports apply to raw bits.
(define-fun jint.and.low ((x Int) (k2 Int)) Int (mod x k2))
(define-fun jlong.and.low ((x Int) (k2 Int)) Int (mod x k2))

; Doubles (JLS 4.2.3, 4.2.4). A normal double is S * M * 2^E with S = 1 or -1,
; 2^52 <= M < 2^53 and -1074 <= E <= 971; a subnormal one is S * M * 2^-1074 with 0 < M < 2^52.
; Nothing below returns a double, because 2^E for an unknown E is not linear: a proof names the
; binade instead, passing 2^E as sn / sd - sn = 2^E and sd = 1 for E >= 0, sn = 1 and sd = 2^-E
; below - with constants at every use, so what the solvers reason about stays linear. A proof
; that rounds a value it cannot place lists the binades the value can fall in, and checks that
; the list covers them.
(define-fun jdouble.M.in ((M Int)) Bool
  (and (<= 4503599627370496 M) (< M 9007199254740992)))
(define-fun jdouble.S.in ((S Int)) Bool (or (= S 1) (= S (- 1))))

; 2^1074, the scale of the lowest binade and of the subnormals, written as a product so that no
; line of this file needs its 324 digits.
(define-fun jdouble.SUB () Int
  (* 4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
     4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
     4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
     4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
     4611686018427387904 1048576))

; Round to nearest (JLS 4.2.4: "the value nearest to the infinitely precise result; if the two
; nearest representable values are equally near, the one with its least significant bit zero is
; chosen"; IEEE 754 4.3.1's roundTiesToEven): S * M * sn / sd, a normal double, is the double the
; exact a / b, b > 0, rounds to. Stated on the number line, for either sign, so that a proof
; about signs derives what it claims rather than reading it off the definition. The doubles next
; to S * M * 2^E are S * (M + 1) * 2^E, away from zero, and S * (M - 1) * 2^E, toward it, except
; at M = 2^52, where the next one toward zero is S * (2^53 - 1) * 2^(E - 1), half a step nearer -
; unless E = -1074, where it is the largest subnormal, a whole step away. So, scaled by
; 4 * b * sd / sn, a / b lies between the midpoints, 4 * S * M less 2 below and plus 2 above,
; except that the side toward zero is 1 at the bottom of a binade above the lowest; inclusive on
; a side only when M is even.
(define-fun jdouble.near ((a Int) (b Int) (S Int) (M Int) (sn Int) (sd Int)) Bool
  (let ((A (* 4 a sd))
        (dz (ite (and (= M 4503599627370496) (not (= sd (* jdouble.SUB sn)))) 1 2)))
    (let ((lo (* (- (* 4 S M) (ite (= S 1) dz 2)) b sn))
          (up (* (+ (* 4 S M) (ite (= S 1) 2 dz)) b sn)))
      (and (jdouble.S.in S) (jdouble.M.in M)
           (ite (= (mod M 2) 0) (and (<= lo A) (<= A up)) (and (< lo A) (< A up)))))))

; The subnormals: S * M * 2^-1074 with 0 < M < 2^52 is the double a / b rounds to. Their grid is
; even, 2^-1074 apart from zero to the lowest normal binade, so a / b lies within half a step,
; scaled by 2 * b * 2^1074: between 2 * S * M - 1 and 2 * S * M + 1, inclusive when M is even.
; Zero is not here: a proof whose value can round to zero states its own case.
(define-fun jdouble.near.sub ((a Int) (b Int) (S Int) (M Int)) Bool
  (let ((A (* 2 a jdouble.SUB))
        (lo (* (- (* 2 S M) 1) b))
        (up (* (+ (* 2 S M) 1) b)))
    (and (jdouble.S.in S) (< 0 M) (< M 4503599627370496)
         (ite (= (mod M 2) 0) (and (<= lo A) (<= A up)) (and (< lo A) (< A up))))))

; The positive case, Varka's jdouble.rne: M * sn / sd is the double a positive a / b rounds to.
(define-fun jdouble.rne ((a Int) (b Int) (M Int) (sn Int) (sd Int)) Bool
  (jdouble.near a b 1 M sn sd))

; A normal double's bits (Double.doubleToRawLongBits, JLS 4.2.3's binary64 format): the sign bit,
; which as a long is - 2^63, then the biased exponent E + 1075 above the 52 stored bits of
; M - 2^52. And the bits read back (Double.longBitsToDouble, DoubleVector's reinterpretation):
; S is the sign of the long, M is 2^52 plus the low 52 bits, E is the 11 bits above them less 1075.
(define-fun jdouble.bits ((S Int) (M Int) (E Int)) Int
  (+ (ite (= S 1) 0 (- 9223372036854775808))
     (* (+ E 1075) 4503599627370496) (- M 4503599627370496)))
(define-fun jdouble.fromBits.S ((b Int)) Int (ite (< b 0) (- 1) 1))
(define-fun jdouble.fromBits.M ((b Int)) Int (+ 4503599627370496 (mod b 4503599627370496)))
(define-fun jdouble.fromBits.E ((b Int)) Int (- (mod (div b 4503599627370496) 2048) 1075))
