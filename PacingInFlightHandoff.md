# Pacing: the in-flight capture term — handoff

Date: 2026-09-03, revised 2026-09-04 with the three review follow-ups (§5, §6). Written so a fresh
session with no memory of the conversation can pick this up.

Read `PacingInvestigationHandoff.md` first (the diagnosis this all sits on), then
`PacingLeadTermFindings.md` (the queue-growth term, already on `master`). This document covers only
what came after: why every Lv3 12MP burst still died, and the term added to fix it.

---

## 1. Where things stand

### On `master`

| commit | change |
|---|---|
| `a40ea69` | **Price the backlog one arrival ahead of the decision.** Adds `backlogGrowthMs`, the recency-weighted mean of `backlogMs(k) − backlogMs(k−1)` within the burst, floored at 0. |
| `2433af7` | The two investigation documents. |
| `8d5e55d` | **Stop halving the queue growth with the deficit.** The growth joins after the `/2`, not inside it: the deficit is a one-off shortfall, the growth is a rate. |

Those were measured on 0829 data and confirmed on device by the 0903 run
(`SEIP'27/data/S26/SM-S942B_metrics_24MP_memory_0903_original.xlsx`, 68 runs, 10 timeouts). The user
reported that measurement as clearly better.

### Set aside, no longer in the working tree

The in-flight capture term (§5) plus the three follow-ups from the 2026-09-04 review (§5, *Three
follow-ups*): 8 files, +180/−14. **Not compiled** — there is still no Kotlin toolchain in this repo,
and the self-checks in §7 have not been updated for the follow-ups.

It was moved out of the tree on 2026-09-04 so the backlog-clock change below could be measured on its
own. Two copies, both taken from the same tree state:

- `patches/pacing-inflight-term.patch` (tracked, carries its own header)
- the `git stash` entry made at the same moment

Re-apply with `git apply -3 patches/pacing-inflight-term.patch`. A plain apply will conflict: the
backlog-clock change edits `endDraftSequence` and `dequeuePacingDecision`, next door to this patch's
`setCaptureDeadlineMs` and snapshot hunks.

### In the working tree instead: the backlog clock rebases at the draft end

`CaptureAvailablePacer`, `CaptureAvailablePacingSession`, `DraftSequenceExecutionProfiler` — 3 files.
`rebaseBacklogClock` moved off `dequeuePacingDecision` (a draft *start*) and onto `endDraftSequence`
plus a revived `cancelDraftSequence`, and it now re-asks the predictor for each queued draft's point
work instead of summing the snapshots they were admitted with.

**Why the draft end.** Nothing is running there, so the admitted queue *is* the whole remaining work
and the rebase carries no running draft out of band — the `startingWorkloadPredictedDurationMs`
parameter it used to take was a symptom of firing at the wrong instant. And the predictor has just
learned from that draft, one line above the call in
`DraftSequenceExecutionProfiler.completeDraftSequenceExecution`.

**What it fixes.** A queued draft's snapshot is up to `queuedDraftCount` draft starts old, and on a
thermal ramp every one of them is cheap. That is a **level** error in the completion-time estimate,
and the growth trend cannot correct a level error: growth is a difference of two consecutive
backlogs, so a bias present in both cancels out of every sample it teaches. Both terms were biased
the same way (low), so both should read higher afterwards — **this change can only raise delay, and
most of it on a ramp.**

**Why `cancelDraftSequence` had to come back.** `12148eb` deleted it as a no-op, correctly: back then
`endDraftSequence` only taught a maximum, and a draft that never ran teaches none. Now the draft end
is the clock's only correction point, so a cancelled draft would leave its admitted price in the
clock for the rest of the burst. `DraftSequenceExecutionProfiler.cancelDraftSequenceExecution` calls
it, and the KDoc there that said the pacer needs no cancellation hook is corrected.

**Open on it.** Whether it stays silent on healthy bursts (the gate the in-flight term passed at 0 of
111 decisions); and the recorded `queuedPredictedWorkMs` is still the admission-time sum, so it no
longer explains the `backlogMs` the clock reports. Not compiled either.

---

## 2. The problem

`SEIP'27/data/S26/problem.zip` → `42B_metrics.xlsx` (175 captures, 8 runs) and `timeout.txt`
(73,688 lines of logcat). Unzip to `problem_x/`; the scripts expect that path.

12MP shooting at overheat level 3 dies essentially every time.

| start Lv | runs | shots | timeout | draft 1 wall | shutters inside draft 1 | wall / shutter interval |
|---|---|---|---|---|---|---|
| 0–2 | 4 | 31–33 | none | 472–600 ms | 5–7 | 1.7–2.3× |
| 3 | 4 | 11–15 | **all 4, on the last shot** | 774–924 ms | 7–9 | **2.9–4.0×** |

The shutter interval is the same in every run (200–290 ms). What changes at Lv3 is the draft wall.
So "the user shoots too fast" is really "the user shoots too fast *for how long a draft now takes*".

**Fidelity gate:** the shipped formula reproduces **151 / 151** recorded delays on this workbook, so
this build is modelled exactly. The recording carries `beforeBacklogGrowthMs`, and its reserve
safety factor is **1.0** (`rpacer2.detect_safety` — do not assume 1.1, see the handoff's trap 10).

---

## 3. What the log and the metrics each showed

### The log: nine captures leave with no pacing at all

`grep executeInternal timeout.txt` — 22 lines for one burst:

| ids | count | log line |
|---|---|---|
| 29, 30, 33, 36 | 4 | `no pacing decider published - no pacing` |
| 38, 39, 41, 44, 47 | 5 | `captureAvailable pacing waits for the first draft snapshot` |
| 50 | | first real decision, `appliedDelay=0ms` |
| 55 | | first non-zero delay, 106 ms |

The first four are outside the pacer entirely: `getApmDataProvider(PacingResultData.class)` has not
published a decider yet, so `decideDelay()` is never called. **That is an APM plumbing question for
whoever owns that path, and this work does not address it.** The next five are
`decideDelay()` returning null because `pacingSnapshot` is still null before the first draft start.

### The metrics: the pacer's whole view lags the shutter stream

`7000 − timeToDeadlineMs` is how long ago the capture being priced was shot. It runs **1.5–2.3 s**
in these bursts while shutters arrive every ~200 ms, so 7–8 further captures are already committed
by the time the pacer prices this one — and none of them is in `backlogMs`, which counts only what
pacing admitted.

Dividing that lag by the shutter cadence separates the two populations completely:

| start Lv | in-flight at the first decision | run median |
|---|---|---|
| 0–2 (healthy) | 2.2 – 3.4 | 2.2 – 2.7 |
| 3 (all died) | **5.3 – 8.2** | 5.8 – 8.6 |

**No overlap, and it is readable at the very first decision.**

### Why the growth term alone cannot reach these runs

Only decisions released before the timed-out capture's shutter can move that capture's deadline.
Counting them per run:

| run | shots | margin | reachable decisions | growth available there |
|---|---|---|---|---|
| 5 | 15 | −158 ms | 7 (shots 3–9) | 566–575 |
| 6 | 11 | −64 ms | 3 (shots 3–5) | 0, 670, 670 |
| 7 | 11 | −195 ms | 2 (shots 3–4) | 0, 704 |
| 8 | 11 | −77 ms | **1 (shot 3 only)** | **0** |

The growth needs two decisions to produce its first sample, and run 8's only chance is the first
decision. Required growth at those decisions is 736–1877 ms against an actual 566–704.

`deficit / 2` sits at −840 to −1475 there, because `ttd` never shrinks: it is measured against the
**newest** committed capture, which in a fast burst was shot ~1.5 s ago and still has ~5.5 s left.

---

## 4. The term

Charge the captures the backlog cannot see.

```kotlin
shutterLagMs         = trend of (CAPTURE_TIMEOUT_MS − timeToDeadlineMs) over this burst's decisions
inFlightCaptureCount = min(shutterLagMs, timeToDeadlineMs) / shutterIntervalMs
unseenCaptureCount   = inFlightCaptureCount − PACING_WINDOW_DRAFT_COUNT − queuedDraftCount
unseenCaptureWorkMs  = max(0, unseenCaptureCount × reserve)
```

and it joins the backlog inside the existing completion-time estimate.

The lag was a raw per-decision sample and the count was uncapped in the first version; both changed in
the 2026-09-04 follow-ups (§5), which is why the first two lines read as they do. The rest is
unchanged.

Both subtractions are existing quantities, not tuned numbers: `PACING_WINDOW_DRAFT_COUNT` captures
are the reserves the deficit already prices, and `queuedDraftCount` more are already inside
`backlogMs`. That is what makes the term vanish on a burst the pipeline is keeping up with instead
of taxing it.

`shutterIntervalMs` is learned in the session from the deadline stream: `updateBacklogDeadlineMs`
already receives every committed capture's deadline, so consecutive deadlines give the shot-to-shot
time **from the burst's second capture** — before any draft has finished. That is the whole point;
the decisions themselves arrive far too late in these bursts to describe how fast the user is
shooting.

### Why `− PACING_WINDOW_DRAFT_COUNT` and not `− 1`

Measured, on both the failing workbook and the one that is now working:

| subtraction | 42B: fires on timeout-run decisions | 0903: fires on healthy decisions | 0903 cost |
|---|---|---|---|
| `n − 1 − q` | 28 / 40 | 89 / 1535 | +34 ms/shot |
| **`n − 2 − q`** (shipped) | **26 / 40** | **37 / 1535** | **+9.9 ms/shot** |
| `n − 3 − q` | 22 / 40 | 7 / 1535 | +1.2 ms/shot |

`n − 2 − q` is the one with a reason behind it rather than a fitted constant; `n − 3` has no third
draft to point at.

### Why the count is estimated and not counted exactly — measured 2026-09-04

The count is available exactly, with no cadence, no lag trend and no cap: `updateBacklogDeadlineMs`
fires once per committed capture and `dequeuePacingDecision` once per draft start, so two ints give
`inFlightCaptureCount = commits − draftStarts`. Reconstructed on 42B it reproduces the in-flight
population exactly. It was measured, and **rejected**.

| arm | healthy decisions changed | healthy extra | first paced, runs 5–8 |
|---|---|---|---|
| **`lag ÷ cadence`, shipped** | **0 / 111** | **+0 ms** | **5, 3, 3, 3** |
| exact count, `n − 2 − q` | 59 / 111 | +35 254 ms | 4, 3, 3, 3 |
| exact count, `n − 3 − q` | 51 / 111 | +19 861 ms | 4, 4, 3, 3 |
| exact count, `n − 4 − q` | 25 / 111 | +6 895 ms | 5, 4, 4, 3 |
| exact count, `n − 5 − q` | 0 / 111 | +0 ms | 7, 5, 4, 4 |

The `− q` subtraction is what retires the term, and it only retires it if `n` does *not* track the
queue. `lag ÷ cadence` stays roughly flat across a burst while `queuedDraftCount` climbs, so
`n − 2 − q` goes negative once the pipeline has admitted what was in flight. The exact count is itself
a queue length and climbs *with* q — `n − q` sits at ~4 for the whole of a healthy run — so nothing
retires and the term charges two reserves on every decision, forever. Buying the silence back costs
`− 5`, a fitted constant, and still reaches the dying runs later than the estimate does.

Separation at the first decision is not better either: exact count 4–5 healthy against 6–8 dying, the
estimate 1.6–3.8 against 4.8–6.4. Same gap of about one capture, and the estimate's is the one that
sits on the right side of a `− 2` written for a reason.

So the estimate's low bias is load-bearing, not incidental. If anyone re-proposes the counter — and it
is the obvious simplification, which is why this note exists — it needs a subtraction that grows with
the queue too, and that is a different design, not a swap.

---

## 5. What was implemented

Eight files, +180/−14, the follow-ups below included. No new constant. No migration
(`CaptureMetricsDatabase` is pinned at `version = 1` with `fallbackToDestructiveMigration`, the
project's standing arrangement).

| file | change |
|---|---|
| `CaptureAvailablePacingSession.kt` | `shutterIntervalsMs` trend learned in `updateBacklogDeadlineMs`; `getShutterIntervalMs()`; **(a)** `shutterLagsMs` trend and `observeShutterLagMs()` |
| `CaptureAvailablePacer.kt` | `estimateUnseenCaptureWorkMs()`, three new parameters on `computePacingDelayMs`, `shutterIntervalMs` and **(a)** `shutterLagMs` on the decision, **(b)** the remaining-window cap, **(c)** `setCaptureDeadlineMs` opens the session |
| `CaptureMetrics.kt`, `CaptureMetricsEntities.kt`, `CaptureMetricsMappers.kt` | persist `shutterIntervalMs` and **(a)** `shutterLagMs` |
| `CaptureMetricsExcelExporter.kt` | 3 call sites, `beforeShutterIntervalMs`, `beforeUnseenCaptureCount` and **(a)** `beforeShutterLagMs` columns, and the `Pacing in-flight captures` ReplayNote |
| `external/apm/policy/CaptureAvailableApmPolicy.java` | `shutterInterval=` in the pacing log |
| `external/draftSaving/SavingDraftImageTaskManager.java` | **(c)** call-site comment, which claimed `decideDelay` opens the session first on that path |

The persistence chain is **not optional**. `computePacingDelayMs` has four call sites and three are
the exporter re-evaluating the same formula on recorded rows; a Kotlin default would compile with
two files touched and silently make those columns a different controller than the one that ran.

The cadence guard reads against `backlogDeadlineMs` itself — the value the method is about to
replace is the previous deadline, so the trend needs no second copy of it.

### Three follow-ups, applied 2026-09-04

A review of the above raised three defects. All three are in the tree; measurements in §6.

**(a) The lag was the one input that was not a trend.** `shutterToDecisionMs` came from
`backlogDeadlineMs`, which tracks whichever capture was committed *most recently*, so a commit landing
just before a decision resets it and the whole term switches off. Measured on 42B: run 6 shot 11 read
456 ms against a burst running at 1576, run 7 shot 10 read 839 against 1685 — both collapsed the term
to 0 in the middle of a burst that was still filling. It is now a `RecencyWeightedDistribution` in the
session, `observeShutterLagMs(timeToDeadlineMs)`, on the same teach-then-read contract as
`observeBacklogGrowthMs`, and not floored: a lag is an age, and a burst catching up on the shutter
stream is entitled to have the term relax.

Because a trend cannot be recovered from one row's `timeToDeadlineMs`, it joins the persistence chain
(`shutterLagMs` → `shutter_lag_ms` → three mapper sites → three exporter call sites →
`beforeShutterLagMs`), for the same reason `backlogGrowthMs` did. The exporter already had a
`beforeShutterToDecisionMs` column holding the single-row complement, so the trend needed its own name.

**(b) The count was unbounded, with a cliff at an expired deadline.** `timeToDeadlineMs` coerces to 0,
so a session that stays open while commits stop arriving drives the lag to `CAPTURE_TIMEOUT_MS` and the
count to ~31, i.e. ~27 s of unseen work and a ~13 s delay. 42B never reaches it (max lag 2497, 36% of
the way), so this was untested rather than disproven. The count is now capped by the arrivals the
remaining window has room for, `timeToDeadlineMs / shutterIntervalMs`: no new constant, and as the
window closes the cap closes with it. It binds on **0 of 151** decisions on 42B — a safety bound that
changes nothing measured, which is what it should be.

**(c) The cadence source dropped the deadlines that matter most.** `setCaptureDeadlineMs` deliberately
discarded values arriving before the session opened, on the reasoning that without a snapshot there was
no decision they could affect. That reasoning is dead: the deadline stream is now where the cadence is
learned, `getShutterIntervalMs()` returns 0 until two deadlines land, and 0 disables the term — so the
term's availability at a burst's first decisions depended on how many callbacks happened to precede the
first draft start. `setCaptureDeadlineMs` now calls `openSession()`.

This matters more than it looks. `SavingDraftImageTaskManager` calls it on every capture the Draft
pipeline accepts, on a path that does not care whether APM has published the decider — so the four
captures per burst that never reach `decideDelay` at all (§3) now contribute their cadence even though
they can never be paced. Bracketing the old behaviour on 42B by opening the session at the first
recorded decision instead: run 8's term **never fires at all**, and run 7 fires after its last shutter.
With (c) the onset is pinned at shot 3 by construction rather than by luck.

Two stale comments were corrected with it: the pacer's session field ("Only `decideDelay` opens a
session") and `SavingDraftImageTaskManager`'s call-site comment, which said `decideDelay` opens the
session earlier in that path.

### A refactor that was tried and reverted

The two learned trends were briefly extracted into one nested helper
(`ConsecutiveDifferenceTrend`, then `SuccessiveGapMean`, then `PerObservationChange`). The user
rejected all three names and asked for them to be operated separately again, which is the current
state. If you are tempted to re-extract: the two differ in sample filter and in read floor, and the
shared part is four mechanical lines. Do not spend the session on it again. Follow-up (a) adds a third
trend, `shutterLagsMs`, which makes the temptation stronger and the answer no different — it differs
from both again, taking every sample and flooring nothing.

---

## 6. What it measures

### On the failing workbook (42B, 8 runs, 4 timeouts)

| | total delay | healthy runs | timeout runs | first paced shot, the 4 dying runs |
|---|---|---|---|---|
| shipped (growth only) | — | — | — | 11, 6, 5, 5 |
| **+ in-flight** | +146% | **0 of 111 decisions fire, +0.0 ms** | 26 of 40 fire, +798 ms/shot | **6, 3, 3, 3** |

Shot 3 is the first decision that exists. The term reaches the earliest point the pacer is alive.

### On the workbook that is now working (0903, 68 runs, 10 timeouts)

+7% total delay, 37 of 1535 healthy decisions fire at +9.9 ms/shot, and 7 of 68 runs start pacing
one shot earlier (4 → 3).

### Ablation — the two terms are not redundant

First paced shot of the four dying runs:

| | run 5 | run 6 | run 7 | run 8 |
|---|---|---|---|---|
| neither | 14 | 9 | never | never |
| growth only | 11 | 6 | 5 | 5 |
| in-flight only | **14** | 3 | 3 | 3 |
| both | **6** | 3 | 3 | 3 |

Runs 6–8 are in-flight's; run 5 is growth's (it has the lowest in-flight, 5.3, and the longest
run — a slow ramp). On 0903, removing growth drops total delay 36% and pushes onsets from 4→9 and
9→12, i.e. it would give back most of what the device measurement just confirmed. **Keep both.**

Run 5's `both` cell reads 5 under the follow-ups rather than 6; the other three cells are unchanged.

### The follow-ups, re-measured on 42B

Recomputed from scratch on the recorded rows, not with `rinflight.py` (§7): the shutter cadence is
reconstructed from the workbook's deadline stream and the trends are ports of
`RecencyWeightedDistribution`. The gate held — HEAD's formula reproduces **151 / 151** recorded delays —
so the baseline column is the build that ran. `*` marks a first fire at or before the burst's last
shutter, the only decisions whose delay can still move a deadline.

First paced shot:

| arm | run 1–4 (healthy) | run 5 | run 6 | run 7 | run 8 |
|---|---|---|---|---|---|
| HEAD, growth only | 22\*, 20\*, 21\*, 16\* | 11 | 6 | 5 | 5 |
| in-flight as first written | unchanged | 5\* | 3\* | 3\* | 3\* |
| in-flight, bracketing **(c)** away | unchanged | 5\* | 5\* | 5 | 5 |
| **with (a)+(b)+(c)** | unchanged | **5\*** | **3\*** | **3\*** | **3\*** |

Total delay, ms:

| arm | run 1 | run 2 | run 3 | run 4 | run 5 | run 6 | run 7 | run 8 |
|---|---|---|---|---|---|---|---|---|
| HEAD | 1922 | 1705 | 3837 | 4661 | 2149 | 2261 | 2515 | 2839 |
| in-flight as first written | 1922 | 1705 | 3837 | 4661 | 3125 | 11445 | 12565 | 17653 |
| **with (a)+(b)+(c)** | 1922 | 1705 | 3837 | 4661 | 2590 | 10943 | 11838 | 16387 |

Reading it:

- **The healthy runs are byte-identical under every arm.** Neither the term nor any follow-up changes
  one of their 111 decisions. All the cost is on the four runs that died.
- **(a) trims without costing reach.** Onsets are unchanged and the total falls 17% on run 5, 4–7% on
  runs 6–8. It shaves the peaks and fills the collapses: run 6 shot 11 reads a raw lag of 456 ms against
  a burst trending at 1576, run 7 shot 10 reads 839 against 1685 — both zeroed the term mid-burst before
  the trend replaced them.
- **(b) is inert here**, by design — see §5.
- **(c) is what makes the onset reproducible.** Bracketed away, run 8's term never fires and run 7's
  first fire lands after its last shutter. The 6, 3, 3, 3 reported above for the first version sits
  between that bracket and the pinned 4, 3, 3, 3, which is the point: without (c) the result depends on
  how many callbacks precede the first draft start.

Not modelled: with the session open from the first commit, `startDraftSequence` sets the snapshot on
the burst's *first* Draft rather than its second or third, so decisions could begin at shot 1–2 instead
of 3. The recorded rows start at shot 3 and cannot show it. And this remains open loop — no timeout
count is claimed from it (trap 14).

### Regression check on the 0803 Ultra recordings

`SEIP'27/data/U_ablation_sampling/48U_metrics_{12MP_normal,24MP_memory}_0803_{1,2}.xlsx`. Gate: the
old formula reproduces **100%** of recorded delays in both groups. **Both groups have zero
timeouts**, so this is a pure cost measurement.

| | growth only | in-flight only | both |
|---|---|---|---|
| S26 12MP normal (70 runs) | +90%, +78 ms/shot | **+0%, +0 ms** | +90%, +78 ms/shot |
| S26 24MP memory (73 runs) | +63%, +65 ms/shot | **+2%, +2 ms** | +65%, +68 ms/shot |

In-flight is silent there because the count is 1.1–1.5 at the median and never reaches the
threshold. All the cost on those recordings is the growth term, and it scales with heat: 12MP
+6 ms/shot at Lv0 rising to +145 at Lv6.

---

## 7. Verification

```bash
python .codex_sheet_analysis/pacing_replay_0829/tgrowth.py     # 14 asserts, all pass
python .codex_sheet_analysis/pacing_replay_0829/rinflight.py   # the term on 42B and 0903
python .codex_sheet_analysis/pacing_replay_0829/rfast.py       # candidate rules on 42B
python .codex_sheet_analysis/pacing_replay_0829/r0803.py       # the four Ultra recordings
```

**These four still model the pre-follow-up formula.** They pass `timeToDeadlineMs` where the tree now
takes a learned `shutterLagMs`, and they know nothing of the remaining-window cap or of the session
opening on a commit, so re-running them scores the first version, not the tree. The §6 follow-up table
came from a separate recomputation. Bring them forward before they gate anything again; `tgrowth.py`
wants at least two more asserts — a lag trend that damps a single short sample, and a cap that closes
as the window does.

`tgrowth.py` ports the shipped Kotlin line for line and asserts, among others: with no measured
cadence the term contributes nothing; a burst the pipeline keeps up with is untouched (in-flight
2.8, horizon 2, queued 1 → 0 ms); a burst outrunning it is charged (in-flight 7.1 → 4762 ms);
shooting faster never lowers the count.

New harness files, all under gitignored `.codex_sheet_analysis/pacing_replay_0829/`:
`rfast.py`, `rinflight.py`, `r0803.py`, and the extended `tgrowth.py`.

---

## 8. Traps

Additions to the trap list in `PacingInvestigationHandoff.md` §7.

14. **The conservative `lo` margin accounting breaks down in this regime.** It credits only delay
    released *before* the timed-out shutter, which assumes the callback lag is smaller than the shot
    cadence. Here the lag is 1.5–2.3 s against a 200 ms cadence, so a large delay pushes its own
    release past that shutter and disqualifies itself — `G3` scores *worse* than `G2` for that
    reason alone. **Do not report a timeout count from that instrument on this data.** Report onset
    shifts and cost; the device is the arbiter.
15. **The 42B build's reserve factor is 1.0.** Call `rpacer2.detect_safety` rather than assuming.
16. **`margin vs backlog` is a confounded table.** Pacing applies delay exactly when the queue is
    deep, so the two move together. Condition on a queue band before claiming a direction (§9).
17. **Do not replay the in-flight term from `beforeShutterToDecisionMs`.** That column is this row's
    `captureTimeoutMs − beforeTimeToDeadlineMs`; the decision counted against the burst's learned
    trend, exported separately as `beforeShutterLagMs`. Recomputing it row-wise scores a different
    controller, the same trap `beforeBacklogGrowthMs` carries. Workbooks exported before these columns
    exist have no cadence and must be replayed with the term at 0.
18. **Where a burst begins is now the first committed capture, not the first callback.** `pacerSessionId`
    (`createdUptimeMs`) therefore moves earlier for every burst. Only its inequality is read by offline
    grouping, so this is safe — but a comparison across workbooks recorded either side of the change
    should not read anything into the value itself.

---

## 9. Admission interaction — a correction worth carrying forward

An earlier answer in this session said admission would fire *less* under more pacing, from the
marginal table of admission margin against queue wait. That table is confounded. Conditioning on a
fixed queue-wait band reverses it:

| queue wait 3000 ms+ | delay 0 | 1–200 | 200–500 | 500 ms+ |
|---|---|---|---|---|
| 12MP bokeh margin | +1313 | +453 | +307 | **−142** |
| 24MP bokeh margin | +1191 | +489 | +291 | +164 |

`budget = 7000 − callbackLag − pacingDelay − queueWait`, so a delay directly costs the capture it is
applied to, and only pays back to the captures behind it. The net is a transfer, not a win.

Sized on the 0803 recordings at the +65–78 ms/shot the terms add, the flips roughly cancel for
bokeh (12MP 22 new rejections vs 28 rescued; 24MP 29 vs 29) and lean slightly negative for filter
(12MP 13 vs 8; 24MP 18 vs 13). The risk is the **sticky amplifier**: one gate rejection costs 6.3
captures of bokeh and **18.2 of filter** on 12MP, so a small count moves a large number of frames.

Baseline admit rates to compare against: 12MP bokeh 60% / filter 92%, 24MP bokeh 60% / filter 85%
(0803); 0903 bokeh 59% / filter 71%.

**Watch `filter` admit rate before `bokeh` in the next measurement.**

---

## 10. Open, and deliberately not done

- **`draftSequenceOverheadDurationMs` is not removable.** It was questioned and measured: dropping
  it costs −18% (42B) and −21% (0903) of total delay and pushes 20 of 68 runs to pace later. It is
  only 25–44 ms per draft, but it is the *price* the other two terms multiply — in-flight is
  `count × reserve`, and the backlog accumulates price per draft. It sits upstream of both, not
  beside them. Its learned value tracks the actual to the millisecond (25 vs 25, 44 vs 44).
- **Run 8 is not reachable by any growth- or cadence-based rule under the `lo` accounting**, because
  its only pre-shutter decision is the first one. Whether the in-flight term actually saves it
  depends on the gating chain the open-loop instrument cannot model. Do not claim it either way
  without device data.
- **The 4 captures lost to "no pacing decider published"** are an APM publication-timing issue, not
  a pacer one. Worth raising with that owner; roughly a third of the blind window. Follow-up (c) does
  not make them paceable — `decideDelay` is still never called for them — but their deadlines now
  reach the session, so they do contribute to the cadence later decisions count against.
- **Nothing here is compiled**, and the §7 self-checks still model the pre-follow-up formula. Run a
  build, and bring those forward, before the next device measurement.
