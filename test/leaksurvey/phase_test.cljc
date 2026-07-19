(ns leaksurvey.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-repair-recommendation` must NEVER be a member
  of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [leaksurvey.phase :as phase]))

(deftest schedule-repair-recommendation-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real repair recommendation"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-repair-recommendation))
          (str "phase " n " must not auto-commit :schedule-repair-recommendation")))))

(deftest escalate-critical-leak-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :escalate-critical-leak))
        (str "phase " n " must not auto-commit :escalate-critical-leak"))))

(deftest leak-survey-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :leak-survey))
        (str "phase " n " must not auto-commit :leak-survey"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-pipe-condition carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-pipe-condition} (:auto (get phase/phases 3))))))

(deftest schedule-repair-recommendation-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-repair-recommendation))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-repair-recommendation)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-repair-recommendation))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-pipe-condition} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-repair-recommendation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :escalate-critical-leak} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :leak-survey} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-pipe-condition} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-pipe-condition} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
