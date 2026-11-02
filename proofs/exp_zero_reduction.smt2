; Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the
; "License"); you may not use this file except in compliance with the License. You may obtain
; a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed
; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
; ANY KIND, either express or implied. See the License for the specific language governing
; permissions and limitations under the License.
;
; exp's k == 0 path is the general tail with hi = x, lo = +0, k = 0 (PROOFS.md, L1): the
; hinges that are IEEE facts, not bounds on the polynomial.
;
; e_exp.c (reference/fdlibm-5.3) returns one-((x*c)/(c-2.0)-x) for hx in [0x3e300000,
; 0x3fd62e42] (line 147), and the port (Exp) computes the general tail there with k = 0 and
; t = +0: hi = x - fl(t*ln2HI[0]), lo = fl(t*ln2LO[0]), r = hi - lo,
; y = one-((lo-(r*c)/(2.0-c))-hi), and y's bits plus k << 52. The domain's ends,
; 0x3e300000_00000000 and 0x3fd62e42_ffffffff, are rendered from Exp's words.
;
; What is proven:
;   - Section A: a nonzero double less +0 is itself and +0 less a double q is -q, in
;     the PROOFS.md steps 1 and 3: the exact value is the double (or its negation),
;     which jdouble.near admits in every binade at once, and admits no other double in
;     its own binade or the ones next to it, for every double, either sign, of the binades
;     2^-60 to 2^-2. Those cover the domain's x, binades 2^-28 to 2^-2 (pinned below from
;     Exp's words), and q, whose binades from 2^-59 to 2^-4 follow from H2 and H3's bounds.
;   - Section B: the scale-back adds 0 << 52 = 0 to y's bits, for every long, and k = 0
;     is not below Exp's K_MIN_NORMAL, so neither the subnormal scale-back nor the finish
;     takes the lane (step 4); with k = 1 the bits would move.
;   - Section C: H1's witness. At the domain's top double the formula's
;     s = fl(fl(invln2 * x) + 0.5) rounds into binade 2^0, so (int) s = 1, not 0: the
;     high-word test, not the formula, forces k = 0 (exp_highword_compare.smt2 proves
;     that test).
;   - Section D: the domain widened by a word is refuted. At one double of the next word
;     up per sign, every rounding of the k == 0 tail and of the C's near form is
;     evaluated, and the two results differ.
;
; What is not machine-checked:
;   - H2 and H3, the bounds 0.94|x| <= |c| < 0.37 with c of x's sign, and q positive and
;     normal: they bound the polynomial's eight roundings, products of unknowns, and stay
;     the informal proof of PROOFS.md with ProofsTest's samples. So q's binades rest on
;     them, and so does step 2's hypothesis, c in [-2, 1], under which sign_symmetry.smt2
;     proves fl(c - 2.0) = -fl(2.0 - c) and the quotient's sign symmetry.
;   - fl(+0 * v) = +0 for a positive finite v, IEEE 754's sign rule for an exact zero
;     (section 6.3): zero is not in the prelude. Section A checks only that ln2HI[0] and
;     ln2LO[0] are positive and finite, which the rule needs.
;   - That D2I of +0.0 is 0 and I2D of 0 is +0.0 (JLS 5.1.3, 5.1.2), that the port puts
;     +0.0 in the cast's argument exactly on the lanes the high-word test leaves out, and
;     that the C's steps after these are the port's on the same operands: the code's text.
;   - That jdouble.near admits no double in a binade further off than the next one: the
;     arithmetic of rationals, as in sign_symmetry.smt2.
;
; Not every constant changed by one is refuted here. The domain's top word is (section
; D); invln2 and P1..P5 are not. Section C holds for a range of invln2: its margin,
; s - 1 = 1.37e-9 at the top double, is millions of invln2's ulps. Section D's chains
; evaluate whatever P1..P5 Exp holds and compare the results with no source value, so
; with a P moved by one ulp the file renders anew and passes as long as the two
; witnesses still differ. ExpTest.constantsAreTheSourcesBits pins these constants to
; the source's hex, and ProofFilesTest this file to its rendering.
;
; The logic is ALL, not QF_NIA: under QF_NIA cvc5 answers unknown to some of section A's
; per-binade checks (2^-58, 2^-55, ...), and under ALL it decides the file in a second.
; Section C's and D's checks carry their own non-vacuity: each asserts that the named
; double is not admitted, or another one is, so a jdouble.near that admitted nothing
; would make it sat.
;
; Rendered by ProofFiles (src/test/java). ProofFilesTest fails when this file differs from
; its rendering, and mvn -q test -Dtest=ProofFilesTest -Dvfdlibm.proofs.regen=true
; rewrites it. Run by dev/prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

; Section A: x - (+0) = x and +0 - q = -q. The exact difference is the double S * M * 2^E
; or its negation, a / b with a = S * M * sn and b = sd.

(echo "a double, either sign, is a double jdouble.near admits for itself, any binade: expect unsat")
(push 1)
(declare-const S Int) (declare-const M Int)
(declare-const sn Int) (declare-const sd Int)
(assert (and (< 0 sn) (< 0 sd) (jdouble.S.in S) (jdouble.M.in M)))
(assert (not (jdouble.near (* S M sn) sd S M sn sd)))
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

(echo "the domain's ends 0x3e300000_00000000, 0x3fd62e42_ffffffff are in 2^-28, 2^-2: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.E 4481081629233643520) (- 80))
  (= (jdouble.fromBits.E 4599914934703226879) (- 54)))))
(check-sat)
(pop 1)

(echo "ln2HI[0] and ln2LO[0] are positive and finite, as the zero product needs: expect unsat")
(push 1)
(assert (not (and (= (jdouble.fromBits.S 4604418534311723008) 1) (< (jdouble.fromBits.E
  4604418534311723008) 972)
  (= (jdouble.fromBits.S 4461442080421002358) 1) (< (jdouble.fromBits.E 4461442080421002358) 972))))
(check-sat)
(pop 1)

(echo "not vacuous: the double 1.5 * 2^-2 admits itself in its binade: expect sat")
(push 1)
(declare-const M2 Int)
(assert (jdouble.near 6755399441055744 18014398509481984 1 M2 1 18014398509481984))
(check-sat)
(pop 1)

(echo "refuted: a double less 2^-54, one step of its binade, is itself: expect sat")
(push 1)
(declare-const M Int) (declare-const M2 Int)
(assert (jdouble.M.in M))
(assert (jdouble.near (- M 1) 18014398509481984 1 M2 1 18014398509481984))
(assert (not (= M2 M)))
(check-sat)
(pop 1)

; Section B: the scale-back with k = 0.

(echo "y's bits plus 0 << 52 are y's bits, every long: expect unsat")
(push 1)
(declare-const y Int)
(assert (jlong.in y))
(assert (not (= (jlong.add y (jlong.shl 0 52)) y)))
(check-sat)
(pop 1)

(echo "k = 0 is not below K_MIN_NORMAL = -1021, the scale-back's plain path: expect unsat")
(push 1)
(assert (< 0 (- 1021)))
(check-sat)
(pop 1)

(echo "refuted: with k = 1 the scale-back moves some y's bits: expect sat")
(push 1)
(declare-const y Int)
(assert (jlong.in y))
(assert (not (= (jlong.add y (jlong.shl 1 52)) y)))
(check-sat)
(pop 1)

; Section C: H1's witness, at the domain's top double x: the formula's k is 1. The
; product rounds into its binade (or its neighbours, which are refused), then the sum
; with 0.5 rounds into binade 2^0, and not below it.

(echo "at the top, invln2 * x rounds into 2^-1 only, positive: expect unsat")
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

(echo "at the top, s rounds into 2^0, so (int) s = 1, and not into 2^-1, k = 0: expect unsat")
(push 1)
(declare-const P Int) (declare-const S2 Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (or (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 9007199254740992)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 S2 M2 1 2251799813685248)
  (jdouble.near (+ P 4503599627370496) 9007199254740992 (- 1) M2 1 4503599627370496)))
(check-sat)
(pop 1)

(echo "not vacuous: at the top, s rounds into 2^0: expect sat")
(push 1)
(declare-const P Int) (declare-const M2 Int)
(assert (jdouble.near (* 6497320848556798 6243314785320959) 81129638414606681695789005144064 1 P 1
  9007199254740992))
(assert (jdouble.near (+ P 4503599627370496) 9007199254740992 1 M2 1 4503599627370496))
(check-sat)
(pop 1)

; Section D: the domain widened by a word is refuted. At the next word up, 0x3fd62e43, the C
; reduces (its near form, k = +-1), and there the k == 0 tail gives another double: at
; the two first witnesses ProofsTest found, one per sign, each rounding of both
; computations is evaluated, then the C's scale-back, and the two results compared.

(echo "k = 0 tail at 0x3fd62e43_00000004: r = x - (+0) = 0x3fd62e43_00000004: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (* 1560828696330241 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4194304) (* 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4096) 1 6243314785320964 1 18014398509481984))
  (or (and (jdouble.near (* 1560828696330241 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4194304) (* 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4096) S2 M2 1 18014398509481984) (not (and (= S2 1) (= M2
    6243314785320964))))
  (jdouble.near (* 1560828696330241 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4194304) (* 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4096) S2 M2 1 36028797018963968)
  (jdouble.near (* 1560828696330241
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4194304) (* 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4096) S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: t = r*r = 0x3fbebfbe_0258900b: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979508607354798408497889296 324518553658426726783156020576256 1
  8655072105369611 1 72057594037927936))
  (or (and (jdouble.near 38978979508607354798408497889296 324518553658426726783156020576256 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 8655072105369611))))
  (jdouble.near
    38978979508607354798408497889296 324518553658426726783156020576256 S2 M2 1 144115188075855872)
 
    (jdouble.near 38978979508607354798408497889296 324518553658426726783156020576256 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: t*P5 = 0x3e355911_839aa8b1: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 54123416060657521211401465894128
  10889035741470030830827987437816582766592 1 6008906268190897 1 1208925819614629174706176))
  (or (and (jdouble.near 54123416060657521211401465894128 10889035741470030830827987437816582766592
    S2 M2 1 1208925819614629174706176) (not (and (= S2 1) (= M2 6008906268190897))))
  (jdouble.near
    54123416060657521211401465894128 10889035741470030830827987437816582766592 S2 M2 1
    2417851639229258349412352)
  (jdouble.near 54123416060657521211401465894128
    10889035741470030830827987437816582766592 S2 M2 1 604462909807314587353088))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: P4+t*P5 = 0xbebba7e8_b44ed148: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9410813252110894660487789417040627892224)
  5708990770823839524233143877797980545530986496 (- 1) 7784442270503240 1 4722366482869645213696))
  (or (and (jdouble.near (- 9410813252110894660487789417040627892224)
    5708990770823839524233143877797980545530986496 S2 M2 1 4722366482869645213696) (not (and (= S2
    (- 1)) (= M2 7784442270503240))))
  (jdouble.near (- 9410813252110894660487789417040627892224)
    5708990770823839524233143877797980545530986496 S2 M2 1 9444732965739290427392)
  (jdouble.near
    (- 9410813252110894660487789417040627892224) 5708990770823839524233143877797980545530986496 S2
    M2 1 2361183241434822606848))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: t*(P4+t*P5) = 0xbe8a9320_94fad201: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 67374909151292672328163173039640)
  340282366920938463463374607431768211456 (- 1) 7480117542179329 1 37778931862957161709568))
  (or (and (jdouble.near (- 67374909151292672328163173039640)
    340282366920938463463374607431768211456 S2 M2 1 37778931862957161709568) (not (and (= S2 (- 1))
    (= M2 7480117542179329))))
  (jdouble.near (- 67374909151292672328163173039640)
    340282366920938463463374607431768211456 S2 M2 1 75557863725914323419136)
  (jdouble.near (-
    67374909151292672328163173039640) 340282366920938463463374607431768211456 S2 M2 1
    18889465931478580854784))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: P3+t*(...) = 0x3f114921_1edb60c3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 183812682906180824568799410542964899840
  2787593149816327892691964784081045188247552 1 4865481204523203 1 73786976294838206464))
  (or (and (jdouble.near 183812682906180824568799410542964899840
    2787593149816327892691964784081045188247552 S2 M2 1 73786976294838206464) (not (and (= S2 1) (=
    M2 4865481204523203))))
  (jdouble.near 183812682906180824568799410542964899840
    2787593149816327892691964784081045188247552 S2 M2 1 147573952589676412928)
  (jdouble.near
    183812682906180824568799410542964899840 2787593149816327892691964784081045188247552 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: t*(P3+...) = 0x3ee09c22_2e16e853: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 42111090652468909484037340584033
  5316911983139663491615228241121378304 1 4675270243444819 1 590295810358705651712))
  (or (and (jdouble.near 42111090652468909484037340584033 5316911983139663491615228241121378304 S2
    M2 1 590295810358705651712) (not (and (= S2 1) (= M2 4675270243444819))))
  (jdouble.near
    42111090652468909484037340584033 5316911983139663491615228241121378304 S2 M2 1
    1180591620717411303424)
  (jdouble.near 42111090652468909484037340584033
    5316911983139663491615228241121378304 S2 M2 1 295147905179352825856))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: P2+t*(...) = 0xbf66b0cf_f490a6ab: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 3770134748699652167087472939582357504)
  1361129467683753853853498429727072845824 (- 1) 6386856695473835 1 2305843009213693952))
  (or (and (jdouble.near (- 3770134748699652167087472939582357504)
    1361129467683753853853498429727072845824 S2 M2 1 2305843009213693952) (not (and (= S2 (- 1)) (=
    M2 6386856695473835))))
  (jdouble.near (- 3770134748699652167087472939582357504)
    1361129467683753853853498429727072845824 S2 M2 1 4611686018427387904)
  (jdouble.near (-
    3770134748699652167087472939582357504) 1361129467683753853853498429727072845824 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: t*(P2+...) = 0xbf35cdb9_09fff11d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 55278705225988721555943454628185)
  166153499473114484112975882535043072 (- 1) 6137169131335965 1 18446744073709551616))
  (or (and (jdouble.near (- 55278705225988721555943454628185) 166153499473114484112975882535043072
    S2 M2 1 18446744073709551616) (not (and (= S2 (- 1)) (= M2 6137169131335965))))
  (jdouble.near
    (- 55278705225988721555943454628185) 166153499473114484112975882535043072 S2 M2 1
    36893488147419103232)
  (jdouble.near (- 55278705225988721555943454628185)
    166153499473114484112975882535043072 S2 M2 1 9223372036854775808))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: P1+t*(...) = 0x3fc54a6e_78d05545: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 110547884827838604083820853762981888
  664613997892457936451903530140172288 1 5992812844700997 1 36028797018963968))
  (or (and (jdouble.near 110547884827838604083820853762981888 664613997892457936451903530140172288
    S2 M2 1 36028797018963968) (not (and (= S2 1) (= M2 5992812844700997))))
  (jdouble.near
    110547884827838604083820853762981888 664613997892457936451903530140172288 S2 M2 1
    72057594037927936)
  (jdouble.near 110547884827838604083820853762981888
    664613997892457936451903530140172288 S2 M2 1 18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: t*(P1+...) = 0x3f94755a_4023ec30: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 51868227284872305748731465202167 2596148429267413814265248164610048 1
  5758530017815600 1 288230376151711744))
  (or (and (jdouble.near 51868227284872305748731465202167 2596148429267413814265248164610048 S2 M2 1
    288230376151711744) (not (and (= S2 1) (= M2 5758530017815600))))
  (jdouble.near
    51868227284872305748731465202167 2596148429267413814265248164610048 S2 M2 1 576460752303423488)

     (jdouble.near 51868227284872305748731465202167 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: c = r - t*(...) = 0x3fd4e6ed_5bfdc141: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 1695776514436860301415445814050816 5192296858534827628530496329220096
  1 5883406659207489 1 18014398509481984))
  (or (and (jdouble.near 1695776514436860301415445814050816 5192296858534827628530496329220096 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 5883406659207489))))
  (jdouble.near
    1695776514436860301415445814050816 5192296858534827628530496329220096 S2 M2 1
    36028797018963968)
  (jdouble.near 1695776514436860301415445814050816
    5192296858534827628530496329220096 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: r*c = 0x3fbcf9f5_cfe75481: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 36731959783485934191390737499396 324518553658426726783156020576256 1
  8156133498246273 1 72057594037927936))
  (or (and (jdouble.near 36731959783485934191390737499396 324518553658426726783156020576256 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 8156133498246273))))
  (jdouble.near
    36731959783485934191390737499396 324518553658426726783156020576256 S2 M2 1 144115188075855872)
 
    (jdouble.near 36731959783485934191390737499396 324518553658426726783156020576256 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: 2.0-c = 0x3ffac644_a9008fb0: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 67881384395568710590980834721792 40564819207303340847894502572032 1
  7536347589939120 1 4503599627370496))
  (or (and (jdouble.near 67881384395568710590980834721792 40564819207303340847894502572032 S2 M2 1
    4503599627370496) (not (and (= S2 1) (= M2 7536347589939120))))
  (jdouble.near
    67881384395568710590980834721792 40564819207303340847894502572032 S2 M2 1 9007199254740992)
 
    (jdouble.near 67881384395568710590980834721792 40564819207303340847894502572032 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: q = (r*c)/(2.0-c) = 0x3fb150da_6dbc8d9d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 36731959783485935073506422161408 543051075164549702742245187256320 1
  4873973678247325 1 72057594037927936))
  (or (and (jdouble.near 36731959783485935073506422161408 543051075164549702742245187256320 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 4873973678247325))))
  (jdouble.near
    36731959783485935073506422161408 543051075164549702742245187256320 S2 M2 1 144115188075855872)
 
    (jdouble.near 36731959783485935073506422161408 543051075164549702742245187256320 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: +0 - q = 0xbfb150da_6dbc8d9d: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- (* 4873973678247325 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (* 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 16384) (- 1) 4873973678247325 1 72057594037927936))
  (or (and (jdouble.near (- (* 4873973678247325 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (* 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 16384) S2 M2 1 72057594037927936) (not (and (= S2 (- 1)) (= M2
    4873973678247325))))
  (jdouble.near (- (* 4873973678247325 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (*
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 16384) S2 M2 1 144115188075855872)
  (jdouble.near (- (*
    4873973678247325 4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 1048576)) (* 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 16384) S2 M2 1 36028797018963968))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: (+0 - q) - x = 0xbfda8279_9b6f236b: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 537679946416324261748698045743104)
  1298074214633706907132624082305024 (- 1) 7461808204882795 1 18014398509481984))
  (or (and (jdouble.near (- 537679946416324261748698045743104) 1298074214633706907132624082305024 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 7461808204882795))))
  (jdouble.near (-
    537679946416324261748698045743104) 1298074214633706907132624082305024 S2 M2 1
    36028797018963968)
  (jdouble.near (- 537679946416324261748698045743104)
    1298074214633706907132624082305024 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0x3fd62e43_00000004: y = 0x3ff6a09e_66dbc8db: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 114734635065626946929182726160384 81129638414606681695789005144064 1
  6369051678591195 1 4503599627370496))
  (or (and (jdouble.near 114734635065626946929182726160384 81129638414606681695789005144064 S2 M2 1
    4503599627370496) (not (and (= S2 1) (= M2 6369051678591195))))
  (jdouble.near
    114734635065626946929182726160384 81129638414606681695789005144064 S2 M2 1 9007199254740992)
 
    (jdouble.near 114734635065626946929182726160384 81129638414606681695789005144064 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: hi = x - ln2HI[xsb] = 0xbfd62e42_fdbffffc: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 56234779941445944576556914442240)
  162259276829213363391578010288128 (- 1) 6243314747572220 1 18014398509481984))
  (or (and (jdouble.near (- 56234779941445944576556914442240) 162259276829213363391578010288128 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6243314747572220))))
  (jdouble.near (-
    56234779941445944576556914442240) 162259276829213363391578010288128 S2 M2 1 36028797018963968)
 
    (jdouble.near (- 56234779941445944576556914442240) 162259276829213363391578010288128 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: r = hi - lo = 0xbfd62e42_fdf473da: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 241526540879250298538438988607085416022016)
  696898287454081973172991196020261297061888 (- 1) 6243314751009754 1 18014398509481984))
  (or (and (jdouble.near (- 241526540879250298538438988607085416022016)
    696898287454081973172991196020261297061888 S2 M2 1 18014398509481984) (not (and (= S2 (- 1)) (=
    M2 6243314751009754))))
  (jdouble.near (- 241526540879250298538438988607085416022016)
    696898287454081973172991196020261297061888 S2 M2 1 36028797018963968)
  (jdouble.near (-
    241526540879250298538438988607085416022016) 696898287454081973172991196020261297061888 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: t = r*r = 0x3fbebfbd_fcacfb11: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979080175986585162603140516 324518553658426726783156020576256 1
  8655072010238737 1 72057594037927936))
  (or (and (jdouble.near 38978979080175986585162603140516 324518553658426726783156020576256 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 8655072010238737))))
  (jdouble.near
    38978979080175986585162603140516 324518553658426726783156020576256 S2 M2 1 144115188075855872)
 
    (jdouble.near 38978979080175986585162603140516 324518553658426726783156020576256 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: t*P5 = 0x3e355911_7faae0ae: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 54123415465768442179735852736976
  10889035741470030830827987437816582766592 1 6008906202144942 1 1208925819614629174706176))
  (or (and (jdouble.near 54123415465768442179735852736976 10889035741470030830827987437816582766592
    S2 M2 1 1208925819614629174706176) (not (and (= S2 1) (= M2 6008906202144942))))
  (jdouble.near
    54123415465768442179735852736976 10889035741470030830827987437816582766592 S2 M2 1
    2417851639229258349412352)
  (jdouble.near 54123415465768442179735852736976
    10889035741470030830827987437816582766592 S2 M2 1 604462909807314587353088))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: P4+t*P5 = 0xbebba7e8_b452c110: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9410813252422787864708906275690359291904)
  5708990770823839524233143877797980545530986496 (- 1) 7784442270761232 1 4722366482869645213696))
  (or (and (jdouble.near (- 9410813252422787864708906275690359291904)
    5708990770823839524233143877797980545530986496 S2 M2 1 4722366482869645213696) (not (and (= S2
    (- 1)) (= M2 7784442270761232))))
  (jdouble.near (- 9410813252422787864708906275690359291904)
    5708990770823839524233143877797980545530986496 S2 M2 1 9444732965739290427392)
  (jdouble.near
    (- 9410813252422787864708906275690359291904) 5708990770823839524233143877797980545530986496 S2
    M2 1 2361183241434822606848))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: t*(P4+t*P5) = 0xbe8a9320_90181403: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 67374908412984814870711044243984)
  340282366920938463463374607431768211456 (- 1) 7480117460210691 1 37778931862957161709568))
  (or (and (jdouble.near (- 67374908412984814870711044243984)
    340282366920938463463374607431768211456 S2 M2 1 37778931862957161709568) (not (and (= S2 (- 1))
    (= M2 7480117460210691))))
  (jdouble.near (- 67374908412984814870711044243984)
    340282366920938463463374607431768211456 S2 M2 1 75557863725914323419136)
  (jdouble.near (-
    67374908412984814870711044243984) 340282366920938463463374607431768211456 S2 M2 1
    18889465931478580854784))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: P3+t*(...) = 0x3f114921_1eddd222: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 183812682912229042517825584757181775872
  2787593149816327892691964784081045188247552 1 4865481204683298 1 73786976294838206464))
  (or (and (jdouble.near 183812682912229042517825584757181775872
    2787593149816327892691964784081045188247552 S2 M2 1 73786976294838206464) (not (and (= S2 1) (=
    M2 4865481204683298))))
  (jdouble.near 183812682912229042517825584757181775872
    2787593149816327892691964784081045188247552 S2 M2 1 147573952589676412928)
  (jdouble.near
    183812682912229042517825584757181775872 2787593149816327892691964784081045188247552 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: t*(P3+...) = 0x3ee09c22_2b0924d0: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 42111090190997063820651456514626
  5316911983139663491615228241121378304 1 4675270192211152 1 590295810358705651712))
  (or (and (jdouble.near 42111090190997063820651456514626 5316911983139663491615228241121378304 S2
    M2 1 590295810358705651712) (not (and (= S2 1) (= M2 4675270192211152))))
  (jdouble.near
    42111090190997063820651456514626 5316911983139663491615228241121378304 S2 M2 1
    1180591620717411303424)
  (jdouble.near 42111090190997063820651456514626
    5316911983139663491615228241121378304 S2 M2 1 295147905179352825856))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: P2+t*(...) = 0xbf66b0cf_f493b46e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 3770134748817788959975805267359039488)
  1361129467683753853853498429727072845824 (- 1) 6386856695673966 1 2305843009213693952))
  (or (and (jdouble.near (- 3770134748817788959975805267359039488)
    1361129467683753853853498429727072845824 S2 M2 1 2305843009213693952) (not (and (= S2 (- 1)) (=
    M2 6386856695673966))))
  (jdouble.near (- 3770134748817788959975805267359039488)
    1361129467683753853853498429727072845824 S2 M2 1 4611686018427387904)
  (jdouble.near (-
    3770134748817788959975805267359039488) 1361129467683753853853498429727072845824 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: t*(P2+...) = 0xbf35cdb9_05fd9557: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 55278704620133610219246775620942)
  166153499473114484112975882535043072 (- 1) 6137169064072535 1 18446744073709551616))
  (or (and (jdouble.near (- 55278704620133610219246775620942) 166153499473114484112975882535043072
    S2 M2 1 18446744073709551616) (not (and (= S2 (- 1)) (= M2 6137169064072535))))
  (jdouble.near
    (- 55278704620133610219246775620942) 166153499473114484112975882535043072 S2 M2 1
    36893488147419103232)
  (jdouble.near (- 55278704620133610219246775620942)
    166153499473114484112975882535043072 S2 M2 1 9223372036854775808))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: P1+t*(...) = 0x3fc54a6e_78d25673: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 110547884830262024550090145297072128
  664613997892457936451903530140172288 1 5992812844832371 1 36028797018963968))
  (or (and (jdouble.near 110547884830262024550090145297072128 664613997892457936451903530140172288
    S2 M2 1 36028797018963968) (not (and (= S2 1) (= M2 5992812844832371))))
  (jdouble.near
    110547884830262024550090145297072128 664613997892457936451903530140172288 S2 M2 1
    72057594037927936)
  (jdouble.near 110547884830262024550090145297072128
    664613997892457936451903530140172288 S2 M2 1 18014398509481984))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: t*(P1+...) = 0x3f94755a_3c600f2f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 51868226715907833544172455755427 2596148429267413814265248164610048 1
  5758529954647855 1 288230376151711744))
  (or (and (jdouble.near 51868226715907833544172455755427 2596148429267413814265248164610048 S2 M2 1
    288230376151711744) (not (and (= S2 1) (= M2 5758529954647855))))
  (jdouble.near
    51868226715907833544172455755427 2596148429267413814265248164610048 S2 M2 1 576460752303423488)

     (jdouble.near 51868226715907833544172455755427 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: c = r - t*(...) = 0xbfd77598_a1ba74cd: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 1903249412548887620337828727095296)
  5192296858534827628530496329220096 (- 1) 6603222873175245 1 18014398509481984))
  (or (and (jdouble.near (- 1903249412548887620337828727095296) 5192296858534827628530496329220096
    S2 M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6603222873175245))))
  (jdouble.near (-
    1903249412548887620337828727095296) 5192296858534827628530496329220096 S2 M2 1
    36028797018963968)
  (jdouble.near (- 1903249412548887620337828727095296)
    5192296858534827628530496329220096 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: r*c = 0x3fc042c3_17506e3e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 41225998768300017152443946339730 324518553658426726783156020576256 1
  4577005304573502 1 36028797018963968))
  (or (and (jdouble.near 41225998768300017152443946339730 324518553658426726783156020576256 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 4577005304573502))))
  (jdouble.near
    41225998768300017152443946339730 324518553658426726783156020576256 S2 M2 1 72057594037927936)
 
    (jdouble.near 41225998768300017152443946339730 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: 2.0-c = 0x4002eeb3_14374e9a: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 95998774450144866370415780429824 40564819207303340847894502572032 1
  5329002486517402 1 2251799813685248))
  (or (and (jdouble.near 95998774450144866370415780429824 40564819207303340847894502572032 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 5329002486517402))))
  (jdouble.near
    95998774450144866370415780429824 40564819207303340847894502572032 S2 M2 1 4503599627370496)
 
    (jdouble.near 95998774450144866370415780429824 40564819207303340847894502572032 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: q = (r*c)/(2.0-c) = 0x3fab7bfe_5d602c7e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10306499692075003579303509098496 191997548900289746251630442971136 1
  7736156789681278 1 144115188075855872))
  (or (and (jdouble.near 10306499692075003579303509098496 191997548900289746251630442971136 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 7736156789681278))))
  (jdouble.near
    10306499692075003579303509098496 191997548900289746251630442971136 S2 M2 1 288230376151711744)
 
    (jdouble.near 10306499692075003579303509098496 191997548900289746251630442971136 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: lo - q = 0xbfab7bfe_5bbc8d8b: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 299278068940381584338606325720502541221888)
  5575186299632655785383929568162090376495104 (- 1) 7736156762181003 1 144115188075855872))
  (or (and (jdouble.near (- 299278068940381584338606325720502541221888)
    5575186299632655785383929568162090376495104 S2 M2 1 144115188075855872) (not (and (= S2 (- 1))
    (= M2 7736156762181003))))
  (jdouble.near (- 299278068940381584338606325720502541221888)
    5575186299632655785383929568162090376495104 S2 M2 1 288230376151711744)
  (jdouble.near (-
    299278068940381584338606325720502541221888) 5575186299632655785383929568162090376495104 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: (lo - q) - hi = 0x3fd2bec3_32486e4b: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 760394268217382681412723055525888 2596148429267413814265248164610048
  1 5276295152299595 1 18014398509481984))
  (or (and (jdouble.near 760394268217382681412723055525888 2596148429267413814265248164610048 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 5276295152299595))))
  (jdouble.near
    760394268217382681412723055525888 2596148429267413814265248164610048 S2 M2 1 36028797018963968)

     (jdouble.near 760394268217382681412723055525888 2596148429267413814265248164610048 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0x3fd62e43_00000004: y = 0x3fe6a09e_66dbc8da: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 57367317532813471212791549394944 81129638414606681695789005144064 1
  6369051678591194 1 9007199254740992))
  (or (and (jdouble.near 57367317532813471212791549394944 81129638414606681695789005144064 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 6369051678591194))))
  (jdouble.near
    57367317532813471212791549394944 81129638414606681695789005144064 S2 M2 1 18014398509481984)
 
    (jdouble.near 57367317532813471212791549394944 81129638414606681695789005144064 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "refuted at 0x3fd62e43_00000004: the k = 0 tail is not the C's y + (1 << 52): expect unsat")
(push 1)
(assert (= 4609047870851238107 (jlong.add 4604544271223867610 (jlong.shl 1 52))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: r = x - (+0) = 0xbfd62e43_0000001f: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- (* 6243314785320991 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (* 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4096) (- 1) 6243314785320991 1 18014398509481984))
  (or (and (jdouble.near (- (* 6243314785320991 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (* 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4096) S2 M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2
    6243314785320991))))
  (jdouble.near (- (* 6243314785320991 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (*
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4096) S2 M2 1 36028797018963968)
  (jdouble.near (- (*
    6243314785320991 4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 1048576)) (* 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4096) S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: t = r*r = 0x3fbebfbe_02589056: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979508607691937406905222081 324518553658426726783156020576256 1
  8655072105369686 1 72057594037927936))
  (or (and (jdouble.near 38978979508607691937406905222081 324518553658426726783156020576256 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 8655072105369686))))
  (jdouble.near
    38978979508607691937406905222081 324518553658426726783156020576256 S2 M2 1 144115188075855872)
 
    (jdouble.near 38978979508607691937406905222081 324518553658426726783156020576256 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: t*P5 = 0x3e355911_839aa8e5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 54123416060657990214565752757728
  10889035741470030830827987437816582766592 1 6008906268190949 1 1208925819614629174706176))
  (or (and (jdouble.near 54123416060657990214565752757728 10889035741470030830827987437816582766592
    S2 M2 1 1208925819614629174706176) (not (and (= S2 1) (= M2 6008906268190949))))
  (jdouble.near
    54123416060657990214565752757728 10889035741470030830827987437816582766592 S2 M2 1
    2417851639229258349412352)
  (jdouble.near 54123416060657990214565752757728
    10889035741470030830827987437816582766592 S2 M2 1 604462909807314587353088))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: P4+t*P5 = 0xbebba7e8_b44ed148: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9410813252110894414924732307819076780032)
  5708990770823839524233143877797980545530986496 (- 1) 7784442270503240 1 4722366482869645213696))
  (or (and (jdouble.near (- 9410813252110894414924732307819076780032)
    5708990770823839524233143877797980545530986496 S2 M2 1 4722366482869645213696) (not (and (= S2
    (- 1)) (= M2 7784442270503240))))
  (jdouble.near (- 9410813252110894414924732307819076780032)
    5708990770823839524233143877797980545530986496 S2 M2 1 9444732965739290427392)
  (jdouble.near
    (- 9410813252110894414924732307819076780032) 5708990770823839524233143877797980545530986496 S2
    M2 1 2361183241434822606848))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: t*(P4+t*P5) = 0xbe8a9320_94fad242: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 67374909151293256161333460782640)
  340282366920938463463374607431768211456 (- 1) 7480117542179394 1 37778931862957161709568))
  (or (and (jdouble.near (- 67374909151293256161333460782640)
    340282366920938463463374607431768211456 S2 M2 1 37778931862957161709568) (not (and (= S2 (- 1))
    (= M2 7480117542179394))))
  (jdouble.near (- 67374909151293256161333460782640)
    340282366920938463463374607431768211456 S2 M2 1 75557863725914323419136)
  (jdouble.near (-
    67374909151293256161333460782640) 340282366920938463463374607431768211456 S2 M2 1
    18889465931478580854784))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: P3+t*(...) = 0x3f114921_1edb60c3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 183812682906180819772645951378481479680
  2787593149816327892691964784081045188247552 1 4865481204523203 1 73786976294838206464))
  (or (and (jdouble.near 183812682906180819772645951378481479680
    2787593149816327892691964784081045188247552 S2 M2 1 73786976294838206464) (not (and (= S2 1) (=
    M2 4865481204523203))))
  (jdouble.near 183812682906180819772645951378481479680
    2787593149816327892691964784081045188247552 S2 M2 1 147573952589676412928)
  (jdouble.near
    183812682906180819772645951378481479680 2787593149816327892691964784081045188247552 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: t*(P3+...) = 0x3ee09c22_2e16e87b: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 42111090652469274395127679824258
  5316911983139663491615228241121378304 1 4675270243444859 1 590295810358705651712))
  (or (and (jdouble.near 42111090652469274395127679824258 5316911983139663491615228241121378304 S2
    M2 1 590295810358705651712) (not (and (= S2 1) (= M2 4675270243444859))))
  (jdouble.near
    42111090652469274395127679824258 5316911983139663491615228241121378304 S2 M2 1
    1180591620717411303424)
  (jdouble.near 42111090652469274395127679824258
    5316911983139663491615228241121378304 S2 M2 1 295147905179352825856))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: P2+t*(...) = 0xbf66b0cf_f490a6ab: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 3770134748699652074853752571034599424)
  1361129467683753853853498429727072845824 (- 1) 6386856695473835 1 2305843009213693952))
  (or (and (jdouble.near (- 3770134748699652074853752571034599424)
    1361129467683753853853498429727072845824 S2 M2 1 2305843009213693952) (not (and (= S2 (- 1)) (=
    M2 6386856695473835))))
  (jdouble.near (- 3770134748699652074853752571034599424)
    1361129467683753853853498429727072845824 S2 M2 1 4611686018427387904)
  (jdouble.near (-
    3770134748699652074853752571034599424) 1361129467683753853853498429727072845824 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: t*(P2+...) = 0xbf35cdb9_09fff152: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 55278705225989200570195615165810)
  166153499473114484112975882535043072 (- 1) 6137169131336018 1 18446744073709551616))
  (or (and (jdouble.near (- 55278705225989200570195615165810) 166153499473114484112975882535043072
    S2 M2 1 18446744073709551616) (not (and (= S2 (- 1)) (= M2 6137169131336018))))
  (jdouble.near
    (- 55278705225989200570195615165810) 166153499473114484112975882535043072 S2 M2 1
    36893488147419103232)
  (jdouble.near (- 55278705225989200570195615165810)
    166153499473114484112975882535043072 S2 M2 1 9223372036854775808))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: P1+t*(...) = 0x3fc54a6e_78d05545: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 110547884827838602174294611757891584
  664613997892457936451903530140172288 1 5992812844700997 1 36028797018963968))
  (or (and (jdouble.near 110547884827838602174294611757891584 664613997892457936451903530140172288
    S2 M2 1 36028797018963968) (not (and (= S2 1) (= M2 5992812844700997))))
  (jdouble.near
    110547884827838602174294611757891584 664613997892457936451903530140172288 S2 M2 1
    72057594037927936)
  (jdouble.near 110547884827838602174294611757891584
    664613997892457936451903530140172288 S2 M2 1 18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: t*(P1+...) = 0x3f94755a_4023ec62: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 51868227284872755209694817776942 2596148429267413814265248164610048 1
  5758530017815650 1 288230376151711744))
  (or (and (jdouble.near 51868227284872755209694817776942 2596148429267413814265248164610048 S2 M2 1
    288230376151711744) (not (and (= S2 1) (= M2 5758530017815650))))
  (jdouble.near
    51868227284872755209694817776942 2596148429267413814265248164610048 S2 M2 1 576460752303423488)

     (jdouble.near 51868227284872755209694817776942 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: c = r - t*(...) = 0xbfd77598_a4023ee5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 1903249423576358199173621852667904)
  5192296858534827628530496329220096 (- 1) 6603222911434469 1 18014398509481984))
  (or (and (jdouble.near (- 1903249423576358199173621852667904) 5192296858534827628530496329220096
    S2 M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 6603222911434469))))
  (jdouble.near (-
    1903249423576358199173621852667904) 5192296858534827628530496329220096 S2 M2 1
    36028797018963968)
  (jdouble.near (- 1903249423576358199173621852667904)
    5192296858534827628530496329220096 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: r*c = 0x3fc042c3_1a64e5f3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 41225999233729140991888426638779 324518553658426726783156020576256 1
  4577005356246515 1 36028797018963968))
  (or (and (jdouble.near 41225999233729140991888426638779 324518553658426726783156020576256 S2 M2 1
    36028797018963968) (not (and (= S2 1) (= M2 4577005356246515))))
  (jdouble.near
    41225999233729140991888426638779 324518553658426726783156020576256 S2 M2 1 72057594037927936)
 
    (jdouble.near 41225999233729140991888426638779 324518553658426726783156020576256 S2 M2 1
    18014398509481984))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: 2.0-c = 0x4002eeb3_148047dd: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 95998774536296979845357949157376 40564819207303340847894502572032 1
  5329002491299805 1 2251799813685248))
  (or (and (jdouble.near 95998774536296979845357949157376 40564819207303340847894502572032 S2 M2 1
    2251799813685248) (not (and (= S2 1) (= M2 5329002491299805))))
  (jdouble.near
    95998774536296979845357949157376 40564819207303340847894502572032 S2 M2 1 4503599627370496)
 
    (jdouble.near 95998774536296979845357949157376 40564819207303340847894502572032 S2 M2 1
    1125899906842624))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: q = (r*c)/(2.0-c) = 0x3fab7bfe_622aec43: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 10306499808432284625258906910720 191997549072593973201514780426240 1
  7736156870077507 1 144115188075855872))
  (or (and (jdouble.near 10306499808432284625258906910720 191997549072593973201514780426240 S2 M2 1
    144115188075855872) (not (and (= S2 1) (= M2 7736156870077507))))
  (jdouble.near
    10306499808432284625258906910720 191997549072593973201514780426240 S2 M2 1 288230376151711744)
 
    (jdouble.near 10306499808432284625258906910720 191997549072593973201514780426240 S2 M2 1
    72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: +0 - q = 0xbfab7bfe_622aec43: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- (* 7736156870077507 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (* 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
  4611686018427387904 32768) (- 1) 7736156870077507 1 144115188075855872))
  (or (and (jdouble.near (- (* 7736156870077507 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (* 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 32768) S2 M2 1 144115188075855872) (not (and (= S2 (- 1)) (= M2
    7736156870077507))))
  (jdouble.near (- (* 7736156870077507 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904 1048576)) (*
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 32768) S2 M2 1 288230376151711744)
  (jdouble.near (- (*
    7736156870077507 4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 1048576)) (* 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 4611686018427387904
    4611686018427387904 4611686018427387904 4611686018427387904 32768) S2 M2 1 72057594037927936))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: (+0 - q) - x = 0x3fd2bec3_33baa297: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 760394271713863292874220072075264 2596148429267413814265248164610048
  1 5276295176561303 1 18014398509481984))
  (or (and (jdouble.near 760394271713863292874220072075264 2596148429267413814265248164610048 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 5276295176561303))))
  (jdouble.near
    760394271713863292874220072075264 2596148429267413814265248164610048 S2 M2 1 36028797018963968)

     (jdouble.near 760394271713863292874220072075264 2596148429267413814265248164610048 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "k = 0 tail at 0xbfd62e43_0000001f: y = 0x3fe6a09e_6622aeb4: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 57367317423548452104619767627776 81129638414606681695789005144064 1
  6369051666460340 1 9007199254740992))
  (or (and (jdouble.near 57367317423548452104619767627776 81129638414606681695789005144064 S2 M2 1
    9007199254740992) (not (and (= S2 1) (= M2 6369051666460340))))
  (jdouble.near
    57367317423548452104619767627776 81129638414606681695789005144064 S2 M2 1 18014398509481984)
 
    (jdouble.near 57367317423548452104619767627776 81129638414606681695789005144064 S2 M2 1
    4503599627370496))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: hi = x - ln2HI[xsb] = 0x3fd62e42_fdbfffe1: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 56234779941445701382177036435456 162259276829213363391578010288128 1
  6243314747572193 1 18014398509481984))
  (or (and (jdouble.near 56234779941445701382177036435456 162259276829213363391578010288128 S2 M2 1
    18014398509481984) (not (and (= S2 1) (= M2 6243314747572193))))
  (jdouble.near
    56234779941445701382177036435456 162259276829213363391578010288128 S2 M2 1 36028797018963968)
 
    (jdouble.near 56234779941445701382177036435456 162259276829213363391578010288128 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: r = hi - lo = 0x3fd62e42_fdf473bf: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 241526540879249254026530841567478469885952
  696898287454081973172991196020261297061888 1 6243314751009727 1 18014398509481984))
  (or (and (jdouble.near 241526540879249254026530841567478469885952
    696898287454081973172991196020261297061888 S2 M2 1 18014398509481984) (not (and (= S2 1) (= M2
    6243314751009727))))
  (jdouble.near 241526540879249254026530841567478469885952
    696898287454081973172991196020261297061888 S2 M2 1 36028797018963968)
  (jdouble.near
    241526540879249254026530841567478469885952 696898287454081973172991196020261297061888 S2 M2 1
    9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: t = r*r = 0x3fbebfbd_fcacfac6: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 38978979080175649446166048614529 324518553658426726783156020576256 1
  8655072010238662 1 72057594037927936))
  (or (and (jdouble.near 38978979080175649446166048614529 324518553658426726783156020576256 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 8655072010238662))))
  (jdouble.near
    38978979080175649446166048614529 324518553658426726783156020576256 S2 M2 1 144115188075855872)
 
    (jdouble.near 38978979080175649446166048614529 324518553658426726783156020576256 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: t*P5 = 0x3e355911_7faae079: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 54123415465767973176571565873376
  10889035741470030830827987437816582766592 1 6008906202144889 1 1208925819614629174706176))
  (or (and (jdouble.near 54123415465767973176571565873376 10889035741470030830827987437816582766592
    S2 M2 1 1208925819614629174706176) (not (and (= S2 1) (= M2 6008906202144889))))
  (jdouble.near
    54123415465767973176571565873376 10889035741470030830827987437816582766592 S2 M2 1
    2417851639229258349412352)
  (jdouble.near 54123415465767973176571565873376
    10889035741470030830827987437816582766592 S2 M2 1 604462909807314587353088))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: P4+t*P5 = 0xbebba7e8_b452c111: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 9410813252422788114994329867781555617792)
  5708990770823839524233143877797980545530986496 (- 1) 7784442270761233 1 4722366482869645213696))
  (or (and (jdouble.near (- 9410813252422788114994329867781555617792)
    5708990770823839524233143877797980545530986496 S2 M2 1 4722366482869645213696) (not (and (= S2
    (- 1)) (= M2 7784442270761233))))
  (jdouble.near (- 9410813252422788114994329867781555617792)
    5708990770823839524233143877797980545530986496 S2 M2 1 9444732965739290427392)
  (jdouble.near
    (- 9410813252422788114994329867781555617792) 5708990770823839524233143877797980545530986496 S2
    M2 1 2361183241434822606848))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: t*(P4+t*P5) = 0xbe8a9320_901813c3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 67374908412984239692612747390246)
  340282366920938463463374607431768211456 (- 1) 7480117460210627 1 37778931862957161709568))
  (or (and (jdouble.near (- 67374908412984239692612747390246)
    340282366920938463463374607431768211456 S2 M2 1 37778931862957161709568) (not (and (= S2 (- 1))
    (= M2 7480117460210627))))
  (jdouble.near (- 67374908412984239692612747390246)
    340282366920938463463374607431768211456 S2 M2 1 75557863725914323419136)
  (jdouble.near (-
    67374908412984239692612747390246) 340282366920938463463374607431768211456 S2 M2 1
    18889465931478580854784))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: P3+t*(...) = 0x3f114921_1eddd222: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 183812682912229047240192067626826989568
  2787593149816327892691964784081045188247552 1 4865481204683298 1 73786976294838206464))
  (or (and (jdouble.near 183812682912229047240192067626826989568
    2787593149816327892691964784081045188247552 S2 M2 1 73786976294838206464) (not (and (= S2 1) (=
    M2 4865481204683298))))
  (jdouble.near 183812682912229047240192067626826989568
    2787593149816327892691964784081045188247552 S2 M2 1 147573952589676412928)
  (jdouble.near
    183812682912229047240192067626826989568 2787593149816327892691964784081045188247552 S2 M2 1
    36893488147419103232))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: t*(P3+...) = 0x3ee09c22_2b0924a8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 42111090190996698909561105267276
  5316911983139663491615228241121378304 1 4675270192211112 1 590295810358705651712))
  (or (and (jdouble.near 42111090190996698909561105267276 5316911983139663491615228241121378304 S2
    M2 1 590295810358705651712) (not (and (= S2 1) (= M2 4675270192211112))))
  (jdouble.near
    42111090190996698909561105267276 5316911983139663491615228241121378304 S2 M2 1
    1180591620717411303424)
  (jdouble.near 42111090190996698909561105267276
    5316911983139663491615228241121378304 S2 M2 1 295147905179352825856))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: P2+t*(...) = 0xbf66b0cf_f493b46e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 3770134748817789052209525635906797568)
  1361129467683753853853498429727072845824 (- 1) 6386856695673966 1 2305843009213693952))
  (or (and (jdouble.near (- 3770134748817789052209525635906797568)
    1361129467683753853853498429727072845824 S2 M2 1 2305843009213693952) (not (and (= S2 (- 1)) (=
    M2 6386856695673966))))
  (jdouble.near (- 3770134748817789052209525635906797568)
    1361129467683753853853498429727072845824 S2 M2 1 4611686018427387904)
  (jdouble.near (-
    3770134748817789052209525635906797568) 1361129467683753853853498429727072845824 S2 M2 1
    1152921504606846976))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: t*(P2+...) = 0xbf35cdb9_05fd9522: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 55278704620133131204994600073492)
  166153499473114484112975882535043072 (- 1) 6137169064072482 1 18446744073709551616))
  (or (and (jdouble.near (- 55278704620133131204994600073492) 166153499473114484112975882535043072
    S2 M2 1 18446744073709551616) (not (and (= S2 (- 1)) (= M2 6137169064072482))))
  (jdouble.near
    (- 55278704620133131204994600073492) 166153499473114484112975882535043072 S2 M2 1
    36893488147419103232)
  (jdouble.near (- 55278704620133131204994600073492)
    166153499473114484112975882535043072 S2 M2 1 9223372036854775808))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: P1+t*(...) = 0x3fc54a6e_78d25673: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 110547884830262026459616387302162432
  664613997892457936451903530140172288 1 5992812844832371 1 36028797018963968))
  (or (and (jdouble.near 110547884830262026459616387302162432 664613997892457936451903530140172288
    S2 M2 1 36028797018963968) (not (and (= S2 1) (= M2 5992812844832371))))
  (jdouble.near
    110547884830262026459616387302162432 664613997892457936451903530140172288 S2 M2 1
    72057594037927936)
  (jdouble.near 110547884830262026459616387302162432
    664613997892457936451903530140172288 S2 M2 1 18014398509481984))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: t*(P1+...) = 0x3f94755a_3c600efd: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 51868226715907384083209093327602 2596148429267413814265248164610048 1
  5758529954647805 1 288230376151711744))
  (or (and (jdouble.near 51868226715907384083209093327602 2596148429267413814265248164610048 S2 M2 1
    288230376151711744) (not (and (= S2 1) (= M2 5758529954647805))))
  (jdouble.near
    51868226715907384083209093327602 2596148429267413814265248164610048 S2 M2 1 576460752303423488)

     (jdouble.near 51868226715907384083209093327602 2596148429267413814265248164610048 S2 M2 1
    144115188075855872))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: c = r - t*(...) = 0x3fd4e6ed_5a2e72cf: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 1695776505685249386770179731488768 5192296858534827628530496329220096
  1 5883406628844239 1 18014398509481984))
  (or (and (jdouble.near 1695776505685249386770179731488768 5192296858534827628530496329220096 S2 M2
    1 18014398509481984) (not (and (= S2 1) (= M2 5883406628844239))))
  (jdouble.near
    1695776505685249386770179731488768 5192296858534827628530496329220096 S2 M2 1
    36028797018963968)
  (jdouble.near 1695776505685249386770179731488768
    5192296858534827628530496329220096 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: r*c = 0x3fbcf9f5_cab91961: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 36731959392051647326348256912753 324518553658426726783156020576256 1
  8156133411330401 1 72057594037927936))
  (or (and (jdouble.near 36731959392051647326348256912753 324518553658426726783156020576256 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 8156133411330401))))
  (jdouble.near
    36731959392051647326348256912753 324518553658426726783156020576256 S2 M2 1 144115188075855872)
 
    (jdouble.near 36731959392051647326348256912753 324518553658426726783156020576256 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: 2.0-c = 0x3ffac644_a974634c: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 67881384463940671283859441057792 40564819207303340847894502572032 1
  7536347597529932 1 4503599627370496))
  (or (and (jdouble.near 67881384463940671283859441057792 40564819207303340847894502572032 S2 M2 1
    4503599627370496) (not (and (= S2 1) (= M2 7536347597529932))))
  (jdouble.near
    67881384463940671283859441057792 40564819207303340847894502572032 S2 M2 1 9007199254740992)
 
    (jdouble.near 67881384463940671283859441057792 40564819207303340847894502572032 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: q = (r*c)/(2.0-c) = 0x3fb150da_6a591c4e: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 36731959392051646321724695248896 543051075711525352256477018980352 1
  4873973621398606 1 72057594037927936))
  (or (and (jdouble.near 36731959392051646321724695248896 543051075711525352256477018980352 S2 M2 1
    72057594037927936) (not (and (= S2 1) (= M2 4873973621398606))))
  (jdouble.near
    36731959392051646321724695248896 543051075711525352256477018980352 S2 M2 1 144115188075855872)
 
    (jdouble.near 36731959392051646321724695248896 543051075711525352256477018980352 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: lo - q = 0xbfb150da_6b2aebc8: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 188552722292873232712176647231057371856896)
  2787593149816327892691964784081045188247552 (- 1) 4873973635148744 1 72057594037927936))
  (or (and (jdouble.near (- 188552722292873232712176647231057371856896)
    2787593149816327892691964784081045188247552 S2 M2 1 72057594037927936) (not (and (= S2 (- 1)) (=
    M2 4873973635148744))))
  (jdouble.near (- 188552722292873232712176647231057371856896)
    2787593149816327892691964784081045188247552 S2 M2 1 144115188075855872)
  (jdouble.near (-
    188552722292873232712176647231057371856896) 2787593149816327892691964784081045188247552 S2 M2 1
    36028797018963968))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: (lo - q) - hi = 0xbfda8279_988abad3: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near (- 537679942919843632272802519711744)
  1298074214633706907132624082305024 (- 1) 7461808156359379 1 18014398509481984))
  (or (and (jdouble.near (- 537679942919843632272802519711744) 1298074214633706907132624082305024 S2
    M2 1 18014398509481984) (not (and (= S2 (- 1)) (= M2 7461808156359379))))
  (jdouble.near (-
    537679942919843632272802519711744) 1298074214633706907132624082305024 S2 M2 1
    36028797018963968)
  (jdouble.near (- 537679942919843632272802519711744)
    1298074214633706907132624082305024 S2 M2 1 9007199254740992))))
(check-sat)
(pop 1)

(echo "the C at 0xbfd62e43_0000001f: y = 0x3ff6a09e_6622aeb5: expect unsat")
(push 1)
(declare-const S2 Int) (declare-const M2 Int)
(assert (or (not (jdouble.near 114734634847096908712839162626048 81129638414606681695789005144064 1
  6369051666460341 1 4503599627370496))
  (or (and (jdouble.near 114734634847096908712839162626048 81129638414606681695789005144064 S2 M2 1
    4503599627370496) (not (and (= S2 1) (= M2 6369051666460341))))
  (jdouble.near
    114734634847096908712839162626048 81129638414606681695789005144064 S2 M2 1 9007199254740992)
 
    (jdouble.near 114734634847096908712839162626048 81129638414606681695789005144064 S2 M2 1
    2251799813685248))))
(check-sat)
(pop 1)

(echo "refuted at 0xbfd62e43_0000001f: the k = 0 tail is not the C's y + (-1 << 52): expect unsat")
(push 1)
(assert (= 4604544271211736756 (jlong.add 4609047870839107253 (jlong.shl (- 1) 52))))
(check-sat)
(pop 1)
