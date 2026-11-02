; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; On exp's near band the far form is the near form (PROOFS.md, L2).
;
; e_exp.c (reference/fdlibm-5.3) reduces 0.5 ln2 < |x| < 1.5 ln2, hx in [0x3fd62e43,
; 0x3ff0a2b1], by its near form, k = 1 - 2*xsb, hi = x - ln2HI[xsb], lo = ln2LO[xsb]
; (line 130), and the port (Exp) by the far form for every lane: k = (int) s with
; s = fl(fl(invln2 * x) + halF[xsb]), t = k, hi = x - fl(t * ln2HI[0]),
; lo = fl(t * ln2LO[0]). The band is X1 <= |x| <= X2, X1 = 0x3fd62e43_00000000 and
; X2 = 0x3ff0a2b1_ffffffff, rendered from Exp's words; invln2, ln2HI and ln2LO from Exp.
;
; What is proven, for every double of the band of either sign, with no appeal to
; monotonicity: x is S * Mx * 2^(j - 52) in one of the band's binades, with Mx symbolic
; and bounded by X1 and X2 in the end binades; invln2 * x is the exact rational
; S * M(invln2) * Mx / 2^n, which rounds into the binades listed for it and no other next
; to them; then s, the sum with halF[xsb] = S / 2 (exp_half_sign.smt2), rounds into binade
; 2^0 with sign S and into no binade next to it, so (int) s = S, the near form's k. Every
; binade pair is reached by some x, or the check says it is not. And t * ln2HI[0] for
; t = +-1 is exact, the double +-ln2HI[0], whose bits for t = -1 are ln2HI[1]'s, and
; likewise for ln2LO: so hi and lo are the near form's operations on the same operands.
;
; The unsat checks hold for every double jdouble.near admits for a value, so they need
; no proof that it admits only one; the sat checks at the band's ends name the binade a
; value rounds into, and rest for that on java_check.smt2, which refuses both neighbours
; of every double it checks.
;
; What is not: that a value rounds to no double in a binade further off than the next
; one rests on the arithmetic of rationals (a double of binade j is rounded to only from
; within half its step of [2^j, 2^(j+1)), and the values here lie in [0.5, 2)); and (int)
; truncating toward zero (JLS 5.1.3, D2I), I2D of +-1 being exact and both forms then
; sharing every later operation are the code's text.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

; x > 0 in binade 2^-2: Mx in [6243314785320960, 9007199254740991].

(echo "x > 0, binade 2^-2: invln2 * x rounds only into binades 2^-1 to 2^0, sign S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (or (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064 S2 M2 1
  18014398509481984)
  (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064 (- 1)
  M2 1 9007199254740992)
  (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064 (-
  1) M2 1 4503599627370496)
  (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064
  S2 M2 1 2251799813685248)))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-2, invln2 * x in 2^-1: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (or (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 (- 1) M2 1 4503599627370496)
  (and (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496) (not (= (div
    M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-2, invln2 * x in 2^-1: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-2, invln2 * x in 2^0: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064 1 P 1
  4503599627370496))
(assert (or (jdouble.near (+ P 2251799813685248) 4503599627370496 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 2251799813685248) 4503599627370496 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 2251799813685248) 4503599627370496 (- 1) M2 1 4503599627370496)
  (and (jdouble.near (+ P 2251799813685248) 4503599627370496 1 M2 1 4503599627370496) (not (= (div
    M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-2, invln2 * x in 2^0: not reached: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 81129638414606681695789005144064 1 P 1
  4503599627370496))
(check-sat)
(pop 1)

; x > 0 in binade 2^-1: Mx in [4503599627370496, 9007199254740991].

(echo "x > 0, binade 2^-1: invln2 * x rounds only into binades 2^-1 to 2^0, sign S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (or (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032 S2 M2 1
  18014398509481984)
  (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032 (- 1)
  M2 1 9007199254740992)
  (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032 (-
  1) M2 1 4503599627370496)
  (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032
  S2 M2 1 2251799813685248)))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-1, invln2 * x in 2^-1: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032 1 P 1
  9007199254740992))
(assert (or (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 (- 1) M2 1 4503599627370496)
  (and (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496) (not (= (div
    M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-1, invln2 * x in 2^-1: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032 1 P 1
  9007199254740992))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-1, invln2 * x in 2^0: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032 1 P 1
  4503599627370496))
(assert (or (jdouble.near (+ P 2251799813685248) 4503599627370496 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 2251799813685248) 4503599627370496 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 2251799813685248) 4503599627370496 (- 1) M2 1 4503599627370496)
  (and (jdouble.near (+ P 2251799813685248) 4503599627370496 1 M2 1 4503599627370496) (not (= (div
    M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x > 0 in 2^-1, invln2 * x in 2^0: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* 6497320848556798 Mx) 40564819207303340847894502572032 1 P 1
  4503599627370496))
(check-sat)
(pop 1)

; x > 0 in binade 2^0: Mx in [4503599627370496, 4682485015248895].

(echo "x > 0, binade 2^0: invln2 * x rounds only into binades 2^-1 to 2^0, sign S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (or (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016 S2 M2 1
  18014398509481984)
  (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016 (- 1)
  M2 1 9007199254740992)
  (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016 (-
  1) M2 1 4503599627370496)
  (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016
  S2 M2 1 2251799813685248)))
(check-sat)
(pop 1)

(echo "x > 0 in 2^0, invln2 * x in 2^-1: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016 1 P 1
  9007199254740992))
(assert (or (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 (- 1) M2 1 4503599627370496)
  (and (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496) (not (= (div
    M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x > 0 in 2^0, invln2 * x in 2^-1: not reached: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016 1 P 1
  9007199254740992))
(check-sat)
(pop 1)

(echo "x > 0 in 2^0, invln2 * x in 2^0: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016 1 P 1
  4503599627370496))
(assert (or (jdouble.near (+ P 2251799813685248) 4503599627370496 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 2251799813685248) 4503599627370496 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 2251799813685248) 4503599627370496 (- 1) M2 1 4503599627370496)
  (and (jdouble.near (+ P 2251799813685248) 4503599627370496 1 M2 1 4503599627370496) (not (= (div
    M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x > 0 in 2^0, invln2 * x in 2^0: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* 6497320848556798 Mx) 20282409603651670423947251286016 1 P 1
  4503599627370496))
(check-sat)
(pop 1)

; x < 0 in binade 2^-2: Mx in [6243314785320960, 9007199254740991].

(echo "x < 0, binade 2^-2: invln2 * x rounds only into binades 2^-1 to 2^0, sign S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (or (jdouble.near (* (- 6497320848556798) Mx) 81129638414606681695789005144064 S2 M2 1
  18014398509481984)
  (jdouble.near (* (- 6497320848556798) Mx) 81129638414606681695789005144064 1
  M2 1 9007199254740992)
  (jdouble.near (* (- 6497320848556798) Mx)
  81129638414606681695789005144064 1 M2 1 4503599627370496)
  (jdouble.near (* (- 6497320848556798)
  Mx) 81129638414606681695789005144064 S2 M2 1 2251799813685248)))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-2, invln2 * x in 2^-1: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 81129638414606681695789005144064 (- 1) P 1
  9007199254740992))
(assert (or (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 1 M2 1 4503599627370496)
  (and (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 (- 1) M2 1 4503599627370496) (not
    (= (div M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-2, invln2 * x in 2^-1: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 81129638414606681695789005144064 (- 1) P 1
  9007199254740992))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-2, invln2 * x in 2^0: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 81129638414606681695789005144064 (- 1) P 1
  4503599627370496))
(assert (or (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 1 M2 1 4503599627370496)
  (and (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 (- 1) M2 1 4503599627370496) (not
    (= (div M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-2, invln2 * x in 2^0: not reached: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 6243314785320960 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 81129638414606681695789005144064 (- 1) P 1
  4503599627370496))
(check-sat)
(pop 1)

; x < 0 in binade 2^-1: Mx in [4503599627370496, 9007199254740991].

(echo "x < 0, binade 2^-1: invln2 * x rounds only into binades 2^-1 to 2^0, sign S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (or (jdouble.near (* (- 6497320848556798) Mx) 40564819207303340847894502572032 S2 M2 1
  18014398509481984)
  (jdouble.near (* (- 6497320848556798) Mx) 40564819207303340847894502572032 1
  M2 1 9007199254740992)
  (jdouble.near (* (- 6497320848556798) Mx)
  40564819207303340847894502572032 1 M2 1 4503599627370496)
  (jdouble.near (* (- 6497320848556798)
  Mx) 40564819207303340847894502572032 S2 M2 1 2251799813685248)))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-1, invln2 * x in 2^-1: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 40564819207303340847894502572032 (- 1) P 1
  9007199254740992))
(assert (or (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 1 M2 1 4503599627370496)
  (and (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 (- 1) M2 1 4503599627370496) (not
    (= (div M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-1, invln2 * x in 2^-1: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 40564819207303340847894502572032 (- 1) P 1
  9007199254740992))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-1, invln2 * x in 2^0: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 40564819207303340847894502572032 (- 1) P 1
  4503599627370496))
(assert (or (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 1 M2 1 4503599627370496)
  (and (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 (- 1) M2 1 4503599627370496) (not
    (= (div M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x < 0 in 2^-1, invln2 * x in 2^0: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 9007199254740991)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 40564819207303340847894502572032 (- 1) P 1
  4503599627370496))
(check-sat)
(pop 1)

; x < 0 in binade 2^0: Mx in [4503599627370496, 4682485015248895].

(echo "x < 0, binade 2^0: invln2 * x rounds only into binades 2^-1 to 2^0, sign S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (or (jdouble.near (* (- 6497320848556798) Mx) 20282409603651670423947251286016 S2 M2 1
  18014398509481984)
  (jdouble.near (* (- 6497320848556798) Mx) 20282409603651670423947251286016 1
  M2 1 9007199254740992)
  (jdouble.near (* (- 6497320848556798) Mx)
  20282409603651670423947251286016 1 M2 1 4503599627370496)
  (jdouble.near (* (- 6497320848556798)
  Mx) 20282409603651670423947251286016 S2 M2 1 2251799813685248)))
(check-sat)
(pop 1)

(echo "x < 0 in 2^0, invln2 * x in 2^-1: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 20282409603651670423947251286016 (- 1) P 1
  9007199254740992))
(assert (or (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 1 M2 1 4503599627370496)
  (and (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 (- 1) M2 1 4503599627370496) (not
    (= (div M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x < 0 in 2^0, invln2 * x in 2^-1: not reached: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 20282409603651670423947251286016 (- 1) P 1
  9007199254740992))
(check-sat)
(pop 1)

(echo "x < 0 in 2^0, invln2 * x in 2^0: s is in 2^0, sign S, and (int) s = S: expect unsat")
(push 1)
(declare-const Mx Int) (declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 20282409603651670423947251286016 (- 1) P 1
  4503599627370496))
(assert (or (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 S2 M2 1 9007199254740992)
  (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 S2 M2 1 2251799813685248)
  (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 1 M2 1 4503599627370496)
  (and (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 (- 1) M2 1 4503599627370496) (not
    (= (div M2 4503599627370496) 1)))))
(check-sat)
(pop 1)

(echo "x < 0 in 2^0, invln2 * x in 2^0: reached: expect sat")
(push 1)
(declare-const Mx Int) (declare-const P Int)
(assert (and (<= 4503599627370496 Mx) (<= Mx 4682485015248895)))
(assert (jdouble.near (* (- 6497320848556798) Mx) 20282409603651670423947251286016 (- 1) P 1
  4503599627370496))
(check-sat)
(pop 1)

; The band's ends are its own: at both boundary doubles, either sign, the chain above is
; satisfiable with s in binade 2^0. Then the band widened by a word at either end is
; refuted: at the next word down s rounds below 1, so k = 0, and at the top of the next
; word up it rounds to 2 or more, so k = 2 - neither the near form's k.

(echo "X1, the band's bottom +: s rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320960) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "X1, the band's bottom -: s rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 6243314785320960) 81129638414606681695789005144064 (-
  1) P 1 9007199254740992))
(assert (jdouble.near (- (+ P 4503599627370496)) 9007199254740992 (- 1) M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "X2, the band's top +: s rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 4682485015248895) 20282409603651670423947251286016 1 P 1
  4503599627370496))
(assert (jdouble.near (+ P 2251799813685248) 4503599627370496 1 M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "X2, the band's top -: s rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 4682485015248895) 20282409603651670423947251286016 (-
  1) P 1 4503599627370496))
(assert (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 (- 1) M2 1 4503599627370496))
(check-sat)
(pop 1)

(echo "refuted: the word below the band, 0x3fd62e42_00000000, +: s rounds into 2^-1: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243310490353664) 81129638414606681695789005144064 1 P 1
  18014398509481984))
(assert (jdouble.near (+ P 9007199254740992) 18014398509481984 1 M2 1 9007199254740992))
(check-sat)
(pop 1)

(echo "refuted: the word below the band, 0x3fd62e42_00000000, -: s rounds into 2^-1: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 6243310490353664) 81129638414606681695789005144064 (-
  1) P 1 18014398509481984))
(assert (jdouble.near (- (+ P 9007199254740992)) 18014398509481984 (- 1) M2 1 9007199254740992))
(check-sat)
(pop 1)

(echo "refuted: the word above, 0x3ff0a2b2_ffffffff, +: s rounds into 2^1: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 4682489310216191) 20282409603651670423947251286016 1 P 1
  4503599627370496))
(assert (jdouble.near (+ P 2251799813685248) 4503599627370496 1 M2 1 2251799813685248))
(check-sat)
(pop 1)

(echo "refuted: the word above, 0x3ff0a2b2_ffffffff, -: s rounds into 2^1: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* (- 6497320848556798) 4682489310216191) 20282409603651670423947251286016 (-
  1) P 1 4503599627370496))
(assert (jdouble.near (- (+ P 2251799813685248)) 4503599627370496 (- 1) M2 1 2251799813685248))
(check-sat)
(pop 1)

; t * ln2HI[0] and t * ln2LO[0] for t = +-1: the exact product is the double +-c itself,
; which rounds to no other double, and the one t = -1 gives has the bits of the source's
; ln2HI[1] or ln2LO[1] (Exp's LN2HI_1, LN2LO_1).

(echo "+1 * ln2HI[0] rounds to itself only: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (and (jdouble.near 6243314766446592 9007199254740992 S2 M2 1 9007199254740992) (not (and
  (= S2 1) (= M2 6243314766446592))))
  (jdouble.near 6243314766446592 9007199254740992 S2 M2 1
  18014398509481984)
  (jdouble.near 6243314766446592 9007199254740992 S2 M2 1 4503599627370496)))
(check-sat)
(pop 1)

(echo "-1 * ln2HI[0] rounds to its negation only: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (and (jdouble.near (- 6243314766446592) 9007199254740992 S2 M2 1 9007199254740992) (not
  (and (= S2 (- 1)) (= M2 6243314766446592))))
  (jdouble.near (- 6243314766446592) 9007199254740992
  S2 M2 1 18014398509481984)
  (jdouble.near (- 6243314766446592) 9007199254740992 S2 M2 1
  4503599627370496)))
(check-sat)
(pop 1)

(echo "ln2HI[1] = 0xbfe62e42_fee00000 is -ln2HI[0]: its S, M, E: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S (- 4618953502543052800)) (- 1))
  (= (jdouble.fromBits.M (- 4618953502543052800)) 6243314766446592)
  (= (jdouble.fromBits.E (- 4618953502543052800)) (- 53)))))
(check-sat)
(pop 1)

(echo "refuted: ln2HI[1] with M one more: expect sat")
(push 1)
(assert (not (= (jdouble.fromBits.M (- 4618953502543052799)) 6243314766446592)))
(check-sat)
(pop 1)

(echo "+1 * ln2LO[0] rounds to itself only: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (and (jdouble.near 7382048951581814 38685626227668133590597632 S2 M2 1
  38685626227668133590597632) (not (and (= S2 1) (= M2 7382048951581814))))
  (jdouble.near
  7382048951581814 38685626227668133590597632 S2 M2 1 77371252455336267181195264)
  (jdouble.near
  7382048951581814 38685626227668133590597632 S2 M2 1 19342813113834066795298816)))
(check-sat)
(pop 1)

(echo "-1 * ln2LO[0] rounds to its negation only: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (and (jdouble.near (- 7382048951581814) 38685626227668133590597632 S2 M2 1
  38685626227668133590597632) (not (and (= S2 (- 1)) (= M2 7382048951581814))))
  (jdouble.near (-
  7382048951581814) 38685626227668133590597632 S2 M2 1 77371252455336267181195264)
  (jdouble.near
  (- 7382048951581814) 38685626227668133590597632 S2 M2 1 19342813113834066795298816)))
(check-sat)
(pop 1)

(echo "ln2LO[1] = 0xbdea39ef_35793c76 is -ln2LO[0]: its S, M, E: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S (- 4761929956433773450)) (- 1))
  (= (jdouble.fromBits.M (- 4761929956433773450)) 7382048951581814)
  (= (jdouble.fromBits.E (- 4761929956433773450)) (- 85)))))
(check-sat)
(pop 1)

(echo "refuted: ln2LO[1] with M one more: expect sat")
(push 1)
(assert (not (= (jdouble.fromBits.M (- 4761929956433773449)) 7382048951581814)))
(check-sat)
(pop 1)
