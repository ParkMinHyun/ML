"""RQ3 aggregation for the SEIP'27 pacing table and figure.

Implements docs/rq1-rq3-metrics-guide.md section 6 of the paper repository against
`RQ3Pacing`/`PacingReplay`/`Capture` sheets exported by CaptureMetricsExcelExporter.

Controlled setup: 12MP normal, starting overheat level 3, first 30 shots of every
complete run, one device per table row group.

Beyond the guide's five table cells this also emits the reviewer-facing evidence
that the guide's original scope omits: distributional (not extreme-value) backlog
and queue statistics, the deadline margin the delay buys, the user-visible
responsiveness cost, and the targeting/calibration diagnostics that say whether the
delay *magnitude* was justified rather than merely applied.

Run:
    uv run --with openpyxl --with pandas --with scipy python data/rq3_aggregate.py
"""
import json
import os
import warnings

import numpy as np
import pandas as pd
from scipy.stats import mannwhitneyu

warnings.filterwarnings("ignore")

DATA_DIR = os.path.dirname(os.path.abspath(__file__))
PAPER_RQ3_DIR = r"C:\Users\algus\Documents\SEIP'27\data\rq3"
OUT_DIR = os.path.join(PAPER_RQ3_DIR, "s26_ultra")

# Operator-supplied policy labels; the exporter does not store an RQ3 policy name.
ARMS = {
    "no_pacing": ["48U_metrics_12MP_normal_0729_AdmitOnly_1.xlsx",
                  "48U_metrics_12MP_normal_0729_AdmitOnly_2.xlsx"],
    "ours": ["48U_metrics_12MP_normal_0729.xlsx"],
}
START_LEVEL = 3
WINDOW = 30                   # shots 1..30
TRANSITIONS = WINDOW - 1      # delay on shot i gates shot i+1
# Fraction of the Capture Timeout budget above which a shot is counted as running
# close to its deadline. Backlog cannot exceed the budget without failing, so the
# maximum saturates; occupancy near the ceiling is what actually separates policies.
NEAR_DEADLINE_FRACTION = 0.8

PACING_REPLAY_COLUMNS = [
    "captureIndex", "captureTimeoutMs", "timeoutMarginMs",
    "beforeBacklogMs", "beforeShutterElapsedMs", "realQueueWaitMs",
    "beforeDominantDeficit",
]


def pct_inc(values, quantile):
    """Excel PERCENTILE.INC, matching the exporter's own percentile helper."""
    v = np.sort(np.asarray([x for x in values if x == x], dtype=float))
    if v.size == 0:
        return None
    rank = (v.size - 1) * quantile
    lo, hi = int(np.floor(rank)), int(np.ceil(rank))
    if lo == hi:
        return float(v[lo])
    return float(v[lo] + (v[hi] - v[lo]) * (rank - lo))


def pct(series, quantile):
    return pct_inc(pd.Series(series).dropna().tolist(), quantile)


def as_bool(series):
    return series.astype(str).str.strip().str.lower().isin(["true", "1"])


def load(arm):
    """RQ3Pacing rows joined to their PacingReplay decision context, plus Capture."""
    rq3_frames, capture_frames = [], []
    for name in ARMS[arm]:
        book = pd.ExcelFile(os.path.join(DATA_DIR, name))
        rq3 = book.parse("RQ3Pacing")
        replay = book.parse("PacingReplay")[PACING_REPLAY_COLUMNS]
        capture = book.parse("Capture")
        rq3["workbook"] = name
        capture["workbook"] = name
        rq3["run"] = name + "#" + rq3["runId"].astype(int).astype(str)
        rq3_frames.append(rq3.merge(replay, on="captureIndex", how="left"))
        capture_frames.append(capture)
    return pd.concat(rq3_frames, ignore_index=True), pd.concat(capture_frames, ignore_index=True)


def select_runs(rq3):
    """Complete 30-shot runs at the controlled condition with a valid real trace.

    A run shorter than 30 shots ended early (Capture Timeout / watchdog) and is
    reported by RQ1, not by this controlled protocol. A run with a censored Draft
    timeline cannot yield real backlog and would otherwise read as zero backlog.
    """
    condition = rq3[(rq3["startingOverheatLevel"] == START_LEVEL)
                    & (rq3["sizeBucket"] == "MP12")
                    & (~as_bool(rq3["isLowMemory"]))].copy()
    audit, kept = [], []
    for run, group in condition.groupby("run", sort=True):
        group = group.sort_values("runShotIndex")
        window = group[group["runShotIndex"] <= WINDOW]
        shot_count = int(group["runShotCount"].iloc[0])
        trace_ok = bool(as_bool(window["realTraceCompleteBeforeDelay"]).all()
                        and window["realBacklogMs"].notna().all()
                        and window["realQueueDepth"].notna().all())
        complete = shot_count >= WINDOW
        included = complete and trace_ok
        audit.append(dict(run=run, shotCount=shot_count, complete=complete, traceOk=trace_ok,
                          timeouts=int(as_bool(group["captureTimedOut"]).sum()),
                          watchdogs=int(as_bool(group["captureWatchdogFailed"]).sum()),
                          included=included))
        if included:
            kept.append(window)
    included_rows = pd.concat(kept, ignore_index=True) if kept else condition.iloc[:0]
    return included_rows, pd.DataFrame(audit)


def summarize(window):
    """Pooled table cells: pacing cost, backlog/queue control, and delivered margin."""
    transitions = window[window["runShotIndex"] <= TRANSITIONS]
    delays = transitions["transitionDelayMs"].dropna()
    positive = delays[delays > 0]
    run_totals = transitions.groupby("run")["transitionDelayMs"].sum() / 1000.0
    backlog = window["realBacklogMs"].dropna()
    depth = window["realQueueDepth"].dropna()
    margin = window["timeoutMarginMs"].dropna()
    step = window[window["runShotIndex"] > 1]["shotToShotTimeMs"].dropna()
    deadline = float(window["captureTimeoutMs"].dropna().iloc[0])
    span = window.groupby("run").apply(
        lambda g: g[g["runShotIndex"] > 1]["shotToShotTimeMs"].sum() / 1000.0)
    return dict(
        runs=int(window["run"].nunique()),
        transitions=int(delays.size),
        pacedCount=int(positive.size),
        pacedPercent=100.0 * positive.size / delays.size if delays.size else None,
        d50=pct(positive, 0.50),
        d95=pct(positive, 0.95),
        totalDelayMedianS=float(run_totals.median()),
        totalDelayMinS=float(run_totals.min()),
        totalDelayMaxS=float(run_totals.max()),
        # Backlog: distributional first, extreme value last.
        backlogMeanS=float(backlog.mean()) / 1000.0,
        backlogP95S=pct(backlog, 0.95) / 1000.0,
        bMaxS=float(backlog.max()) / 1000.0,
        deadlineMs=deadline,
        nearDeadlinePercent=100.0 * float((backlog > NEAR_DEADLINE_FRACTION * deadline).mean()),
        queueMean=float(depth.mean()),
        queueP95=pct(depth, 0.95),
        qMax=int(depth.max()),
        # What the delay buys.
        marginMinMs=float(margin.min()),
        marginP5Ms=pct(margin, 0.05),
        marginP50Ms=pct(margin, 0.50),
        marginUnder300Percent=100.0 * float((margin < 300).mean()),
        # What the delay costs the user.
        shotToShotP50Ms=pct(step, 0.50),
        shotToShotP95Ms=pct(step, 0.95),
        spanMedianS=float(span.median()),
        spanMinS=float(span.min()),
        spanMaxS=float(span.max()),
        timeouts=int(as_bool(window["captureTimedOut"]).sum()),
        watchdogs=int(as_bool(window["captureWatchdogFailed"]).sum()),
    )


def decision_quality(window):
    """Was the delay targeted at the right states, and was its magnitude justified?

    `beforeBacklogMs` is only maintained by a build whose pacer actually runs, so the
    clock-calibration fields stay null for a no-pacing arm rather than reporting a
    disabled estimator as a policy property.
    """
    transitions = window[window["runShotIndex"] <= TRANSITIONS]
    paced = transitions[transitions["transitionDelayMs"] > 0]
    unpaced = transitions[transitions["transitionDelayMs"] == 0]
    total_delay = transitions["transitionDelayMs"].sum()
    result = dict(
        pacedBacklogP50Ms=pct(paced["realBacklogMs"], 0.50),
        unpacedBacklogP50Ms=pct(unpaced["realBacklogMs"], 0.50),
        pacedQueueP50=pct(paced["realQueueDepth"], 0.50),
        unpacedQueueP50=pct(unpaced["realQueueDepth"], 0.50),
        dominantDeficit={str(k): int(v)
                         for k, v in paced["beforeDominantDeficit"].value_counts().items()},
    )
    if total_delay > 0:
        threshold = pct(transitions["realBacklogMs"], 0.75)
        high = transitions[transitions["realBacklogMs"] >= threshold]
        result["backlogP75Ms"] = threshold
        result["delayShareAboveP75Percent"] = 100.0 * float(high["transitionDelayMs"].sum()) / total_delay
        ratio = (paced["transitionDelayMs"] / paced["realQueueWaitMs"])
        ratio = ratio.replace([np.inf, -np.inf], np.nan).dropna()
        result["delayOverQueueWaitP50"] = pct(ratio, 0.50)
        result["delayOverQueueWaitP90"] = pct(ratio, 0.90)
        result["delayOverQueueWaitMax"] = float(ratio.max())

    clock = window.dropna(subset=["realQueueWaitMs", "beforeBacklogMs", "beforeShutterElapsedMs"])
    if len(clock) and float(clock["beforeBacklogMs"].abs().max()) > 0:
        error = clock["beforeBacklogMs"] + clock["beforeShutterElapsedMs"] - clock["realQueueWaitMs"]
        result.update(clockErrorP5Ms=pct(error, 0.05), clockErrorP50Ms=pct(error, 0.50),
                      clockErrorP95Ms=pct(error, 0.95),
                      clockUnderPredictPercent=100.0 * float((error < 0).mean()))
    else:
        result["clockNote"] = "beforeBacklogMs not maintained by this build; clock calibration N/A"
    return result


def run_level(window):
    """Per-run values behind each pooled cell, for dispersion and rank tests."""
    grouped = window.groupby("run")
    deadline = float(window["captureTimeoutMs"].dropna().iloc[0])
    return pd.DataFrame({
        "sumDelayS": grouped["transitionDelayMs"].sum() / 1000.0,
        "backlogMeanMs": grouped["realBacklogMs"].mean(),
        "backlogP95Ms": grouped["realBacklogMs"].apply(lambda s: pct(s, 0.95)),
        "backlogMaxMs": grouped["realBacklogMs"].max(),
        "nearDeadlinePercent": grouped["realBacklogMs"].apply(
            lambda s: 100.0 * (s > NEAR_DEADLINE_FRACTION * deadline).mean()),
        "queueMean": grouped["realQueueDepth"].mean(),
        "queueMax": grouped["realQueueDepth"].max(),
        "marginMinMs": grouped["timeoutMarginMs"].min(),
        "marginP5Ms": grouped["timeoutMarginMs"].apply(lambda s: pct(s, 0.05)),
        "shotToShotP95Ms": grouped["shotToShotTimeMs"].apply(lambda s: pct(s, 0.95)),
        "spanS": grouped.apply(lambda g: g[g["runShotIndex"] > 1]["shotToShotTimeMs"].sum() / 1000.0),
    })


def per_shot(window):
    """Figure CSV: per-shot P10/median/P90 of backlog, queue depth, applied delay."""
    rows = []
    for shot in range(1, WINDOW + 1):
        group = window[window["runShotIndex"] == shot]
        # Shot 30's delay gates shot 31, outside the controlled window, and exists only
        # for over-length runs. Blank it so every plotted shot pools the same runs.
        delays = group["transitionDelayMs"].dropna() if shot <= TRANSITIONS else pd.Series(dtype=float)
        row = {"shot": shot}
        for name, series in (("backlog", group["realBacklogMs"].dropna()),
                             ("queue_depth", group["realQueueDepth"].dropna()),
                             ("delay", delays)):
            for suffix, quantile in (("median", 0.50), ("p10", 0.10), ("p90", 0.90)):
                value = pct_inc(series, quantile)
                row[f"{name}_{suffix}"] = "nan" if value is None else round(value, 3)
        rows.append(row)
    return pd.DataFrame(rows)[["shot",
                               "backlog_median", "backlog_p10", "backlog_p90",
                               "queue_depth_median", "queue_depth_p10", "queue_depth_p90",
                               "delay_median", "delay_p10", "delay_p90"]]


def admit_rates(window, capture):
    """Admitted-workload audit; the arms are comparable only when these match."""
    joined = window[["run", "captureIndex", "workbook"]].merge(
        capture[["workbook", "captureIndex", "bokehAdmitted", "bokehCompleted",
                 "filterAdmitted", "filterCompleted"]],
        on=["workbook", "captureIndex"], how="left")
    for column in ("bokehAdmitted", "bokehCompleted", "filterAdmitted", "filterCompleted"):
        joined[column] = as_bool(joined[column])
    joined["bothCompleted"] = joined["bokehCompleted"] & joined["filterCompleted"]
    per_run = joined.groupby("run")[["bokehAdmitted", "filterAdmitted",
                                     "bokehCompleted", "bothCompleted"]].mean() * 100
    return {f"{name}MedianPercent": float(per_run[name].median()) for name in per_run.columns}


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    results, runs = {}, {}
    for arm in ARMS:
        rq3, capture = load(arm)
        window, audit = select_runs(rq3)
        summary = summarize(window)
        summary["admit"] = admit_rates(window, capture)
        summary["decisionQuality"] = decision_quality(window)
        results[arm] = summary
        runs[arm] = run_level(window)

        # Every included run must contribute exactly the 29 transitions the table counts.
        assert summary["transitions"] == summary["runs"] * TRANSITIONS, summary

        print("=" * 96)
        print(f"[{arm}] {', '.join(ARMS[arm])}")
        print(audit.to_string(index=False))
        print(json.dumps(summary, indent=2, default=float))
        print(runs[arm].round(1).to_string())
        per_shot(window).to_csv(os.path.join(OUT_DIR, f"{arm}.csv"), index=False, lineterminator="\n")

    cost = pd.DataFrame([{
        "no_pacing_delay_s": round(results["no_pacing"]["totalDelayMedianS"], 3),
        "no_pacing_max_backlog_s": round(results["no_pacing"]["bMaxS"], 3),
        "static_delay_s": "nan", "static_max_backlog_s": "nan",
        "queue_dynamic_delay_s": "nan", "queue_dynamic_max_backlog_s": "nan",
        "ours_delay_s": round(results["ours"]["totalDelayMedianS"], 3),
        "ours_max_backlog_s": round(results["ours"]["bMaxS"], 3),
    }])
    cost.to_csv(os.path.join(OUT_DIR, "backlog_cost.csv"), index=False, lineterminator="\n")

    # Run-level rank test. n is 8 and 9, so report the exact test rather than a
    # normal approximation, and keep the per-run range alongside every p-value.
    print("=" * 96)
    comparison = {}
    for metric in runs["no_pacing"].columns:
        a, b = runs["no_pacing"][metric], runs["ours"][metric]
        _, p = mannwhitneyu(a, b, alternative="two-sided")
        comparison[metric] = dict(
            noPacingMedian=float(a.median()), noPacingMin=float(a.min()), noPacingMax=float(a.max()),
            oursMedian=float(b.median()), oursMin=float(b.min()), oursMax=float(b.max()),
            mannWhitneyP=float(p))
        print(f"{metric:22s} NoPacing {a.median():9.1f} [{a.min():.1f}, {a.max():.1f}]"
              f"   Ours {b.median():9.1f} [{b.min():.1f}, {b.max():.1f}]   p={p:.4f}")

    payload = dict(condition=dict(resolution="12MP normal", startingOverheatLevel=START_LEVEL,
                                  window=WINDOW, transitionsPerRun=TRANSITIONS,
                                  nearDeadlineFraction=NEAR_DEADLINE_FRACTION,
                                  workbooks=ARMS),
                   arms=results, runLevelComparison=comparison)
    with open(os.path.join(PAPER_RQ3_DIR, "rq3_metrics.json"), "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, default=float)
        handle.write("\n")
    print(f"\nwrote {os.path.join(PAPER_RQ3_DIR, 'rq3_metrics.json')}")


if __name__ == "__main__":
    # PERCENTILE.INC reference values from the exporter's own interpolation rule.
    assert abs(pct_inc([1, 2, 3, 4], 0.95) - 3.85) < 1e-9
    assert pct_inc([10], 0.5) == 10.0
    assert pct_inc([], 0.5) is None
    main()
