(ns leaksurvey.telemetry
  "Sensor-data ingestion + the grounding logic `leaksurvey.governor`
  uses to enforce this blueprint's own invariant: 'a leak-survey
  CONFIRMED/NO-LEAK verdict must reference measured sensor data, never
  advisor self-attestation'. Modeled on `cloud-itonami-unspsc-27`'s
  `formation.telemetry` -- the same, well-tested pattern that makes a
  condition/safety *claim* provably traceable to measured sensor
  readings, adapted from a per-tool-class sensor surface to this
  domain's fixed acoustic+pressure survey surface (a leak-detection
  survey has no per-material-class variation the way a tool-fleet
  inspection does -- every pipe segment, regardless of material, is
  surveyed with the same acoustic/pressure sensing method).

  Two responsibilities:

  1. Sensor-reading shape + constructor. A reading is an immutable
     measured fact: what metric, what value, which sensor unit, when,
     for which segment. The STORE owns persistence
     (`leaksurvey.store`); this namespace owns the shape, the
     validation, and the pure grounding logic.

  2. Pure grounding logic. `grounds-verdict?` answers: do the readings
     a leak-survey proposal cites as its `:sensor-basis` actually cover
     every sensor-metric a CONFIRMED/NO-LEAK verdict requires? This is
     the technical kernel of the 'no self-attested leak finding' HARD
     invariant: an untrusted advisor (a real, possibly-hallucinating
     LLM) must not be able to declare a segment leaking (or clear) from
     thin air -- every such verdict must be backed by cited readings
     whose metrics cover BOTH the acoustic-signature and
     pressure-differential surface.

  Grounding is REQUIRED for any leak-survey verdict that asserts a
  condition (`:confirmed` -- 'this segment is leaking' / `:no-leak` --
  'this segment is not leaking'). A `:needs-more-data` verdict ('I
  could not determine') is the honest 'no basis yet' outcome -- it
  escalates for a human / re-survey and is explicitly exempt, because
  requiring a basis for 'I don't know yet' would punish honesty.")

;; A sensor reading is a plain map (NOT a defrecord) so it round-trips
;; through pr-str / edn/read-string unchanged -- a defrecord would emit
;; a tagged literal that edn/read-string cannot read back without a
;; registered reader.

(def required-metrics
  "The fixed sensor-metric surface a leak-survey CONFIRMED/NO-LEAK
  verdict must cite readings covering. Unlike `formation.telemetry`'s
  per-tool-class surface, this domain has ONE survey method (acoustic +
  pressure sensing) applied uniformly regardless of pipe material --
  there is no per-material-class variation to inject a lookup function
  for."
  #{:acoustic-signature :pressure-differential})

(defn reading
  "Construct + validate a sensor reading. `metric` should be one of
  `required-metrics` (or `:flow-rate`, an optional third metric this
  domain also tracks but does not require for grounding). Throws on
  the shape errors that would silently corrupt the grounding check
  (missing ids / nil value / non-keyword metric)."
  [{:keys [reading-id segment-id metric value unit sensor-id timestamp]
    :or {unit "" timestamp nil}}]
  (when-not (and reading-id (not= reading-id ""))
    (throw (ex-info "sensor reading: reading-id required" {})))
  (when-not (and segment-id (not= segment-id ""))
    (throw (ex-info "sensor reading: segment-id required" {})))
  (when-not (keyword? metric)
    (throw (ex-info (str "sensor reading: metric must be a keyword, got " (pr-str metric)) {})))
  (when (nil? value)
    (throw (ex-info "sensor reading: value required (nil is not a measurement)" {})))
  (when-not (and sensor-id (not= sensor-id ""))
    (throw (ex-info "sensor reading: sensor-id required" {})))
  {:type :sensor-reading :reading-id reading-id :segment-id segment-id :metric metric
   :value value :unit (or unit "") :sensor-id sensor-id :timestamp timestamp})

(defn readings-by-id
  "Index a seq of readings by their reading-id (last wins on collision)."
  [readings]
  (into {} (map (juxt :reading-id identity)) readings))

(defn grounds-verdict?
  "Does `cited-ids` (the reading-ids a leak-survey proposal claims as
  its `:sensor-basis`) actually ground a CONFIRMED/NO-LEAK verdict for
  `segment-id`?

   - Every cited id must resolve to a REAL reading for `segment-id` (a
     cited id that points at another segment's reading, or at nothing,
     grounds nothing -- it is the advisor naming evidence it does not
     have).
   - Every cited id must be DISTINCT (citing the same reading twice
     does not widen coverage).
   - The union of the resolved readings' metrics must cover EVERY
     metric in `required-metrics`. Partial coverage is not grounding:
     'I measured the acoustic signature but not the pressure
     differential' does not substantiate 'this segment is leaking'.

  Returns true only on full coverage; no cited ids -> false (a verdict
  with no basis at all can never be grounded through this gate)."
  [segment-id cited-ids readings]
  (let [by-id (readings-by-id readings)
        ids (seq cited-ids)
        resolved (keep (fn [rid]
                          (let [r (get by-id rid)]
                            (when (and r (= (:segment-id r) segment-id)) r)))
                        ids)]
    (boolean
     (and ids
          (= (count resolved) (count (distinct ids)))
          (every? #(contains? (set (map :metric resolved)) %) required-metrics)))))
