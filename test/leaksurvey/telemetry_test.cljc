(ns leaksurvey.telemetry-test
  (:require [clojure.test :refer [deftest is]]
            [leaksurvey.telemetry :as t]))

(defn- mk [id segment metric value]
  (t/reading {:reading-id id :segment-id segment :metric metric :value value :sensor-id "acoustic-001"}))

;; ----------------------------- reading -----------------------------

(deftest reading-constructs-a-valid-shape
  (let [r (mk "r1" "segment-001" :acoustic-signature 0.8)]
    (is (= :sensor-reading (:type r)))
    (is (= "r1" (:reading-id r)))
    (is (= "segment-001" (:segment-id r)))
    (is (= :acoustic-signature (:metric r)))
    (is (= 0.8 (:value r)))))

(deftest reading-requires-reading-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:segment-id "s1" :metric :acoustic-signature :value 1 :sensor-id "a1"}))))

(deftest reading-requires-segment-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :metric :acoustic-signature :value 1 :sensor-id "a1"}))))

(deftest reading-requires-keyword-metric
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :segment-id "s1" :metric "acoustic" :value 1 :sensor-id "a1"}))))

(deftest reading-requires-non-nil-value
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :segment-id "s1" :metric :acoustic-signature :value nil :sensor-id "a1"}))))

(deftest reading-requires-sensor-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :segment-id "s1" :metric :acoustic-signature :value 1}))))

;; ----------------------------- grounds-verdict? -----------------------------

(deftest full-coverage-grounds-a-verdict
  (let [readings [(mk "r1" "segment-001" :acoustic-signature 0.8)
                  (mk "r2" "segment-001" :pressure-differential 4.3)]]
    (is (true? (t/grounds-verdict? "segment-001" ["r1" "r2"] readings)))))

(deftest partial-coverage-does-not-ground
  (let [readings [(mk "r1" "segment-001" :acoustic-signature 0.8)]]
    (is (false? (t/grounds-verdict? "segment-001" ["r1"] readings)))))

(deftest no-citations-does-not-ground
  (let [readings [(mk "r1" "segment-001" :acoustic-signature 0.8)
                  (mk "r2" "segment-001" :pressure-differential 4.3)]]
    (is (false? (t/grounds-verdict? "segment-001" [] readings)))
    (is (false? (t/grounds-verdict? "segment-001" nil readings)))))

(deftest citing-another-segments-reading-does-not-ground
  (let [readings [(mk "r1" "segment-001" :acoustic-signature 0.8)
                  (mk "r2" "segment-002" :pressure-differential 4.3)]]
    (is (false? (t/grounds-verdict? "segment-001" ["r1" "r2"] readings))
        "r2 belongs to segment-002, not segment-001 -- citing it grounds nothing")))

(deftest citing-a-nonexistent-reading-does-not-ground
  (let [readings [(mk "r1" "segment-001" :acoustic-signature 0.8)
                  (mk "r2" "segment-001" :pressure-differential 4.3)]]
    (is (false? (t/grounds-verdict? "segment-001" ["r1" "r2" "r-does-not-exist"] readings))
        "a cited id that resolves to nothing shrinks the distinct-count match")))

(deftest citing-the-same-reading-twice-does-not-widen-coverage
  (let [readings [(mk "r1" "segment-001" :acoustic-signature 0.8)]]
    (is (false? (t/grounds-verdict? "segment-001" ["r1" "r1"] readings))
        "duplicate citation of the same reading cannot substitute for the missing metric")))

(deftest readings-by-id-indexes-last-wins
  (let [r1 (mk "r1" "segment-001" :acoustic-signature 0.5)
        r1b (mk "r1" "segment-001" :acoustic-signature 0.9)
        idx (t/readings-by-id [r1 r1b])]
    (is (= 0.9 (:value (get idx "r1"))))))
