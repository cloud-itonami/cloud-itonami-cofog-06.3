(ns leaksurvey.registry
  "Pure-function domain logic for the water-infrastructure
  leak-detection Survey Advisor actor -- segment/sensor-unit
  verification, pipe-material validation, flow-loss plausibility
  validation, and draft repair-recommendation/escalation record
  construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has no
  pre-existing `kotoba-lang/leaksurvey`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `leaksurvey.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes: never
  trust a proposal's own self-reported material/flow-loss/leak-status
  claim when the inputs needed to independently validate it are
  already on record, or are simple physical-plausibility bounds.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real SCADA or utility-billing system. It builds the
  DRAFT record a survey coordinator would keep (a scheduled repair
  recommendation, a filed critical-leak escalation), not the act of
  actuating a valve/main directly, and never a water-utility's own
  regulatory compliance filing (this actor NEVER does either -- see
  README `What this actor does NOT do`).

  SCOPE: COFOG 06.3 covers water-supply distribution-network
  maintenance -- pipe-segment condition logging, acoustic/pressure
  leak surveying, and repair-recommendation coordination for a
  contracted leak-detection inspector. This actor coordinates the
  back-office record-keeping around that survey work (pipe-condition
  logging, leak-survey filing, repair-recommendation scheduling,
  critical-leak escalation) -- it never actuates a valve or main
  directly, and it never stands in for a water utility's own
  regulatory compliance authority.

  Independent water-audit context (informational, not a claim of
  compliance): many U.S. water utilities account for distribution
  losses using the IWA/AWWA Water Audit Methodology (AWWA Manual M36,
  'Water Audits and Loss Control Programs' -- verified real via AWWA's
  own published catalog during this build's research). The measured
  leak/flow-loss data this actor logs is the kind of independently
  verified input such an audit would consume; this software does not
  itself perform or certify an M36-compliant audit.")

;; ----------------------------- constants -----------------------------

(def valid-pipe-materials
  "The closed set of pipe-material values a segment condition record
  may declare -- spans the materials a municipal distribution network
  actually uses. Anything else is a fabricated/unrecognized material --
  the governor HARD-holds rather than let an invented material pass
  through."
  #{:ductile-iron :cast-iron :pvc :hdpe :concrete :steel :asbestos-cement :copper})

(def flow-loss-min-lpm
  "Physical floor for a leak's own measured flow-loss reading (a
  no-leak segment legitimately reports 0)."
  0.0)

(def flow-loss-max-lpm
  "Physical ceiling for a single leak's own measured flow-loss reading
  (liters per minute) -- generous enough to cover a major distribution
  main break, but bounded so an implausible/sensor-error reading is
  rejected rather than silently accepted into a leak record."
  10000.0)

;; ----------------------------- segment checks -----------------------------

(defn segment-verified?
  "Ground-truth check: has `segment`'s own record been marked verified
  (i.e. it has actually been surveyed/confirmed by the utility or
  inspector, not merely referenced from an unverified repair request)?
  A pure predicate over the segment's own permanent field -- no
  proposal inspection needed."
  [segment]
  (true? (:verified? segment)))

(defn segment-registered?
  "Ground-truth check: does `segment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the utility's
  distribution-network registry)? Recommending a repair against a
  segment that is not on file and registered is the exact scope
  violation this actor's HARD invariant ('segment/sensor-unit record
  must be independently verified/registered before any action') exists
  to block."
  [segment]
  (true? (:registered? segment)))

(defn segment-ready?
  "Combined ground-truth gate: the segment must be both `verified?` AND
  `registered?` before ANY repair recommendation may be scheduled
  against it. Two independent facts on the segment's own permanent
  record, neither inferred from the advisor's own rationale."
  [segment]
  (and (segment-verified? segment) (segment-registered? segment)))

;; ----------------------------- sensor-unit checks -----------------------------

(defn sensor-unit-verified?
  "Ground-truth check: has `unit`'s own record been marked verified
  (i.e. the acoustic/pressure leak-sensing robot has actually been
  inspected/commissioned and registered in the SSoT)?"
  [unit]
  (true? (:verified? unit)))

(defn sensor-unit-registered?
  "Ground-truth check: does `unit`'s own record carry a `:registered?`
  true flag (i.e. it is on file in the inspector's sensor-fleet
  registry)?"
  [unit]
  (true? (:registered? unit)))

(defn sensor-unit-ready?
  "Combined ground-truth gate: the sensor unit must be both
  `verified?` AND `registered?` before its readings may be treated as
  a trustworthy survey basis."
  [unit]
  (and (sensor-unit-verified? unit) (sensor-unit-registered? unit)))

;; ----------------------------- record-field validation -----------------------------

(defn pipe-material-valid?
  "Is `material` one of the closed, known pipe-material values a
  distribution network actually uses? nil/blank is treated as invalid
  (a pipe-condition patch must declare a real material, not omit it
  silently)."
  [material]
  (contains? valid-pipe-materials material))

(defn flow-loss-valid?
  "Is `lpm` a physically plausible measured leak flow-loss reading?
  Rejects nil, non-numbers, negative values, and values beyond
  `flow-loss-max-lpm` -- a fabricated or sensor-error reading, never
  let through as a real leak fact."
  [lpm]
  (and (number? lpm)
       (>= (double lpm) flow-loss-min-lpm)
       (<= (double lpm) flow-loss-max-lpm)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human survey coordinator's/utility engineer's act, not this
  actor's. And NEVER a water-utility regulatory compliance filing --
  this actor is never the utility's own compliance-reporting authority
  (see README `What this actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-repair-recommendation
  "Validate + construct the REPAIR-RECOMMENDATION DRAFT -- a proposed
  repair against a verified, registered segment with an on-file
  CONFIRMED leak record. Pure function -- does not actuate any
  valve/main or execute any repair; it builds the RECORD a survey
  coordinator would keep. `leaksurvey.governor` independently
  re-verifies the segment's own verified/registered ground truth and
  the leak record's own confirmed status, and permanently blocks any
  attempt to directly actuate a valve/main (see README `Actuation`),
  before this is ever allowed to commit."
  [recommendation-id segment-id leak-id sequence]
  (when-not (and recommendation-id (not= recommendation-id ""))
    (throw (ex-info "repair-recommendation: recommendation_id required" {})))
  (when-not (and segment-id (not= segment-id ""))
    (throw (ex-info "repair-recommendation: segment_id required" {})))
  (when-not (and leak-id (not= leak-id ""))
    (throw (ex-info "repair-recommendation: leak_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "repair-recommendation: sequence must be >= 0" {})))
  (let [repair-number (str "RPR-" (zero-pad sequence 6))
        record {"record_id" repair-number
                "kind" "repair-recommendation-draft"
                "recommendation_id" recommendation-id
                "segment_id" segment-id
                "leak_id" leak-id
                "immutable" true}]
    {"record" record "repair_number" repair-number
     "certificate" (unsigned-certificate "RepairRecommendation" repair-number repair-number)}))

(defn register-escalation
  "Validate + construct the CRITICAL-LEAK-ESCALATION DRAFT -- a filed
  acute water-safety/pressure-loss concern, routed to a human for
  sign-off. Pure function -- does not itself close a valve, isolate a
  main, or dispatch a repair crew; it builds the RECORD a survey
  coordinator would keep pending human review. The Safe Drinking Water
  Act (verified real via EPA's own published program materials during
  this build's research) is the U.S. federal framework governing
  drinking-water safety; some leaks (e.g. those risking contamination
  ingress at negative main pressure, or major supply disruption) are
  the kind of finding such a framework treats as urgent. This record's
  own never-auto-resolved posture is the software-side analog of
  routing that finding to the human water-safety authority who
  actually holds compliance responsibility -- not a claim that this
  software itself performs a Safe Drinking Water Act determination."
  [concern-id sequence]
  (when-not (and concern-id (not= concern-id ""))
    (throw (ex-info "escalation: concern_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "escalation: sequence must be >= 0" {})))
  (let [escalation-number (str "ESC-" (zero-pad sequence 6))
        record {"record_id" escalation-number
                "kind" "critical-leak-escalation-draft"
                "concern_id" concern-id
                "immutable" true}]
    {"record" record "escalation_number" escalation-number
     "certificate" (unsigned-certificate "CriticalLeakEscalation" escalation-number escalation-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
