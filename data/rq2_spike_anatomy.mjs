// RQ2 unsafe-admit spike anatomy for figures/fig_rq2_unsafe_spike_anatomy.tex.
//
// Implements section 5.4 of the paper repository's docs/rq1-rq3-metrics-guide.md,
// with the baseline taken as the immediately preceding capture of the same run and
// optional-work group rather than an average over three recent safe decisions.
//
// Run:  node data/rq2_spike_anatomy.mjs [--baseline recent-safe]
import { readWorkbook, asBool } from "./xlsx_read.mjs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const DIR = dirname(fileURLToPath(import.meta.url));
const BOOKS = {
  A: "48U_metrics_12MP_normal_0729_PacingOnly_1.xlsx",
  B: "48U_metrics_24MP_memory_0729_PacingOnly_1.xlsx",
  C: "48U_metrics_24MP_memory_0729_PacingOnly_2.xlsx",
};
// AdmissionReplay only carries rows for nodes that made an admission decision, so
// the node suffix has to come from the per-node sheets or the CPU sums are short.
const NODE_SHEETS = ["DynamicFunctionNode", "SecDualBokehNode", "SecFilterNode",
                     "SecImageCodecNode", "WatermarkNode"];
const GROUP = { Bokeh: "M", Filter: "S" };
const MODE = process.argv.includes("--baseline")
  ? process.argv[process.argv.indexOf("--baseline") + 1]
  : "previous";

const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const round = (x) => Math.round(x);

const byBook = {};
const events = [];

for (const [tag, file] of Object.entries(BOOKS)) {
  const sheets = readWorkbook(join(DIR, file));
  const adm = sheets.AdmissionReplay;
  const draftEnd = new Map(sheets.PacingReplay.map((r) => [r.captureIndex, r.draftEndUptimeMs]));

  const nodesBy = new Map();
  for (const name of NODE_SHEETS) {
    for (const r of sheets[name] ?? []) {
      if (!nodesBy.has(r.captureIndex)) {
        nodesBy.set(r.captureIndex, []);
      }
      nodesBy.get(r.captureIndex).push(r);
    }
  }
  for (const rows of nodesBy.values()) {
    rows.sort((a, b) => a.nodeOrder - b.nodeOrder);
  }

  // Runs are delimited by a ppSequenceId reset, as everywhere else in the guide.
  const captures = [...new Set(adm.map((r) => r.captureIndex))];
  const pp = new Map(adm.map((r) => [r.captureIndex, r.ppSequenceId]));
  const runOf = new Map(), shotOf = new Map();
  let runId = 1, shot = 0, previous = null;
  for (const c of captures) {
    if (previous !== null && pp.get(c) <= previous) {
      runId++;
      shot = 0;
    }
    shot++;
    runOf.set(c, runId);
    shotOf.set(c, shot);
    previous = pp.get(c);
  }

  const admBy = new Map();
  for (const r of adm) {
    if (!admBy.has(r.captureIndex)) {
      admBy.set(r.captureIndex, []);
    }
    admBy.get(r.captureIndex).push(r);
  }

  const selected = [];
  for (const c of captures) {
    const decisions = admBy.get(c).slice().sort((x, y) => x.nodeOrder - y.nodeOrder);
    const nodes = nodesBy.get(c) ?? [];
    for (const stage of ["Bokeh", "Filter"]) {
      const d = decisions.find((r) => r.admissionStage === stage);
      if (!d) {
        continue;
      }
      const suffix = nodes.filter((r) => r.nodeOrder >= d.nodeOrder);
      // The audit build forces every optional node, so the measured suffix
      // contains work this same model decided to skip. Charging that work to the
      // decision under test overstates its cost, so every quantity is also
      // computed over the subset the model would actually have run: nodes that
      // carry no admission decision always execute, the rest only when the fresh
      // model admitted them.
      const skipsModel = (r) => {
        const m = decisions.find((x) => x.nodeOrder === r.nodeOrder);
        return !!(m && m.admissionStage && !asBool(m.afterModelAdmit));
      };
      // The decision under test is the one being hypothetically admitted, so it
      // always stays in the sum whatever the model said about it.
      const modelSubset = suffix.filter((r) => r.nodeOrder === d.nodeOrder || !skipsModel(r));
      const droppedMs = suffix
        .filter((r) => !modelSubset.includes(r))
        .reduce((s, r) => s + (r.durationMs ?? 0), 0);
      // A skip before the decision node shortens the path to it, so it raises
      // this decision's own budget rather than lowering its cost.
      const budgetGainMs = nodes
        .filter((r) => r.nodeOrder < d.nodeOrder && skipsModel(r))
        .reduce((s, r) => s + (r.durationMs ?? 0), 0);
      const decisionNode = nodes.find((r) => r.nodeOrder === d.nodeOrder);
      const cpu = suffix.reduce((s, r) => s + (r.cpuTimeMs ?? 0), 0);
      const wall = suffix.reduce((s, r) => s + (r.wallTimeMs ?? 0), 0);
      const cpuModel = modelSubset.reduce((s, r) => s + (r.cpuTimeMs ?? 0), 0);
      const wallModel = modelSubset.reduce((s, r) => s + (r.wallTimeMs ?? 0), 0);
      const end = draftEnd.get(c);
      const cost = end != null && d.nodeStartUptimeMs != null ? end - d.nodeStartUptimeMs : null;
      selected.push({
        book: tag, captureIndex: c, run: runOf.get(c), shot: shotOf.get(c),
        group: GROUP[stage], budget: d.beforeBudgetMs,
        upperBound: d.beforeSequencePredictedUpperBoundMs,
        cost,
        costModel: cost == null ? null : cost - droppedMs,
        droppedMs,
        budgetModel: d.beforeBudgetMs + budgetGainMs,
        budgetGainMs,
        nodeDurationMs: decisionNode?.durationMs ?? null,
        nodeWatchdogMs: decisionNode?.watchdogTimeoutMs ?? null,
        cpu, wall,
        cpuModel, wallModel,
        busyCoresModel: wallModel > 0 ? cpuModel / wallModel : null,
        busyCores: wall > 0 ? cpu / wall : null,
        gc: suffix.reduce((s, r) => s + (r.blockingGcTimeMs ?? 0), 0),
        runQueue: suffix.reduce((s, r) => s + (r.runQueueWaitMs ?? 0), 0),
        ctxSwitch: suffix.reduce((s, r) => s + (r.nonvoluntaryCtxSwitches ?? 0), 0),
        modelAdmit: asBool(d.afterModelAdmit),
        overheat: d.overheatLevel, thermal: d.thermalStatus,
      });
    }
  }
  byBook[tag] = selected;
  for (const s of selected) {
    // RQ2 scores the model's judgement on the decision it made, so the label is
    // the measured outcome: this suffix, as executed, missed this budget. The
    // model-subset quantities below are reported next to it as interpretation,
    // never as the classifier - redefining the label by them would answer a
    // different question (was the whole decision set safe end to end) and would
    // read as moving the metric in the model's favour.
    if (s.modelAdmit && s.cost != null && s.cost > s.budget) {
      events.push(s);
    }
  }
}

function baselineOf(event) {
  const pool = byBook[event.book]
    .filter((s) => s.run === event.run && s.group === event.group && s.shot < event.shot)
    .sort((a, b) => a.shot - b.shot);
  return MODE === "recent-safe"
    ? pool.filter((s) => s.modelAdmit && s.cost != null && s.cost <= s.budget).slice(-3)
    : pool.slice(-1);
}

const rows = [];
for (const e of events) {
  const base = baselineOf(e);
  if (!base.length) {
    console.error(`no baseline for ${e.book} run${e.run} shot${e.shot}`);
    continue;
  }
  // Average busy cores is CPU time / wall time. Baselines average that quantity
  // itself, so the baseline wall is cpu_p / cores_p and the split below is exact:
  //   cpuTerm + coreTerm = cpu_e/cores_e - cpu_p/cores_p = wall_e - wall_p
  // Reported quantities are the measured ones, matching the label.
  const costP = mean(base.map((b) => b.cost));
  const cpuP = mean(base.map((b) => b.cpu));
  const coresP = mean(base.map((b) => b.busyCores));
  const coresE = e.busyCores;
  const wallP = cpuP / coresP;
  const cpuTerm = (e.cpu - cpuP) / coresP;
  const coreTerm = e.cpu * (1 / coresE - 1 / coresP);
  rows.push({
    ...e, costP, cpuP, coresP, coresE, wallP, cpuTerm, coreTerm,
    total: e.wall - wallP, growth: e.cost / costP, overrun: e.cost - e.budget,
    baseShots: base.map((b) => b.shot).join(","),
    baseAllSafe: base.every((b) => b.cost <= b.budget),
    gcP: mean(base.map((b) => b.gc)),
    runQueueP: mean(base.map((b) => b.runQueue)),
    ctxSwitchP: mean(base.map((b) => b.ctxSwitch)),
    overheatP: [...new Set(base.map((b) => b.overheat))].join("/"),
    thermalP: [...new Set(base.map((b) => b.thermal))].join("/"),
  });
}
rows.sort((a, b) => (a.group === b.group ? b.growth - a.growth : a.group < b.group ? -1 : 1));
let m = 0, s = 0;
for (const r of rows) {
  r.id = r.group === "M" ? `M${++m}` : `S${++s}`;
}

// The split must reproduce the measured wall-time difference exactly.
for (const r of rows) {
  console.assert(Math.abs(r.cpuTerm + r.coreTerm - r.total) < 1e-6, `split broken for ${r.id}`);
}
console.log(`baseline=${MODE}  unsafe model-admits=${events.length}` +
  `  every baseline itself safe=${rows.every((r) => r.baseAllSafe)}`);

console.log("\n  id  book run shot     B   Cprev      C      UB  growth  overrun");
for (const r of rows) {
  console.log(`  ${r.id}    ${r.book}   ${String(r.run).padStart(2)}   ${String(r.shot).padStart(2)}` +
    `  ${String(r.budget).padStart(4)}  ${r.costP.toFixed(1).padStart(6)}  ${String(r.cost).padStart(5)}` +
    `  ${r.upperBound.toFixed(1).padStart(6)}  ${r.growth.toFixed(2).padStart(6)}  ${String(r.overrun).padStart(7)}`);
}

// Remaining-sequence wall time is CPU work divided by the cores serving it, so the
// figure plots those two measured quantities before and after rather than an
// abstract split of the time difference.
console.log("\n  id   cpu_p   cpu_e  factor   cores_p  cores_e  factor   wall_p  wall_e  factor" +
  "   nodeWall/C_p  nodeWall/C_e");
for (const r of rows) {
  console.log(`  ${r.id}  ${String(round(r.cpuP)).padStart(6)}  ${String(r.cpu).padStart(6)}` +
    `   ${(r.cpu / r.cpuP).toFixed(3)}      ${r.coresP.toFixed(2)}     ${r.coresE.toFixed(2)}` +
    `   ${(r.coresE / r.coresP).toFixed(3)}   ${String(round(r.wallP)).padStart(6)}` +
    `  ${String(r.wall).padStart(6)}   ${(r.wall / r.wallP).toFixed(3)}` +
    `         ${(r.wallP / r.costP).toFixed(3)}         ${(r.wall / r.cost).toFixed(3)}`);
}

console.log("\n  additive split, kept for the text only (not plotted)");
console.log("  id   cpuTerm  coreTerm  total  coreShare");
for (const r of rows) {
  const sign = (x) => (x >= 0 ? `+${round(x)}` : `${round(x)}`);
  console.log(`  ${r.id}  ${sign(r.cpuTerm).padStart(7)}  ${sign(r.coreTerm).padStart(8)}` +
    `  ${sign(r.total).padStart(5)}   ${(100 * r.coreTerm / r.total).toFixed(0)}%`);
}

// What the shipped build would have done with the same decision. Two independent
// safeguards, reported as interpretation next to the measured label above.
console.log("\n  would this have shipped as a Capture Timeout?");
console.log("  id   node dur / watchdog   watchdog?   C_model / B_model   fits?   verdict");
for (const r of rows) {
  const trips = r.nodeWatchdogMs != null && r.nodeDurationMs > r.nodeWatchdogMs;
  const fits = r.costModel <= r.budgetModel;
  const tag = [trips ? "W" : null, fits ? "B" : null].filter(Boolean).join("+") || "NONE";
  console.log(`  ${r.id}   ${String(r.nodeDurationMs).padStart(5)} / ${String(r.nodeWatchdogMs).padStart(5)}` +
    `   ${trips ? `cut +${r.nodeDurationMs - r.nodeWatchdogMs}`.padEnd(10) : `no, ${r.nodeWatchdogMs - r.nodeDurationMs} spare`.padEnd(10)}` +
    `   ${String(r.costModel).padStart(5)} / ${String(r.budgetModel).padStart(5)}` +
    `   ${fits ? "yes" : "no "}     ${tag}`);
}
console.log("  W = the per-node watchdog would have cut it first.");
console.log("  B = it fits once the model's own skips are honoured on both sides.");

console.log("\n  panel (a) x values [Cprev/B, UB/B, C/B]");
for (const r of rows) {
  console.log(`  ${r.id}  ${(r.costP / r.budget).toFixed(3)}  ${(r.upperBound / r.budget).toFixed(3)}` +
    `  ${(r.cost / r.budget).toFixed(3)}`);
}

console.log("\n  control signals, baseline -> event");
for (const r of rows) {
  console.log(`  ${r.id} gc ${round(r.gcP)}->${r.gc}  runQueue ${round(r.runQueueP)}->${r.runQueue}` +
    `  ctxSwitch ${round(r.ctxSwitchP)}->${r.ctxSwitch}` +
    `  overheat ${r.overheatP}->${r.overheat}  thermal ${r.thermalP}->${r.thermal}`);
}
