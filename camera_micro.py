import csv
import math
from collections import defaultdict

CSV_PATH = r"C:\Users\Ghost\AppData\Roaming\PrismLauncher\instances\WaldinDastinner1.12.10\minecraft\camera_debug.csv"

rows = []
with open(CSV_PATH, 'r') as f:
    reader = csv.DictReader(f)
    for r in reader:
        row = {}
        for k, v in r.items():
            try: row[k] = float(v)
            except: row[k] = v
        rows.append(row)

N = len(rows)
duration_s = (rows[-1]['time_ms'] - rows[0]['time_ms']) / 1000.0
fps = N / duration_s if duration_s > 0 else 0
print(f"FRAMES: {N}   DURATION: {duration_s:.1f}s   FPS: {fps:.1f}")

# Deltas
yaw_deltas = []
pitch_deltas = []
for i in range(1, N):
    dy = rows[i]['yaw'] - rows[i-1]['yaw']
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    while dy > 180: dy -= 360
    while dy < -180: dy += 360
    yaw_deltas.append(dy)
    pitch_deltas.append(dp)

# =====================================================================
print("\n" + "=" * 75)
print("1. QUANTIZATION GRID (MICROSCOPIC)")
print("=" * 75)

# Find step
best_sens = -1
best_step = 0
best_match = 0
for sens_pct in range(1, 201):
    s = sens_pct / 200.0
    f = s * 0.6 + 0.2
    step = f * f * f * 8.0 * 0.15
    if step < 0.001: continue
    clean = 0
    for d in yaw_deltas + pitch_deltas:
        if abs(d) < 0.00001: clean += 1; continue
        ratio = abs(d) / step
        residual = abs(ratio - round(ratio))
        if residual < 0.01: clean += 1
    pct = clean / (2 * len(yaw_deltas))
    if pct > best_match:
        best_match = pct; best_sens = sens_pct; best_step = step

step = best_step
print(f"Sensitivity: {best_sens}%  step={step:.6f} deg/px  overall grid match: {best_match*100:.1f}%")

# Per-axis
for name, deltas in [("Yaw", yaw_deltas), ("Pitch", pitch_deltas)]:
    clean = dirty = subpx = 0
    for d in deltas:
        if abs(d) < 0.00001: clean += 1; continue
        if abs(d) < step * 0.5: subpx += 1
        ratio = abs(d) / step
        residual = abs(ratio - round(ratio))
        if residual < 0.01: clean += 1
        else: dirty += 1
    print(f"  {name}: on-grid={clean} ({clean/len(deltas)*100:.1f}%)  off-grid={dirty} ({dirty/len(deltas)*100:.1f}%)  sub-pixel={subpx}")

# =====================================================================
print("\n" + "=" * 75)
print("2. PIXEL DISTRIBUTION")
print("=" * 75)

for name, deltas in [("Yaw", yaw_deltas), ("Pitch", pitch_deltas)]:
    px_counts = defaultdict(int)
    for d in deltas:
        if abs(d) < 0.00001: px_counts[0] += 1
        else: px_counts[round(abs(d) / step)] += 1
    print(f"\n  {name} pixels per frame:")
    mx = max(px_counts.values()) if px_counts else 1
    for px in sorted(px_counts.keys())[:25]:
        bar = "#" * min(50, px_counts[px] * 50 // mx)
        print(f"    {px:3d}px: {px_counts[px]:5d} ({px_counts[px]/len(deltas)*100:.1f}%) {bar}")

# =====================================================================
print("\n" + "=" * 75)
print("3. VELOCITY PROFILES")
print("=" * 75)

total_vels = [math.sqrt(r['yaw_vel']**2 + r['pitch_vel']**2) for r in rows]
yaw_vels = [r['yaw_vel'] for r in rows]
pitch_vels = [r['pitch_vel'] for r in rows]
tv = sorted(total_vels)
print(f"Total vel: mean={sum(tv)/N:.1f}  med={tv[N//2]:.1f}  p95={tv[int(N*0.95)]:.1f}  max={tv[-1]:.1f} deg/s")
pv = sorted([abs(v) for v in pitch_vels])
print(f"Pitch vel: mean={sum(pv)/N:.1f}  med={pv[N//2]:.1f}  p95={pv[int(N*0.95)]:.1f}  max={pv[-1]:.1f} deg/s")

# Velocity histogram
print("\n  Velocity distribution:")
bins = [0, 2, 5, 10, 20, 40, 80, 150, 300, 500, 9999]
for bi in range(len(bins)-1):
    cnt = sum(1 for v in total_vels if bins[bi] <= v < bins[bi+1])
    pct = cnt / N * 100
    bar = "#" * min(40, int(pct))
    label = f"{bins[bi]}-{bins[bi+1]}" if bins[bi+1] < 9999 else f"{bins[bi]}+"
    print(f"    {label:>8s}: {pct:5.1f}% {bar}")

# =====================================================================
print("\n" + "=" * 75)
print("4. ACCELERATION (JERKINESS)")
print("=" * 75)

yaw_accels = []
pitch_accels = []
total_accels = []
for i in range(1, N):
    dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
    if 0.0001 < dt_s < 0.1:
        ya = (rows[i]['yaw_vel'] - rows[i-1]['yaw_vel']) / dt_s
        pa = (rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s
        yaw_accels.append(ya)
        pitch_accels.append(pa)
        total_accels.append(math.sqrt(ya*ya + pa*pa))

if total_accels:
    ta = sorted(total_accels)
    pa_s = sorted([abs(a) for a in pitch_accels])
    print(f"Total accel: mean={sum(ta)/len(ta):.0f}  p90={ta[int(len(ta)*0.9)]:.0f}  p95={ta[int(len(ta)*0.95)]:.0f}  p99={ta[int(len(ta)*0.99)]:.0f}  max={ta[-1]:.0f}")
    print(f"Pitch accel: mean={sum(pa_s)/len(pa_s):.0f}  p90={pa_s[int(len(pa_s)*0.9)]:.0f}  p95={pa_s[int(len(pa_s)*0.95)]:.0f}  p99={pa_s[int(len(pa_s)*0.99)]:.0f}  max={pa_s[-1]:.0f}")

    for thresh in [1000, 2000, 3000, 5000]:
        cnt = sum(1 for a in total_accels if a > thresh)
        print(f"  Jerks >{thresh}: {cnt} ({cnt/len(total_accels)*100:.2f}%)")

# =====================================================================
print("\n" + "=" * 75)
print("5. DIRECTION REVERSALS & RUN LENGTHS")
print("=" * 75)

for name, deltas in [("Yaw", yaw_deltas), ("Pitch", pitch_deltas)]:
    rev = 0
    for i in range(1, len(deltas)):
        if deltas[i] * deltas[i-1] < 0 and abs(deltas[i]) > 0.01 and abs(deltas[i-1]) > 0.01:
            rev += 1

    runs = []
    cur = 1
    for i in range(1, len(deltas)):
        if deltas[i] * deltas[i-1] > 0: cur += 1
        else: runs.append(cur); cur = 1
    runs.append(cur)
    rl = sorted(runs)
    print(f"  {name} reversals: {rev}/{len(deltas)-1} ({rev/(len(deltas)-1)*100:.1f}%)")
    print(f"  {name} same-dir runs: mean={sum(runs)/len(runs):.1f}  med={rl[len(rl)//2]}  p90={rl[int(len(rl)*0.9)]}  max={max(runs)}")

# Alternating 1px
for name, deltas in [("Yaw", yaw_deltas), ("Pitch", pitch_deltas)]:
    alt = 0
    for i in range(1, len(deltas)):
        d1, d2 = deltas[i-1], deltas[i]
        if abs(abs(d1) - step) < 0.005 and abs(abs(d2) - step) < 0.005 and d1 * d2 < 0:
            alt += 1
    print(f"  {name} alternating 1px: {alt} ({alt/(len(deltas)-1)*100:.2f}%)")

# =====================================================================
print("\n" + "=" * 75)
print("6. TREMOR ANALYSIS")
print("=" * 75)

ty = [r['tremor_yaw'] for r in rows]
tp = [r['tremor_pitch'] for r in rows]
print(f"Tremor yaw:   mean_abs={sum(abs(t) for t in ty)/N:.6f}  max={max(abs(t) for t in ty):.6f}")
print(f"Tremor pitch: mean_abs={sum(abs(t) for t in tp)/N:.6f}  max={max(abs(t) for t in tp):.6f}")

# Frequency
yc = sum(1 for i in range(1, N) if ty[i] * ty[i-1] < 0)
pc = sum(1 for i in range(1, N) if tp[i] * tp[i-1] < 0)
print(f"Tremor freq: yaw={yc/(2*duration_s):.1f}Hz  pitch={pc/(2*duration_s):.1f}Hz  (target: 8-12Hz)")

# =====================================================================
print("\n" + "=" * 75)
print("7. BREATHING")
print("=" * 75)
br = [r['breath_offset'] for r in rows]
print(f"Breath: range={max(br)-min(br):.4f}  mean={sum(br)/N:.4f}")
bc = sum(1 for i in range(1, N) if (br[i]-sum(br)/N) * (br[i-1]-sum(br)/N) < 0)
print(f"Breath freq: {bc/(2*duration_s):.2f}Hz  (target: ~0.25Hz)")

# =====================================================================
print("\n" + "=" * 75)
print("8. FALL CAMERA ANALYSIS")
print("=" * 75)

fall_eps = []
in_f = False; fs = 0
for i in range(N):
    if rows[i]['falling'] == 1 and not in_f: in_f = True; fs = i
    elif rows[i]['falling'] == 0 and in_f: in_f = False; fall_eps.append((fs, i-1))
if in_f: fall_eps.append((fs, N-1))

short = [(s,e) for s,e in fall_eps if rows[e]['time_ms']-rows[s]['time_ms'] < 200]
medium = [(s,e) for s,e in fall_eps if 200 <= rows[e]['time_ms']-rows[s]['time_ms'] < 600]
long_f = [(s,e) for s,e in fall_eps if rows[e]['time_ms']-rows[s]['time_ms'] >= 600]
print(f"Falls: {len(fall_eps)} total  short(<200ms)={len(short)}  medium={len(medium)}  long={len(long_f)}")

# Fall reaction times
for label, falls in [("Medium+Long", medium + long_f)]:
    react_times = []
    no_react = 0
    for fs, fe in falls:
        dur = rows[fe]['time_ms'] - rows[fs]['time_ms']
        base = rows[fs]['pitch']
        found = False
        for i in range(fs, fe+1):
            if rows[i]['pitch'] > base + 1.0:
                react_times.append(rows[i]['time_ms'] - rows[fs]['time_ms'])
                found = True; break
        if not found: no_react += 1

    if react_times:
        rt = sorted(react_times)
        print(f"  {label} reaction: mean={sum(rt)/len(rt):.0f}ms  med={rt[len(rt)//2]:.0f}ms  range={rt[0]:.0f}-{rt[-1]:.0f}ms")
    print(f"  No reaction: {no_react}/{len(falls)}")

# Fall pitch acceleration
fall_set = set()
for fs, fe in fall_eps:
    for i in range(fs, fe+1): fall_set.add(i)

fall_pa = [abs(pitch_accels[i-1]) for i in range(1, N) if i in fall_set and i-1 < len(pitch_accels)]
norm_pa = [abs(pitch_accels[i-1]) for i in range(1, N) if i not in fall_set and i-1 < len(pitch_accels)]
if fall_pa:
    fpa = sorted(fall_pa)
    print(f"  Fall pitch accel: mean={sum(fpa)/len(fpa):.0f}  p95={fpa[int(len(fpa)*0.95)]:.0f}  p99={fpa[int(len(fpa)*0.99)]:.0f}  max={fpa[-1]:.0f}")
if norm_pa:
    npa = sorted(norm_pa)
    print(f"  Normal pitch accel: mean={sum(npa)/len(npa):.0f}  p95={npa[int(len(npa)*0.95)]:.0f}  p99={npa[int(len(npa)*0.99)]:.0f}  max={npa[-1]:.0f}")

# Saccades during falls
sacc_during_fall = sum(1 for i in range(N) if rows[i]['saccade'] == 1 and i in fall_set)
print(f"  Saccade frames during falls: {sacc_during_fall}/{len(fall_set)} ({sacc_during_fall/max(1,len(fall_set))*100:.1f}%)")

# =====================================================================
print("\n" + "=" * 75)
print("9. PITCH WHILE RUNNING")
print("=" * 75)

running = [i for i in range(N) if rows[i]['saccade'] == 0 and rows[i]['falling'] == 0 and rows[i]['error'] < 10]
print(f"Running frames: {len(running)} ({len(running)/N*100:.1f}%)")
if running:
    rpd = [abs(rows[i]['pitch'] - rows[i-1]['pitch']) for i in running if i > 0]
    zero = sum(1 for d in rpd if d < 0.001)
    print(f"  Pitch static: {zero}/{len(rpd)} ({zero/len(rpd)*100:.1f}%)")
    print(f"  Mean pitch delta: {sum(rpd)/len(rpd):.4f}")

    # Run direction
    rd = [rows[i]['pitch'] - rows[i-1]['pitch'] for i in running if i > 0]
    rrev = sum(1 for j in range(1, len(rd)) if rd[j] * rd[j-1] < 0 and abs(rd[j]) > 0.01 and abs(rd[j-1]) > 0.01)
    print(f"  Pitch reversals while running: {rrev}/{max(1,len(rd)-1)} ({rrev/max(1,len(rd)-1)*100:.1f}%)")

# =====================================================================
print("\n" + "=" * 75)
print("10. AUTOCORRELATION")
print("=" * 75)

for name, deltas in [("Yaw", yaw_deltas), ("Pitch", pitch_deltas)]:
    mean_d = sum(deltas) / len(deltas)
    var_d = sum((d - mean_d)**2 for d in deltas) / len(deltas)
    if var_d < 0.0001: print(f"  {name}: variance too low"); continue
    print(f"  {name} delta autocorrelation:")
    for lag in [1, 2, 3, 5, 10, 20]:
        if lag >= len(deltas): break
        cov = sum((deltas[i] - mean_d) * (deltas[i-lag] - mean_d) for i in range(lag, len(deltas))) / (len(deltas) - lag)
        acf = cov / var_d
        bar = "#" * int(abs(acf) * 40)
        sign = "+" if acf > 0 else "-"
        print(f"    lag={lag:2d}: {sign}{abs(acf):.3f} {bar}")

# =====================================================================
print("\n" + "=" * 75)
print("11. ATTENTION & FATIGUE & DWELL")
print("=" * 75)

attn = [r['attention'] for r in rows]
fat = [r['fatigue'] for r in rows]
sacc_f = sum(1 for r in rows if r['saccade'] == 1)
dwell_f = sum(1 for r in rows if r['dwell'] == 1)
fall_f = sum(1 for r in rows if r['falling'] == 1)
edge_f = sum(1 for r in rows if r['pre_edge'] == 1)
lift_f = sum(1 for r in rows if r['mouse_lift'] == 1)

print(f"Attention: mean={sum(attn)/N:.3f}  range=[{min(attn):.3f}, {max(attn):.3f}]")
print(f"Fatigue: start={fat[0]:.4f}  end={fat[-1]:.4f}")
print(f"Saccade: {sacc_f/N*100:.1f}%  Dwell: {dwell_f/N*100:.1f}%  Fall: {fall_f/N*100:.1f}%  Pre-edge: {edge_f/N*100:.1f}%  Mouse-lift: {lift_f/N*100:.1f}%")

# =====================================================================
print("\n" + "=" * 75)
print("12. ZERO-MOVEMENT & STILLNESS")
print("=" * 75)

zero_y = sum(1 for d in yaw_deltas if abs(d) < 0.00001)
zero_p = sum(1 for d in pitch_deltas if abs(d) < 0.00001)
zero_b = sum(1 for i in range(len(yaw_deltas)) if abs(yaw_deltas[i]) < 0.00001 and abs(pitch_deltas[i]) < 0.00001)
print(f"Zero yaw: {zero_y}/{len(yaw_deltas)} ({zero_y/len(yaw_deltas)*100:.1f}%)")
print(f"Zero pitch: {zero_p}/{len(pitch_deltas)} ({zero_p/len(pitch_deltas)*100:.1f}%)")
print(f"Zero both: {zero_b}/{len(yaw_deltas)} ({zero_b/len(yaw_deltas)*100:.1f}%)")

# Stillness streaks
streaks = []
cur = 0
for i in range(len(yaw_deltas)):
    if abs(yaw_deltas[i]) < 0.00001 and abs(pitch_deltas[i]) < 0.00001: cur += 1
    else:
        if cur > 0: streaks.append(cur)
        cur = 0
if cur > 0: streaks.append(cur)
if streaks:
    ss = sorted(streaks)
    print(f"Stillness streaks: count={len(streaks)}  mean={sum(streaks)/len(streaks):.1f}  p90={ss[int(len(ss)*0.9)]}  max={max(streaks)}")

# =====================================================================
print("\n" + "=" * 75)
print("13. SACCADE VELOCITY PROFILES")
print("=" * 75)

sacc_eps = []
in_s = False; ss2 = 0
for i in range(N):
    if rows[i]['saccade'] == 1 and not in_s: in_s = True; ss2 = i
    elif rows[i]['saccade'] == 0 and in_s: in_s = False; sacc_eps.append((ss2, i-1))
if in_s: sacc_eps.append((ss2, N-1))

print(f"Saccade count: {len(sacc_eps)}")
if sacc_eps:
    durations = [(rows[e]['time_ms']-rows[s]['time_ms']) for s,e in sacc_eps]
    ds = sorted(durations)
    print(f"Duration: mean={sum(ds)/len(ds):.0f}ms  med={ds[len(ds)//2]:.0f}ms  range={ds[0]:.0f}-{ds[-1]:.0f}ms")

    # Peak velocity tau distribution
    taus = []
    for ss3, se in sacc_eps:
        if se - ss3 < 5: continue
        vels = [math.sqrt(rows[i]['yaw_vel']**2 + rows[i]['pitch_vel']**2) for i in range(ss3, se+1)]
        peak_idx = vels.index(max(vels))
        tau = peak_idx / max(1, se - ss3)
        taus.append(tau)
    if taus:
        ts = sorted(taus)
        print(f"Peak vel tau: mean={sum(taus)/len(taus):.2f}  med={ts[len(ts)//2]:.2f}  (human: ~0.35-0.45)")

# =====================================================================
print("\n" + "=" * 75)
print("VERDICT")
print("=" * 75)

issues = []
warnings = []

# Quantization
for name, deltas in [("Yaw", yaw_deltas), ("Pitch", pitch_deltas)]:
    dirty = sum(1 for d in deltas if abs(d) >= 0.00001 and abs(abs(d)/step - round(abs(d)/step)) >= 0.01)
    subpx = sum(1 for d in deltas if 0.00001 < abs(d) < step * 0.5)
    if subpx > 0: issues.append(f"{name}: {subpx} sub-pixel deltas")
    if dirty / len(deltas) > 0.05: warnings.append(f"{name}: {dirty/len(deltas)*100:.1f}% off-grid")

# Pitch static
if running:
    rpd2 = [abs(rows[i]['pitch'] - rows[i-1]['pitch']) for i in running if i > 0]
    zp = sum(1 for d in rpd2 if d < 0.001)
    if zp / len(rpd2) > 0.70: warnings.append(f"Pitch static {zp/len(rpd2)*100:.0f}% while running")

# Alternating
for name, deltas in [("Yaw", yaw_deltas), ("Pitch", pitch_deltas)]:
    alt = sum(1 for i in range(1, len(deltas)) if abs(abs(deltas[i-1])-step)<0.005 and abs(abs(deltas[i])-step)<0.005 and deltas[i]*deltas[i-1]<0)
    if alt / len(deltas) > 0.03: warnings.append(f"{name} alternating 1px: {alt/len(deltas)*100:.1f}%")

# Tremor freq
if abs(yc/(2*duration_s) - 10) > 3: warnings.append(f"Tremor freq {yc/(2*duration_s):.1f}Hz off target")

# Fall reaction
for fs, fe in medium + long_f:
    dur = rows[fe]['time_ms'] - rows[fs]['time_ms']
    if dur < 300: continue
    base = rows[fs]['pitch']
    reacted = any(rows[i]['pitch'] > base + 1.0 for i in range(fs, fe+1))
    if not reacted: warnings.append(f"Fall {fs}-{fe} ({dur:.0f}ms): no pitch reaction")

for issue in issues:
    print(f"  [PROBLEM] {issue}")
for warning in warnings:
    print(f"  [WARNING] {warning}")
if not issues and not warnings:
    print("  ALL CHECKS PASSED!")
else:
    print(f"\n  {len(issues)} problems, {len(warnings)} warnings")
