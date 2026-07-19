(ns metalforming.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6 / iteration 16): this repo previously had
  NO demo page and no generator at all. This namespace drives the REAL
  actor stack (`metalforming.operation` -> `metalforming.governor` ->
  `metalforming.store`) through a scenario adapted from this repo's own
  `metalforming.sim` demo driver (`clojure -M:dev:run`, confirmed to run
  correctly against the real seeded batch/equipment directory before
  this file was written -- `metalforming.sim`'s own request ids
  (\"batch-001\"/\"batch-002\"/\"batch-003\"/\"press-001\"/\"roll-002\")
  DO match `metalforming.store/sample-data!`'s real seed data, and every
  claimed HARD-hold rule in its printed banner was independently
  cross-checked against the actual `:violations` returned by a real run
  -- so it was safe to adapt from rather than author from scratch,
  unlike `cloud-itonami-isic-851`'s `schoolops.sim`), trimmed to a
  representative subset (one full auto-commit, three
  escalate-then-approve lifecycles, and four distinct HARD-hold
  reasons) and rendered deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns against
  the same seed (verified by diffing two consecutive runs before
  shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [metalforming.store :as store]
            [metalforming.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach:

  Clean / approved paths:
    - batch-001 gets a clean product-category/last-assessed patch ->
      phase-3 auto-commit (`:log-production-batch` is the only op in
      this phase's `:auto` set, no physical/financial risk).
    - mnt-1 schedules a die-inspection maintenance window against
      press-001 (verified + registered forging press, not an
      actuate attempt) -> ALWAYS escalates (`:schedule-maintenance` is
      never in any phase's `:auto` set) -> human plant supervisor
      approves -> commits.
    - concern-1 flags a moderate pinch-point-guard safety concern
      against press-001 -> ALWAYS escalates
      (`:coordination/safety-concern` is always high-stakes) -> human
      approves -> commits.
    - ship-1 coordinates a 500kg shipment against batch-001 (verified +
      registered, well within its 1800kg logged weight) -> ALWAYS
      escalates -> human shipping approver approves -> commits.

  HARD-hold paths (never reach a human, no override possible):
    - mnt-2 tries to schedule maintenance against roll-002, a
      roll-forming mill that is UNVERIFIED/unregistered ->
      `:equipment-not-verified`.
    - ship-2 tries to coordinate a shipment against batch-003, a
      sintered-gear-blank lot that is UNVERIFIED/unregistered ->
      `:batch-not-verified`.
    - ship-3 tries to coordinate a 600kg shipment against batch-002,
      whose own logged weight (5000kg) already has 4700kg shipped, so
      600kg more would exceed it (5300kg > 5000kg) ->
      `:shipment-weight-exceeded` (independently recomputed from the
      batch's own permanent fields, never the proposal's self-report).
    - mnt-3 tries to schedule maintenance against press-001 with
      `:actuate-forge-press-line? true` -> `:forge-press-line-actuate-
      blocked`, this actor's own central, permanent scope boundary:
      no phase and no human approval can ever override direct
      forging/pressing-line-equipment actuation.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (exec! actor "t1-batch-001-intake"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :forged-part :last-assessed "2026-07-14"}})

    (exec! actor "t2-mnt-1-schedule"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "press-001" :maintenance-type :die-inspection
                    :scheduled-date "2026-08-01" :actuate-forge-press-line? false}})
    (approve! actor "t2-mnt-1-schedule")

    (exec! actor "t3-concern-1-flag"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "press-001" :severity :moderate
                    :description "forging press pinch-point guard found loose during inspection"}})
    (approve! actor "t3-concern-1-flag")

    (exec! actor "t4-ship-1-coordinate"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 500.0
                    :destination "buyer-yard-north"}})
    (approve! actor "t4-ship-1-coordinate")

    (exec! actor "t5-mnt-2-unverified-equipment"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "roll-002" :maintenance-type :roll-alignment
                    :scheduled-date "2026-08-01" :actuate-forge-press-line? false}})

    (exec! actor "t6-ship-2-unverified-batch"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :weight-kg 500.0
                    :destination "buyer-yard-south"}})

    (exec! actor "t7-ship-3-weight-exceeded"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 600.0
                    :destination "buyer-yard-east"}})

    (exec! actor "t8-mnt-3-actuate-blocked"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "press-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-forge-press-line? true}})

    db))

;; ----------------------------- rendering (structure mirrors the rest
;; of the flagship cluster; column labels + row extraction are
;; domain-specific) -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id product-category process-type weight-kg
                                  defect-rate-percent verified? registered?
                                  shipped-weight-kg]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or product-category :n-a))) (esc (name (or process-type :n-a)))
          (esc weight-kg) (esc (or shipped-weight-kg 0.0)) (esc defect-rate-percent)
          (if verified? "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>")
          (if registered? "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>")
          (status-cell ledger id)))

(defn- equipment-row [{:keys [id kind verified? registered? last-maintenance-date
                               last-scheduled-maintenance-date]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or kind :n-a)))
          (if verified? "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>")
          (if registered? "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>")
          (esc (or last-maintenance-date "n/a"))
          (esc (or last-scheduled-maintenance-date "n/a"))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `What
  ;; this actor does`, `metalforming.governor`/`metalforming.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">auto-commit when clean, phase 3</span> &middot; product-category/defect-rate independently validated</td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; equipment verified/registered independently re-checked &middot; direct forging/pressing-line actuation <span class=\"critical\">permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; high-stakes, never auto-eligible at any phase</td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval</span> &middot; batch verified/registered + cumulative shipped weight independently recomputed</td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map equipment-row equipment))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-2591 &middot; forging/pressing/stamping/roll-forming plant operations</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Forging/pressing/stamping/roll-forming plant operations (ISIC 2591) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · forge/press-line actuation always permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>metalforming.store</code> via <code>metalforming.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product category</th><th>Process</th><th>Weight (kg)</th><th>Shipped (kg)</th><th>Defect rate (%)</th><th>Verified?</th><th>Registered?</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Equipment (forging press / stamping press / roll-forming mill)</h2>\n"
     "    <p class=\"muted\">Maintenance may only be scheduled against equipment independently verified AND registered — never trusted from a proposal's own rationale.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Verified?</th><th>Registered?</th><th>Last maintenance</th><th>Last scheduled maintenance</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Metal Forming Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Forging-press/stamping-press/roll-forming-mill/powder-metallurgy-press-and-sinter-line actuation is a permanent scope boundary — this actor only ever drafts proposals, it never actuates equipment.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-batches db)) "batches,"
             (count (store/all-equipment db)) "equipment units )")))
