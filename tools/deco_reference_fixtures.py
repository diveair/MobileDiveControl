"""Generate numeric test fixtures with independently installed DecoTengu 0.14.1.
Reference dependency is test-only; it is not copied into or distributed with the app.
Run: python tools/deco_reference_fixtures.py --reference-dir tmp/deco-reference
"""
import argparse
import sys
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--reference-dir', required=True)
args = parser.parse_args()
sys.path.insert(0, str(Path(args.reference_dir).resolve()))
from decotengu.model import ZH_L16C_GF
from decotengu.engine import Engine, GasMix, Step, Phase
SURFACE = 1.01325
BPM = .0981
CASES = {
    'air_surface': [(0, 5, 21, 0)],
    'air_18m_20min': [(18, .9, 21, 0), (18, 20, 21, 0)],
    'air_30m_20min': [(30, 1.5, 21, 0), (30, 20, 21, 0)],
    'air_40m_30min': [(40, 2, 21, 0), (40, 30, 21, 0)],
    'ean32_30m_20min': [(30, 1.5, 32, 0), (30, 20, 32, 0)],
    'ean36_24m_35min': [(24, 1.2, 36, 0), (24, 35, 36, 0)],
    'trimix_50m_25min': [(50, 2.5, 21, 35), (50, 25, 21, 35)],
    'trimix_deco': [(50, 2.5, 21, 35), (50, 25, 21, 35), (21, 29/9, 21, 35), (21, 10, 50, 0), (6, 15/9, 50, 0), (6, 20, 100, 0)],
    'repetitive_air': [(18, .9, 21, 0), (18, 40, 21, 0), (5, 13/9, 21, 0), (5, 3, 21, 0), (0, 5/9, 21, 0), (0, 60, 21, 0), (18, .9, 21, 0), (18, 20, 21, 0)],
    'short_exposure': [(1.234, .00005, 32, 0), (1.234, .00005, 32, 0)],
}
rows = ['# DecoTengu 0.14.1 ZH-L16C; surface=1.01325 bar; water=0.0981 bar/m; initial N2 fraction=.79; vapour=.0627 bar; GF=.40/.85; ascent=9m/min',
        '# case|segments(end_depth,minutes,O2,He;...)|N2 tissues|He tissues|GF40 ceiling bar|GF85 ceiling bar|NDL whole seconds (99min cap)']
for name, segments in CASES.items():
    model = ZH_L16C_GF()
    model.START_P_N2 = .79
    model.gf_low = .4
    model.gf_high = .85
    engine = Engine()
    engine.model = model
    engine.surface_pressure = SURFACE
    engine._meter_to_bar = BPM
    engine.ascent_rate = 9
    data = model.init(SURFACE)
    depth = 0
    for end, minutes, oxygen, helium in segments:
        gas = GasMix(0, oxygen, 100 - oxygen - helium, helium)
        data = model.load(SURFACE + depth * BPM, minutes, gas, (end-depth)*BPM/minutes, data)
        depth = end
    p = SURFACE + depth * BPM
    def allows(seconds):
        held = model.load(p, seconds/60, gas, 0, data) if seconds else data
        start = Step(Phase.CONST, p, 0, gas, held)
        return model.ceiling_limit(held, .85) <= SURFACE if depth == 0 else engine._ndl_ascent(start, gas) is not None
    if not allows(0): ndl = 0
    elif allows(5940): ndl = 5940
    else:
        lo, hi = 0, 5940
        while hi - lo > 1:
            mid = (lo+hi)//2
            if allows(mid): lo = mid
            else: hi = mid
        ndl = lo
    values = [name, ';'.join(','.join(map(str,s)) for s in segments),
        ','.join(format(t[0], '.14g') for t in data.tissues), ','.join(format(t[1], '.14g') for t in data.tissues),
        format(model.ceiling_limit(data, .4), '.14g'), format(model.ceiling_limit(data, .85), '.14g'), str(ndl)]
    rows.append('|'.join(values))
path = Path('core/src/test/resources/decompression/decotengu-0.14.1.txt')
path.write_text('\n'.join(rows)+'\n', encoding='utf-8')
print('Wrote', len(CASES), 'independent profile fixtures to', path)

