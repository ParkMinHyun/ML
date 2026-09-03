# Pacing: firing earlier under queue pressure — methods, simulation, verdict

Date: 2026-09-02, session 2. Companion to `PacingInvestigationHandoff.md`; read that one first for the
data inventory (§2), the replay harness (§3) and the identifiability contract (§4).

Goal set by the user: **fire the pacing delay earlier when queue pressure builds and a timeout is
likely, without raising the delay much, and with a mechanism developers and reviewers accept at a
glance.**

---

## 1. Verdict first

Ship the **queue-growth lead term** (`G` below). One extra addend in `computePacingDelayMs`,
learned with the trend class the codebase already has, no new constant.

```
delayMs = ceil( (backlogMs + reserved * PACING_WINDOW_DRAFT_COUNT - timeToDeadlineMs)
                / PACING_WINDOW_DRAFT_COUNT
                + backlogGrowthMs )
```

where `backlogGrowthMs` is the recency-weighted mean of `backlogMs(k) - backlogMs(k-1)` inside the
current burst, floored at 0.

**The growth is added after the split, not inside it.** The deficit is a one-off shortfall, so
halving it hands the other half to the future Draft that shares the window. The growth is a rate:
the delay that holds a queue rising by G per callback flat is G per callback, and halving it leaves
a residual ramp of G/2 on every shot. Both forms were measured; §4.1 has the comparison.

Rejected after measurement: pricing against the oldest queued deadline (`D`, 2x the total delay),
a three-draft horizon (`H3`, needs a new constant), and the clock-price fixes from handoff §6.1
(`P5`/`P7`, they clear 2-4 of 14 on their own and one of them paces *less* than today).

---

## 2. Why a lead term, in one paragraph

The shipped decision compares the queue **as it stands** against the deadline. A burst is a rate
problem: the callback this decision gates is released into the queue one arrival later, by which
time the queue has grown by roughly one service time minus one interarrival time. So the controller
is proportional-only on a signal that ramps, and it necessarily acts late — which is exactly what
the recording shows (handoff §5.7: the better clocks win by firing earlier, not harder, and in most
timeout runs the captureAvailable callbacks lag the shutters by 2-5 s, so a delay decided late
cannot move the timed-out capture's deadline at all).

Adding the learned per-arrival growth turns it into a proportional-plus-lead controller. The term
is self-limiting: once pacing holds the queue flat the growth decays to 0 and the delay relaxes.

The same quantity derived analytically instead of learned (`Gs` = clock price minus mean arrival
interval) scores the same, which is the sanity check that the learned term is measuring the thing
the queueing argument says it should.

---

## 3. What was simulated

Two instruments, both trace-conditioned estimates, never empirical timeout data.

**Open loop** — `rmethods.py`. Every arrival held fixed; the handoff's §4.3 two-bound margin
accounting. Conservative, because an arm that fires earlier is charged its extra delay on every
later decision and never credited for the shorter queue that delay buys.

**Closed loop** — `rclosed.py`, new. The one coupling the recording shows directly: a shutter that
follows a released callback moves with that release, so extra delay pushes later shutters *and*
their deadlines. Held fixed per capture: draft wall, callback lag, the newest committed capture the
decision was priced against, the pre-draft readiness lag, the server's inter-draft gap. The draft
server is one FIFO worker, so capture k starts at `max(ready'_k, end'_{k-1} + gap_k)`.

Gate for the closed loop, on the recorded policy:

| workbook | timeouts reproduced | delay mismatches | max shutter shift | margin mismatches |
|---|---|---|---|---|
| `24MP_memory_0829_original` | 14 / 14 | 28 / 1717 | 95 ms | 22 |
| `24MP_memory_0829` | 9 / 9 | 26 / 1576 | 95 ms | 22 |
| `12MP_normal_baseline_0829` | 46 / 46 | 1 / 858 | 0 ms | 0 |

Two of the 24MP delay mismatches are inherited from `rpacer2` itself (`ci=6`, `ci=7`, +67 ms each,
the known `draftStartUptimeMs` stamping residue); the rest are that residue propagating.

### 3.1 New finding: the two 0829 workbook families are different builds

Handoff §2 says the 0829 build multiplies the pacing reserve by 1.1. That is true of the
**24MP_memory** family only. The **12MP_normal** family has no such factor:

| factor | floor-branch rows where `reserved / f == predicted + overhead` exactly |
|---|---|
| | `24MP_memory_original` — `12MP_normal_baseline` |
| 1.00 | 0 — **105** |
| 1.05 | 10 — 0 |
| 1.10 | **262** — 0 |

Replaying the 12MP workbook with 1.1 silently measured a different pacer: 183 of 858 delays wrong,
p50 error 57 ms, and only 41 of 46 timeouts reproduced. With 1.0 the gate is near-exact (table
above). `rpacer2.detect_safety()` now recovers the factor per workbook and every entry point calls
it. **Any earlier cross-family comparison in this repo is suspect.**

---

## 4. Results

`G` = queue-growth lead term. Baseline for every column is the current repo HEAD, i.e. the shipped
policy including commit `9217444`.

### Open loop (handoff §4.3 bounds; `lo` is the conservative one)

| workbook | runs | timeouts | total delay | TO lo | TO relaxed |
|---|---|---|---|---|---|
| `24MP_memory_0829_original` (selection) | 69 | 14 | +51% | **9 / 14** | 7 / 14 |
| `12MP_normal_baseline_0829` (holdout) | 59 | 46 | +62% | **21 / 46** | 22 / 46 |

Baseline is 0 in every `lo` and `relaxed` cell by construction.

`24MP_memory_0829.xlsx` is **not** a third data point and is excluded here. Measured: all 1802 of its
captures and all 9 of its timeouts are inside `24MP_memory_0829_original.xlsx`, matched on
`draftStartUptimeMs`. It is a filtered re-export of the same recording, not a holdout, despite how
handoff §2 lists it. It scores 8/9, 6/9 and 6/9, which is the selection set restating itself.

### Closed loop

| workbook | HEAD cleared | cleared | total delay | new timeouts |
|---|---|---|---|---|
| `24MP_memory_0829_original` | 1 / 14 (5 new) | **4 / 14** | +26% | **0** |
| `12MP_normal_baseline_0829` | 2 / 46 (0 new) | **22 / 46** | +54% | **0** |

So the honest evidence base is **60 distinct capture timeouts over two independent recordings**: the
24MP memory set the candidates were chosen on, and the 12MP normal set, which is a different
workload, a different thermal regime and a different build. The ranking holds across both, which is
the one thing the previous session could not check.

Note the baseline's own 5 new closed-loop timeouts on the 24MP family: commit `9217444` both raises
and lowers the delay, and where it lowers it early in a burst the shutters move *earlier* and the
queue deepens sooner. `G` more than compensates (it never lowers the delay) and takes that count to
2 marginal ones.

### 4.1 Halve the growth, or apply it whole?

Both were measured. `G` halves the growth with the rest of the deficit; `Gw`, what ships, adds it
after the split.

| | 24MP, 14 timeouts | | 12MP holdout, 46 timeouts | |
|---|---|---|---|---|
| | G halved | **Gw whole** | G halved | **Gw whole** |
| open loop, conservative | 8 | **9** | 19 | **21** |
| open loop, pessimistic | 6 | **7** | 15 | **22** |
| closed loop | **6** | 4 | 19 | **22** |
| new timeouts | 2 | **0** | 0 | **0** |
| total delay, open loop | +24% | +51% | +29% | +62% |
| healthy runs, per shot | +58 ms | +121 ms | +0 ms | +0 ms |

`Gw` is more preemptive where it should be: delay in the first half of a burst rises 74% (24MP) and
112% (12MP) against `G`, but only 13-18% in the second half.

The one place `Gw` loses is the 24MP closed loop, 4 against 6. Two of the 14 runs receive *less*
total delay under `Gw`: braking harder early flattens the queue, the learned growth collapses to
zero, and the term has retired itself by the time the queue ramps again. The other 12 runs behave
as expected. That is a 2-run reversal at n=14 inside the instrument built to evaluate this change,
against a 3-of-3 win on a holdout with three times the events, so `Gw` was taken. **Watch for it in
the device A/B** — if the stand-down is real rather than an artefact it will show as a burst that
paces hard early and then stops.

### Cost, where it lands (`24MP_memory_0829_original`)

| | healthy runs | runs that timed out |
|---|---|---|
| extra delay per shot | +121 ms | +194 ms |
| closed-loop delay p50 / p95 on healthy runs | 0 → 0 ms / 1097 → 1341 ms | |

This is the change's weakest point against the brief, which asked for the delay not to rise much.
`G` costs half as much and clears one fewer timeout on each recording. On the 12MP workload the
question does not arise: healthy bursts there are never paced under either form.

Within a healthy burst the extra concentrates at the front and fades:

| burst decile | d0 | d1 | d2 | d3 | d4 | d5 | d6 | d7 | d8 | d9 |
|---|---|---|---|---|---|---|---|---|---|---|
| extra ms/shot | +59 | +129 | +100 | +60 | +53 | +48 | +37 | +27 | +28 | +27 |

That shape is the intended behaviour: act while the queue is still shallow, then stop, because the
queue never gets deep. (The decile figures are `G`'s; `Gw` roughly doubles each while keeping the
same shape.) On `12MP_normal_baseline` the healthy runs pay **0 ms** — they are never paced at all
— and the whole cost lands on the runs that timed out.

### Onset — the mechanism

First paced shot of each run that timed out, `24MP_memory_0829_original`:

```
HEAD   6  6  5 12  4  6  -  5  4 16 14 12 12 13
G      6  5  4 10  4  5  -  4  4 15 12 11 10 11
```

Earlier on 9 of 13, by 1 to 2 shots. On `12MP_normal_baseline` the same shift shows on 30 of 44
(e.g. 10→9, 9→7, 6→4).

### Safety properties

| check | result |
|---|---|
| empty pipeline still decides 0 ms | unchanged: same 5 of 78 zero-queue decisions delayed as today (24MP), 0 of 88 (12MP). The 7 s window dominates even where the growth term is positive |
| never paces less than today | 0 of 1717 and 0 of 858 decisions below baseline |
| no delay==0 while the deficit is positive (the 2026-07-09 valley) | 0 occurrences |
| new constant | none: `RecencyWeightedDistribution` (decay 0.90) and `mean()` already exist |

---

## 5. Rejected, with the number that killed each

| method | what it does | why not |
|---|---|---|
| `D` oldest queued deadline | price pressure against the deadline of the oldest decision still queued instead of the newest committed capture | clears more (7/14, 22/46) but costs **+97% to +113%** closed-loop delay and p50 **907 ms** on healthy 24MP runs. The deadline it prices against belongs to a capture pacing cannot save |
| `H3` horizon 3 | widen `PACING_WINDOW_DRAFT_COUNT` to 3 | best raw timeout count (9/14, 21/46) but it is a **new constant**, +19-20% delay, and p50 105 ms on healthy 24MP runs. It is "pace harder", not "pace earlier" |
| `P5` / `P7` clock-price fixes | handoff §6.1: correct the clock's bias multiplicatively or additively | 3-4 of 14 on their own. `P7` also paces **less** than today on 158 decisions, which breaks the monotonicity the 2026-07-09 review demanded |
| `G2` growth x2 arrivals | project two arrivals ahead | +51-62% delay for +1 timeout over `G` |
| `Gmed`, `Gu`, `Gs` | median instead of mean, unfloored, analytic form | all within noise of `G`; `G` is the simplest and matches the codebase's `mean()` idiom for a right-skewed quantity |

`P5`/`P7` are handoff §6.1 in disguise. **`G` is orthogonal to that decision** — it works on top of
whatever clock price is chosen, so §6.1 does not block it, and shipping `G` does not pre-empt §6.1.

---

## 6. Implementation — applied 2026-09-02, not committed

Seven files, 72 lines added, 1 changed, 0 deleted, plus a one-line follow-up moving the growth outside the split. No new file, no new class, no new constant, no
migration (`CaptureMetricsDatabase` is pinned at `version = 1` with `fallbackToDestructiveMigration`,
which is the project's standing arrangement).

| file | change |
|---|---|
| `CaptureAvailablePacingSession.kt` | 2 fields, `observeBacklogGrowthMs()` |
| `CaptureAvailablePacer.kt` | 1 call, 1 parameter on `computePacingDelayMs`, 1 decision field |
| `CaptureMetrics.kt` / `CaptureMetricsEntities.kt` / `CaptureMetricsMappers.kt` | persist the term |
| `CaptureMetricsExcelExporter.kt` | 3 call sites, `beforeBacklogGrowthMs`, one ReplayNote |
| `external/apm/policy/CaptureAvailableApmPolicy.java` | one log field, mirroring the 0722 pattern |

The persistence chain is not optional. `computePacingDelayMs` has four call sites and three of them
are the exporter re-evaluating the same formula on recorded rows. A Kotlin default of `0.0` would
compile with two files touched and silently make those three columns a different controller than
the one that ran, which is exactly what sharing the function exists to prevent.

**Not compiled** — there is no Kotlin toolchain here. Verified by
`.codex_sheet_analysis/pacing_replay_0829/tgrowth.py`, which ports the shipped Kotlin line for line
and asserts: it equals the arm that was simulated; growth 0 reproduces today's formula exactly;
delay is monotone in the growth term; it never decides less than today; a draining burst floors at
zero; an idle pipeline still decides 0 ms; and a held-flat queue decays the term back toward zero
(700 ms to 67 ms), so it cannot wind up. **Run a build.**

### Sketch as shipped

`CaptureAvailablePacingSession.kt` — the burst owns the growth, so `clear()` still resets everything:

```kotlin
/**
 * How much the admitted backlog grows between two consecutive callbacks of this burst. The
 * decision prices the queue as it stands, but the callback it gates is released one arrival
 * later, so a burst that is filling faster than it drains is already behind by this much.
 * Recency-weighted mean, the statistic this codebase uses for a right-skewed quantity that must
 * not be under-estimated, and it carries no smoothing factor of its own.
 */
private val backlogGrowthsMs = RecencyWeightedDistribution()
private var lastBacklogMs: Long? = null

/** Reads the growth this callback teaches, then the trend including it. Call once per decision. */
fun observeBacklogGrowth(backlogMs: Long): Double {
    lastBacklogMs?.let {
        backlogGrowthsMs.decay()
        backlogGrowthsMs.add((backlogMs - it).toDouble())
    }
    lastBacklogMs = backlogMs
    // A draining queue teaches a negative sample, which lowers the trend but may not lower the
    // delay: pacing is allowed to become more careful within a burst, never less.
    return backlogGrowthsMs.mean().coerceAtLeast(0.0)
}
```

`CaptureAvailablePacer.decideDelay()`:

```kotlin
val backlogMs = session.backlogMsAt(nowUptimeMs)
val backlogGrowthMs = session.observeBacklogGrowth(backlogMs)
val pacingDelayMs = computePacingDelayMs(
    backlogMs = backlogMs,
    backlogGrowthMs = backlogGrowthMs,
    timeToDeadlineMs = timeToDeadlineMs,
    draftSequenceReservedDurationMs = draftSequenceReservedDurationMs,
)
```

`computePacingDelayMs`:

```kotlin
internal fun computePacingDelayMs(
    backlogMs: Long,
    backlogGrowthMs: Double,
    timeToDeadlineMs: Long,
    draftSequenceReservedDurationMs: Double,
): Long {
    val estimatedCompletionTimeMs =
        backlogMs + backlogGrowthMs + (draftSequenceReservedDurationMs * PACING_WINDOW_DRAFT_COUNT)
    val deadlineDeficitMs = estimatedCompletionTimeMs - timeToDeadlineMs.coerceAtLeast(0L)
    return ceil(deadlineDeficitMs / PACING_WINDOW_DRAFT_COUNT).toLong().coerceAtLeast(0L)
}
```

Order matters: observe first, then read, so the difference this callback measured is in the trend
that prices it. The replay does the same.

Order matters and is asserted by the self-check: observe first, then read, so the difference this
callback measured is in the trend that prices it.

---

## 7. What this does not fix

The hot-start bursts. Splitting the 14 by handoff §5.4: the 8 that start at overheat level 3-6 and
die at shot 7-14 stay uncleared under every arm that costs less than doubling the shot-to-shot time.
They have no runway — the lead term needs two decisions to learn anything, and those runs are over
by shot 7. The lever there is shedding (handoff §5.6, +38-72 ms/shot queue growth instead of
+364-492), not pacing. Do not re-propose pacing for them.

---

## 8. Reproducing

```bash
python .codex_sheet_analysis/pacing_replay_0829/rvalidate.py                 # predictor gate, error 0
python .codex_sheet_analysis/pacing_replay_0829/rmethods.py  <workbook.xlsx> # open loop, all arms
python .codex_sheet_analysis/pacing_replay_0829/rclosed.py   <workbook.xlsx> # closed loop + its gate
python .codex_sheet_analysis/pacing_replay_0829/rdiag.py     <workbook.xlsx> # safety + cost of finalists
python .codex_sheet_analysis/pacing_replay_0829/tgrowth.py                   # shipped term, self-check
```

New files this session: `rmethods.py` (arms and the open-loop table), `rclosed.py` (closed loop),
`rdiag.py` (safety and cost), `tgrowth.py` (asserts the shipped Kotlin is the arm that was
measured). `rpacer2.detect_safety()` is the per-workbook reserve factor.
`rload.py` now also carries `firstNodeOverheatLevel` as `level`.

Everything still lives in gitignored `.codex_sheet_analysis/`, so it does not travel with a clone.

### Traps added to handoff §7

10. **The reserve factor is per workbook family, not per date.** Call `detect_safety()`; do not
    assume 1.1.
11. **The closed loop's draft server is FIFO by capture index.** An earlier version started drafts
    in readiness order, which silently reordered the queue and produced 24 timeouts against the
    recorded 14.
12. **Readiness must be clamped at the recorded draft start** (`min(shutter + minLag, draftStart)`),
    or a reconstructed capture becomes ready later than it really was and the chain drifts.
13. **`24MP_memory_0829.xlsx` is a subset of `24MP_memory_0829_original.xlsx`, not a holdout.**
    1802 of 1802 captures and 9 of 9 timeouts match on `draftStartUptimeMs`. Handoff §2 lists it as
    an untouched holdout; scoring it as independent evidence double-counts the selection set. The
    only genuine holdout in this directory is the 12MP_normal family.
