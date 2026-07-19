(ns leaksurvey.phase
  "Phase 0->3 staged rollout for the water-infrastructure
  leak-detection Survey Advisor actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- pipe-condition logging allowed,
                                    every write needs human approval.
    Phase 2  assisted-survey    -- adds leak-survey filing, still
                                    approval.
    Phase 3  supervised-auto    -- adds repair-recommendation
                                    scheduling and critical-leak
                                    escalation (still always approval
                                    -- see below); governor-clean,
                                    high-confidence
                                    `:log-pipe-condition` (no physical/
                                    financial risk) may auto-commit.

  `:schedule-repair-recommendation` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Recommending a real
  repair against a segment is the one act in this domain with physical
  consequence (a repair crew is dispatched and a valve/main is
  actually touched); it is always a human survey coordinator's call.
  `leaksurvey.governor`'s `valve-actuate-blocked-violations`
  HARD-blocks actuate attempts unconditionally, and the confidence/
  high-stakes gate independently never lets `:escalate-critical-leak`
  auto-commit either -- multiple independent layers agree on where
  this actor's authority ends. Like every prior sibling's phase-3
  `:auto` set, this domain has only ONE member
  (`:log-pipe-condition`) -- no separate no-risk lifecycle distinct
  from ordinary record logging.")

(def write-ops
  #{:log-pipe-condition :leak-survey
    :schedule-repair-recommendation :escalate-critical-leak})

;; NOTE the invariant: `:schedule-repair-recommendation` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                   :auto #{}}
   1 {:label "assisted-intake" :writes #{:log-pipe-condition}                :auto #{}}
   2 {:label "assisted-survey" :writes #{:log-pipe-condition :leak-survey}   :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:log-pipe-condition}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-repair-recommendation` is never auto-eligible at any
    phase, so it always escalates once the governor clears it (or
    holds if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Water Infrastructure Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
