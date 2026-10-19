---
name: multiframe-efficient-work
description: Classify and budget every Multiframe task before implementation, investigation, review, or trivial edits. Use for reasoning-effort selection, stable context reuse, and prevention of requests approaching 272000 input tokens.
---

# Multiframe efficient work

## Classify before work

Classify each incoming task and meaningful scope change before reading large files or editing. Keep classification internal for trivial work; include it briefly in the normal progress update for larger work. Explicit user model/effort choices take precedence.

| Tier | Examples | Initial effort | Escalation |
| --- | --- | --- | --- |
| Trivial | Typo, formatting, isolated mechanical refactor | Lowest supported effort; Astra `low` | Only after a failed focused attempt or failed relevant validation: `medium`, then reassess scope |
| Substantial | Merge engine, colour pipeline, capture plumbing, layout architecture | `medium`; `high` for demonstrated cross-component complexity | Reserve `xhigh` / `max` for this tier, with a concrete unresolved correctness problem |
| Research/investigation | Usage audit, API verification, bug reproduction | `low` for lookup; `medium` for synthesis | Narrow hypotheses first; reclassify as substantial before `xhigh` / `max` |

Do not escalate because a tool is unavailable, a build is slow, or output is long. Avoid repeated setting changes inside a conversation. Astra's minimum is `low`, not `none`. Do not switch models or spawn agents solely to implement this policy.

Inspect active turn metadata when available; a global config default is not proof of active effort. Set the selected effort using an available runtime control before execution. A CLI launch can use `-c model_reasoning_effort="low"`. If this host exposes no control, report any material mismatch once and recommend the model picker/CLI setting; never claim prose changed the running model. Do not alter global settings. Classifying a subtask does not retroactively change its request's effort.

## Stable project block

Multiframe is an offline Android computational photography app: measured sensor data only, no generative AI/neural imaging or third-party imaging libraries. Kotlin/Compose coordinates Camera2; C++20 NDK and NEON process pixels; mmap/ZSL buffers avoid unnecessary copies. PHOTO and NIGHT are primary modes; sub-pixel super-resolution is automatic zoom processing; Brenizer lives in Labs. Preserve natural tonality, highlight roll-off, shadow depth, sensor-clipped neutral recovery, and native/host parity. Preserve the documented develop order and read the relevant invariant before pipeline changes.

Keep pipeline work off the UI thread. Gate hardware features through CameraCapabilities/LensProfile. Use Ink/Type/Haptics tokens, accessible labels and at least 48dp targets. Keep the photograph unobstructed. Prefer DngDevelopBench and focused tests; do not run the full device suite for routine edits. Root AGENTS.md is authoritative; this summary does not replace its detailed invariants.

## Preserve the prefix; minimize the suffix

This file is the canonical stable block of architecture summary, style rules and budget instructions. Load it once per task context. Do not reword, reorder, date-stamp, duplicate or paste it anew on every turn. Keep task facts, progress, logs, code excerpts and evidence separate after stable instructions. Change the block only when project policy actually changes.

Where a request assembler is controlled, insert these unchanged bytes once in the initial developer-instruction block before variable history, preserving required higher-priority instructions and stable tool schemas. Reuse that same prefix in subsequent assembled requests; append new messages rather than editing old ones. Use supported cache breakpoints/settings where available. Do not claim a cache hit until usage telemetry reports it.

Codex Desktop owns prompt assembly, hidden instructions and cache settings. This skill cannot force its prefix placement or guarantee hits. In Desktop, preserve existing instruction text, avoid repeated full-file reads, and monitor actual cached-input ratios. Read root AGENTS once; do not also read its identical CLAUDE copy. Use targeted searches and bounded excerpts from PRD/REFERENCE. Re-read only changed/relevant sections. Save large logs locally and return concise failures/counts. Batch independent reads; avoid repeated model calls merely to poll unchanged build output. After compaction recover only missing facts, not the entire repository.

## Context ceiling

Budget each individual request, never cumulative session input. Count all instructions, history, tools, new results and multimodal input with the runtime/model's authoritative tokenizer/count facility. Cached tokens still count toward the limit. Do not use characters/4 or the previous call's count as an exact next-call measurement.

At 240000 projected input tokens, proactively narrow context. At or above 272000, refuse to assemble/send that request: flag its size and propose one subsystem, selected excerpts, or a compact handoff. Proceed with the smaller scope when it still satisfies the request; obtain explicit direction before any deliberate exception. Do not silently remove essential safety or correctness instructions.

For a controlled assembler, run `python3 scripts/context_guard.py --input-tokens N` on the complete measured request. It adds 8192 tokens of default headroom; set `--reserve 0` only when the count covers the final fully serialized request. Missing/invalid counts fail closed. Recount after any additions. The script validates a supplied count, not tokenization or network traffic; the caller must honor its exit status.

Desktop provides no skill-level pre-send interception. If the full count is unavailable, state the limitation when near the boundary and stop bulk ingestion; do not certify compliance. Use reported context usage as a warning signal and propose compaction or a fresh focused task. A new task requires the user's request. Never promise this Markdown is a hard runtime ceiling.

## Finish economically

Run the smallest meaningful validation, stop after it passes unless new evidence warrants more, and report changed behavior, validation and material limitations. Never rerun successful work merely because the conversation resumed. No full camera suite for skill-only changes.
