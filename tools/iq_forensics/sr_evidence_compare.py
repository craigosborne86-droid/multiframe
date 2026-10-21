#!/usr/bin/env python3
"""Before/after tables for two SuperResEvidenceBench runs scored by sr_evidence_analyze.py.

    sr_evidence_compare.py <before-out> <after-out> [<timing-dir>]

Both runs read the same frozen bursts, so every comparison is paired by
burst. The A/A null for a before/after difference is the same build against
itself on the noise twin: the change is claimed only where the null holds and
the effect clears it (sr_evidence_analyze.paired). Writes compare.md beside
the after run.
"""
import json
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from sr_evidence_analyze import SYN_METRICS, paired, table, t_crit  # noqa: E402


def load(out):
    return {r['burst']: r for r in json.loads((out / 'synthetic-metrics.json').read_text())}


def main():
    before_dir, after_dir = Path(sys.argv[1]), Path(sys.argv[2])
    before, after = load(before_dir), load(after_dir)
    md = ['# Before / after: Lucas-Kanade registration\n']
    groups = {}
    for name, r in after.items():
        groups.setdefault((r['optics'], r['motion'], r['noise']), []).append(name)

    # Registration error, sensor pixels, interior tiles.
    rows = []
    for key, names in sorted(groups.items()):
        b = [before[n]['alignmentErrorSensorPx'] for n in names]
        a = [after[n]['alignmentErrorSensorPx'] for n in names]
        rows.append(['/'.join(key), np.mean([x['median'] for x in b]), np.mean([x['median'] for x in a]),
                     np.mean([x['p95'] for x in b]), np.mean([x['p95'] for x in a]),
                     np.mean([x['fittedFraction'] for x in b]), np.mean([x['fittedFraction'] for x in a])])
    md.append('\n## Registration error against the known shift (sensor px, interior tiles, after borrowing)\n\n' +
              table(['config', 'median before', 'median after', 'p95 before', 'p95 after', 'fitted before', 'fitted after'], rows))

    # The shipped canvas against merge Lanczos, before and after, with the A/A verdicts.
    rows = []
    verdicts = []
    for key, names in sorted(groups.items()):
        noisy = key[2] != 'none'
        for mkey, label, get, higher in SYN_METRICS:
            if mkey in ('deMax', 'edgeDeMax') or (mkey == 'snr' and not noisy):
                continue
            mean = lambda runs, m: float(np.mean([get(runs[n]['methods'][m]) for n in names]))
            row = ['/'.join(key), label, mean(before, 'merge-lanczos'), mean(before, 'sr-est'), mean(after, 'sr-est'),
                   mean(after, 'sr-known')]
            verdict = '--'
            if noisy:
                seeds = sorted({after[n]['seed'] for n in names})
                pick = lambda runs, s, t: next(runs[n] for n in names if runs[n]['seed'] == s and runs[n]['twin'] == t)
                d_aa = [get(pick(after, s, 0)['methods'][m]) - get(pick(after, s, 1)['methods'][m])
                        for s in seeds for m in ('sr-est', 'merge-lanczos')]
                d_ab = [get(pick(after, s, t)['methods']['sr-est']) - get(pick(after, s, t)['methods']['merge-lanczos'])
                        for s in seeds for t in (0, 1)]
                d_ba = [get(pick(after, s, t)['methods']['sr-est']) - get(pick(before, s, t)['methods']['sr-est'])
                        for s in seeds for t in (0, 1)]
                d_aa_self = [get(pick(after, s, 0)['methods']['sr-est']) - get(pick(after, s, 1)['methods']['sr-est']) for s in seeds] + \
                            [get(pick(before, s, 0)['methods']['sr-est']) - get(pick(before, s, 1)['methods']['sr-est']) for s in seeds]
                if all(np.isfinite(d_aa + d_ab + d_ba + d_aa_self)):
                    vs_merge = paired(d_ab, d_aa)
                    vs_before = paired(d_ba, d_aa_self)
                    def word(p):
                        if not p['nullHolds']:
                            return 'void'
                        if not p['separates']:
                            return '='
                        return 'better' if (p['meanAB'] > 0) == higher else 'worse'
                    verdict = f"vs merge: {word(vs_merge)}; vs before: {word(vs_before)}"
                    verdicts.append(dict(config='/'.join(key), metric=mkey, vsMerge=vs_merge, vsBefore=vs_before))
            rows.append(row + [verdict])
    md.append('\n## Synthetic: the shipped 2x canvas (estimated alignment) before and after\n\n'
              'Verdicts are the A/A rule of REPORT.md: the twin null first, then paired |t| >= t(0.01) and the '
              'effect beyond twice the null SD. "vs merge" is after against merge Lanczos; "vs before" is '
              'after against before, with the null the same build against its own twin.\n\n' +
              table(['config', 'metric', 'merge Lanczos', '2x before', '2x after', '2x known shifts', 'verdict (after)'], rows))
    (after_dir / 'compare-verdicts.json').write_text(json.dumps(verdicts, indent=1) + '\n')

    if len(sys.argv) > 3:
        md.append(timing(Path(sys.argv[3])))
    (after_dir / 'compare.md').write_text(''.join(md))
    print(''.join(md)[:3000])


def timing(d):
    runs = {}
    for f in sorted(d.glob('timing-*.txt')):
        p = dict(l.split('=', 1) for l in f.read_text().splitlines() if '=' in l)
        runs.setdefault(p['label'], []).append([float(v) for v in p['repsMs'].split(',')])
    if not {'before', 'after'} <= runs.keys():
        return ''
    b = [float(np.median(r)) for r in runs['before']]
    a = [float(np.median(r)) for r in runs['after']]
    # A/A: the before runs against each other.
    aa = [x - y for i, x in enumerate(b) for y in b[i + 1:]]
    rows = [['before (V-fit)', len(b), *[round(v, 1) for v in b]], ['after (LK)', len(a), *[round(v, 1) for v in a]]]
    spread = max(abs(v) for v in aa) / np.mean(b) if aa else float('nan')
    ratio = np.median(a) / np.median(b)
    return ('\n## Alignment time, host replay (7 bursts, 105 alignments per rep, median of 5 reps per run)\n\n' +
            table(['build', 'runs'] + [f'run {i + 1} ms' for i in range(max(len(b), len(a)))], rows) +
            f'\nA/A: the before runs differ from each other by at most {spread * 100:.1f} % of their mean. '
            f'After / before, median of runs: {ratio:.3f} ({(ratio - 1) * 100:+.1f} %).\n')


if __name__ == '__main__':
    main()
