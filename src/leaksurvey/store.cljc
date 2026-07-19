(ns leaksurvey.store
  "SSoT for the water-infrastructure leak-detection Survey Advisor
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami` actor in this fleet
  uses.

  Scope note: like several siblings (e.g. `cloud-itonami-isic-3091`'s
  own `motomfg.store`), this build ships a single `MemStore` backend
  only (atom of EDN) -- the deterministic default for dev/tests/demo,
  no deps.

  Five kinds of entity live here:
    - `segments`              -- the central entity. A distribution-
                                 network pipe segment's zone/material/
                                 install-year record. `:verified?`
                                 marks whether the segment has actually
                                 been surveyed/confirmed (never
                                 inferred from a routine condition-log
                                 patch); `:registered?` marks whether
                                 it is on file in the utility's
                                 distribution-network registry.
    - `sensor-units`           -- an acoustic/pressure leak-sensing
                                 robot's own record. `:verified?`/
                                 `:registered?` track whether it has
                                 actually been inspected/commissioned
                                 and is on file -- the same
                                 ground-truth discipline as `segments`.
    - `readings`               -- append-only measured sensor readings
                                 (`leaksurvey.telemetry/reading`),
                                 keyed by reading-id, indexed by
                                 segment-id for the grounding check.
    - `leak-surveys`           -- a filed leak-survey verdict
                                 (CONFIRMED/NO-LEAK/NEEDS-MORE-DATA),
                                 keyed by leak-id, replaced on each new
                                 filing -- never merged, so a stale
                                 verdict can never linger alongside a
                                 fresh one. `leaksurvey.governor`
                                 independently re-verifies a
                                 CONFIRMED/NO-LEAK verdict is grounded
                                 in cited readings before it may
                                 commit.
    - `repairs`                -- a scheduled repair-recommendation
                                 DRAFT against a segment with an
                                 on-file CONFIRMED leak record
                                 (`leaksurvey.registry`'s
                                 `register-repair-recommendation`).
                                 Dedicated `:repair-recommended?`
                                 double-recommendation guard (never a
                                 `:status` value -- the same discipline
                                 every prior governor's guards
                                 establish, informed by
                                 `cloud-itonami-isic-6492`'s
                                 status-lifecycle bug, ADR-2607071320).

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which segment was logged, which leak
  survey was filed and on what sensor basis, which repair was
  recommended against a verified/registered segment with an on-file
  confirmed leak, approved by whom, which critical leak was escalated'
  is always a query over an immutable log -- the audit trail a utility
  or downstream auditor trusting this inspector needs."
  (:require [leaksurvey.registry :as registry]))

(defprotocol Store
  (segment [s id])
  (all-segments [s])
  (sensor-unit [s id])
  (all-sensor-units [s])
  (reading [s reading-id])
  (readings-for-segment [s segment-id])
  (leak-survey-of [s leak-id])
  (repair [s id] "a scheduled repair-recommendation record, or nil")
  (critical-leak-log [s] "the append-only critical-leak-escalation log")
  (ledger [s])
  (repair-history [s] "the append-only repair-recommendation history (leaksurvey.registry drafts)")
  (next-repair-sequence [s] "next repair-number sequence")
  (next-escalation-sequence [s] "next escalation-number sequence")
  (repair-already-recommended? [s recommendation-id] "has this repair already been recommended?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-segments [s segments] "replace/seed the segment directory (map id->segment)")
  (with-sensor-units [s units] "replace/seed the sensor-unit directory (map id->unit)")
  (with-readings [s readings] "replace/seed the reading directory (map id->reading)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-segments []
  {"segment-001" {:id "segment-001" :zone "downtown-main"
                  :material :ductile-iron :install-year 1988
                  :verified? true :registered? true}
   "segment-002" {:id "segment-002" :zone "east-trunk"
                  :material :pvc :install-year 2004
                  :verified? true :registered? true}
   "segment-003" {:id "segment-003" :zone "west-annex"
                  :material :cast-iron :install-year 1962
                  :verified? false :registered? false}})

(defn- sample-sensor-units []
  {"acoustic-001" {:id "acoustic-001" :kind :acoustic-pressure-crawler
                    :verified? true :registered? true}
   "acoustic-002" {:id "acoustic-002" :kind :acoustic-pressure-crawler
                    :verified? false :registered? false}})

(defn- sample-readings []
  {"reading-001" {:type :sensor-reading :reading-id "reading-001" :segment-id "segment-001"
                   :metric :acoustic-signature :value 0.82 :unit "correlation-index"
                   :sensor-id "acoustic-001" :timestamp "2026-07-10"}
   "reading-002" {:type :sensor-reading :reading-id "reading-002" :segment-id "segment-001"
                   :metric :pressure-differential :value 4.3 :unit "psi"
                   :sensor-id "acoustic-001" :timestamp "2026-07-10"}
   "reading-003" {:type :sensor-reading :reading-id "reading-003" :segment-id "segment-002"
                   :metric :acoustic-signature :value 0.05 :unit "correlation-index"
                   :sensor-id "acoustic-001" :timestamp "2026-07-11"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-repair!
  "Backend-agnostic `:repair/schedule` -- drafts the
  repair-recommendation record via `leaksurvey.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s recommendation-id segment-id leak-id]
  (let [seq-n (next-repair-sequence s)
        result (registry/register-repair-recommendation recommendation-id segment-id leak-id seq-n)]
    {:result result
     :patch {:repair-recommended? true
             :repair-number (get result "repair_number")}}))

(defn- file-escalation!
  "Backend-agnostic `:leak/escalate` -- drafts the
  critical-leak-escalation record via `leaksurvey.registry` and
  returns {:result .. :concern ..} for the caller to persist."
  [s concern-id value]
  (let [seq-n (next-escalation-sequence s)
        result (registry/register-escalation concern-id seq-n)]
    {:result result
     :concern (assoc value :id concern-id :escalation-number (get result "escalation_number"))}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (segment [_ id] (get-in @a [:segments id]))
  (all-segments [_] (sort-by :id (vals (:segments @a))))
  (sensor-unit [_ id] (get-in @a [:sensor-units id]))
  (all-sensor-units [_] (sort-by :id (vals (:sensor-units @a))))
  (reading [_ reading-id] (get-in @a [:readings reading-id]))
  (readings-for-segment [_ segment-id]
    (filterv #(= segment-id (:segment-id %)) (vals (:readings @a))))
  (leak-survey-of [_ leak-id] (get-in @a [:leak-surveys leak-id]))
  (repair [_ id] (get-in @a [:repairs id]))
  (critical-leak-log [_] (:critical-leak-log @a))
  (ledger [_] (:ledger @a))
  (repair-history [_] (:repair-history @a))
  (next-repair-sequence [_] (:repair-sequence @a 0))
  (next-escalation-sequence [_] (:escalation-sequence @a 0))
  (repair-already-recommended? [_ recommendation-id]
    (boolean (get-in @a [:repairs recommendation-id :repair-recommended?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :segment/upsert)
      (swap! a update-in [:segments (first path)] merge (assoc value :id (first path)))

      (= effect :leak/survey-set)
      (swap! a assoc-in [:leak-surveys (first path)] (assoc value :leak-id (first path)))

      (= effect :repair/schedule)
      (let [recommendation-id (first path)
            segment-id (:segment-id value)
            leak-id (:leak-id value)
            {:keys [result patch]} (schedule-repair! s recommendation-id segment-id leak-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :repair-sequence (fnil inc 0))
                       (update-in [:repairs recommendation-id] merge (assoc value :id recommendation-id) patch)
                       (update :repair-history registry/append result)
                       (update-in [:segments segment-id :last-repair-recommended-date]
                                  (fn [_prev] (:scheduled-date value))))))
        result)

      (= effect :leak/escalate)
      (let [concern-id (first path)
            {:keys [result concern]} (file-escalation! s concern-id value)]
        (swap! a (fn [state]
                   (-> state
                       (update :escalation-sequence (fnil inc 0))
                       (update :critical-leak-log conj concern))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `logging`-style siblings expose as their own
      ;; low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-segments [s segments] (when (seq segments) (swap! a assoc :segments segments)) s)
  (with-sensor-units [s units] (when (seq units) (swap! a assoc :sensor-units units)) s)
  (with-readings [s readings] (when (seq readings) (swap! a assoc :readings readings)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:segments {} :sensor-units {} :readings {} :leak-surveys {} :repairs {}
                      :records {} :critical-leak-log []
                      :ledger [] :repair-sequence 0 :repair-history []
                      :escalation-sequence 0})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained segment +
  sensor-unit + reading set -- two verified+registered segments
  (schedulable for repair once a confirmed leak is on file), one
  UNVERIFIED/unregistered segment (blocks any repair recommended
  against it); one verified+registered acoustic-pressure-crawler unit,
  one UNVERIFIED/unregistered unit; segment-001 carries a FULL sensor
  basis (both required metrics, grounding a CONFIRMED verdict),
  segment-002 carries only a PARTIAL basis (one metric, insufficient to
  ground any verdict) -- so the actor + demo + tests run offline.
  Returns `s` (thread-friendly with `->`)."
  [s]
  (with-segments s (sample-segments))
  (with-sensor-units s (sample-sensor-units))
  (with-readings s (sample-readings))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
