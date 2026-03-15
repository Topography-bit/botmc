import csv
import math

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
print(f"FRAMES: {N}   DURATION: {duration_s:.1f}s")

# Pitch deltas
pitch_deltas = []
for i in range(1, N):
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    pitch_deltas.append(dp)

# ── 1. Pitch acceleration (jerkiness) ──
print("\n=== PITCH ACCELERATION ===")
pitch_accels = []
for i in range(1, N):
    dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
    if 0.0001 < dt_s < 0.1:
        pa = (rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s
        pitch_accels.append((i, pa))

if pitch_accels:
    abs_pa = sorted([abs(a) for _, a in pitch_accels])
    print(f"Pitch accel: mean={sum(abs_pa)/len(abs_pa):.0f}  p90={abs_pa[int(len(abs_pa)*0.9)]:.0f}  p95={abs_pa[int(len(abs_pa)*0.95)]:.0f}  p99={abs_pa[int(len(abs_pa)*0.99)]:.0f}  max={abs_pa[-1]:.0f} deg/s2")

    jerks_1k = sum(1 for a in abs_pa if a > 1000)
    jerks_2k = sum(1 for a in abs_pa if a > 2000)
    jerks_3k = sum(1 for a in abs_pa if a > 3000)
    print(f"Pitch jerks >1000: {jerks_1k} ({jerks_1k/len(abs_pa)*100:.1f}%)")
    print(f"Pitch jerks >2000: {jerks_2k} ({jerks_2k/len(abs_pa)*100:.1f}%)")
    print(f"Pitch jerks >3000: {jerks_3k} ({jerks_3k/len(abs_pa)*100:.1f}%)")

# ── 2. Pitch direction reversals ──
print("\n=== PITCH DIRECTION REVERSALS ===")
reversals = 0
reversal_frames = []
for i in range(1, len(pitch_deltas)):
    if pitch_deltas[i] * pitch_deltas[i-1] < 0 and abs(pitch_deltas[i]) > 0.01 and abs(pitch_deltas[i-1]) > 0.01:
        reversals += 1
        if len(reversal_frames) < 200:
            reversal_frames.append(i)

print(f"Pitch reversals: {reversals}/{N-2} ({reversals/(N-2)*100:.1f}%)")

# Same-direction run lengths
run_lens = []
cur = 1
for i in range(1, len(pitch_deltas)):
    if pitch_deltas[i] * pitch_deltas[i-1] > 0:
        cur += 1
    else:
        run_lens.append(cur)
        cur = 1
run_lens.append(cur)
if run_lens:
    rl = sorted(run_lens)
    print(f"Pitch same-dir runs: mean={sum(run_lens)/len(run_lens):.1f}  median={rl[len(rl)//2]}  max={max(run_lens)}")

# ── 3. Pitch delta histogram ──
print("\n=== PITCH DELTA SIZE DISTRIBUTION ===")
zero_p = sum(1 for d in pitch_deltas if abs(d) < 0.001)
tiny_p = sum(1 for d in pitch_deltas if 0.001 <= abs(d) < 0.1)
small_p = sum(1 for d in pitch_deltas if 0.1 <= abs(d) < 0.5)
med_p = sum(1 for d in pitch_deltas if 0.5 <= abs(d) < 2.0)
big_p = sum(1 for d in pitch_deltas if abs(d) >= 2.0)
total = len(pitch_deltas)
print(f"  |d| = 0:       {zero_p:6d} ({zero_p/total*100:.1f}%)")
print(f"  |d| < 0.1:     {tiny_p:6d} ({tiny_p/total*100:.1f}%)")
print(f"  |d| 0.1-0.5:   {small_p:6d} ({small_p/total*100:.1f}%)")
print(f"  |d| 0.5-2.0:   {med_p:6d} ({med_p/total*100:.1f}%)")
print(f"  |d| >= 2.0:    {big_p:6d} ({big_p/total*100:.1f}%)")

# ── 4. What's causing the jerkiness? Context around worst jerks ──
print("\n=== TOP 20 WORST PITCH JERKS (context) ===")
sorted_jerks = sorted(pitch_accels, key=lambda x: abs(x[1]), reverse=True)

for rank, (idx, accel) in enumerate(sorted_jerks[:20]):
    r = rows[idx]
    r_prev = rows[idx-1] if idx > 0 else r
    sacc = "SACC" if r['saccade'] == 1 else "spring"
    fall = " FALL" if r['falling'] == 1 else ""
    dwell = " DWELL" if r['dwell'] == 1 else ""
    pre_edge = " EDGE" if r['pre_edge'] == 1 else ""

    # Check if saccade state changed
    sacc_change = ""
    if idx > 0 and r['saccade'] != r_prev['saccade']:
        sacc_change = " *SACC_BOUNDARY*"

    dp = rows[idx]['pitch'] - rows[idx-1]['pitch'] if idx > 0 else 0
    print(f"  #{rank+1}: frame={idx} accel={accel:+.0f} deg/s2  pitch_vel={r['pitch_vel']:.1f}  dp={dp:.3f}  {sacc}{fall}{dwell}{pre_edge}{sacc_change}")

# ── 5. Head-bob analysis ──
print("\n=== HEAD-BOB EFFECT ===")
# During running (no saccade, no fall, on ground = low error)
running = [i for i in range(N) if rows[i]['saccade'] == 0 and rows[i]['falling'] == 0 and rows[i]['error'] < 10]
print(f"Running frames: {len(running)}")

if running:
    run_pitch_d = [abs(rows[i]['pitch'] - rows[i-1]['pitch']) for i in running if i > 0]
    zero_run = sum(1 for d in run_pitch_d if d < 0.001)
    print(f"Pitch static while running: {zero_run}/{len(run_pitch_d)} ({zero_run/len(run_pitch_d)*100:.1f}%)")
    print(f"Mean pitch delta while running: {sum(run_pitch_d)/len(run_pitch_d):.4f} deg")

    # Pitch velocity during running
    run_pv = [abs(rows[i]['pitch_vel']) for i in running]
    print(f"Pitch vel while running: mean={sum(run_pv)/len(run_pv):.2f}  max={max(run_pv):.2f} deg/s")

    # Direction reversals during running
    run_deltas = [rows[i]['pitch'] - rows[i-1]['pitch'] for i in running if i > 0]
    run_rev = 0
    for j in range(1, len(run_deltas)):
        if run_deltas[j] * run_deltas[j-1] < 0 and abs(run_deltas[j]) > 0.01 and abs(run_deltas[j-1]) > 0.01:
            run_rev += 1
    print(f"Pitch reversals while running: {run_rev}/{len(run_deltas)-1} ({run_rev/(len(run_deltas)-1)*100:.1f}%)")

# ── 6. Tremor pitch contribution ──
print("\n=== TREMOR PITCH ANALYSIS ===")
tremor_ps = [r['tremor_pitch'] for r in rows]
tp_abs = [abs(t) for t in tremor_ps]
print(f"Tremor pitch: mean_abs={sum(tp_abs)/N:.6f}  max={max(tp_abs):.6f}")

# Is tremor creating visible 1px alternating pattern?
# Find frames where pitch delta is exactly 1 step and alternates
sens_step = 0.0847  # from previous analysis
alternating = 0
for i in range(2, len(pitch_deltas)):
    d1 = pitch_deltas[i-1]
    d2 = pitch_deltas[i]
    # Alternating: one is +1px, next is -1px (or vice versa)
    if abs(abs(d1) - sens_step) < 0.005 and abs(abs(d2) - sens_step) < 0.005:
        if d1 * d2 < 0:  # opposite signs
            alternating += 1

print(f"Alternating 1px pitch pattern: {alternating} ({alternating/(N-2)*100:.2f}%)")
if alternating > N * 0.02:
    print("  [PROBLEM] Too many alternating 1px steps -- looks like quantization dithering jitter!")

# ── 7. Quantization check ──
print("\n=== QUANTIZATION GRID CHECK ===")
# Find best step
best_step = 0
best_match = 0
for sens_pct in range(1, 201):
    s = sens_pct / 200.0
    f = s * 0.6 + 0.2
    step = f * f * f * 8.0 * 0.15
    if step < 0.001: continue
    clean = 0
    for d in pitch_deltas:
        if abs(d) < 0.0001:
            clean += 1
            continue
        ratio = abs(d) / step
        residual = abs(ratio - round(ratio))
        if residual < 0.015:
            clean += 1
    pct = clean / total
    if pct > best_match:
        best_match = pct
        best_step = step

yaw_deltas = []
for i in range(1, N):
    dy = rows[i]['yaw'] - rows[i-1]['yaw']
    while dy > 180: dy -= 360
    while dy < -180: dy += 360
    yaw_deltas.append(dy)

clean_y = 0
dirty_y = 0
for d in yaw_deltas:
    if abs(d) < 0.0001:
        clean_y += 1
        continue
    ratio = abs(d) / best_step
    residual = abs(ratio - round(ratio))
    if residual < 0.015:
        clean_y += 1
    else:
        dirty_y += 1

clean_p = 0
dirty_p = 0
subpixel_p = 0
for d in pitch_deltas:
    if abs(d) < 0.0001:
        clean_p += 1
        continue
    if abs(d) < best_step * 0.5:
        subpixel_p += 1
    ratio = abs(d) / best_step
    residual = abs(ratio - round(ratio))
    if residual < 0.015:
        clean_p += 1
    else:
        dirty_p += 1

print(f"Step: {best_step:.6f} (match: {best_match*100:.1f}%)")
print(f"Yaw on grid: {clean_y}/{total} ({clean_y/total*100:.1f}%)  off: {dirty_y} ({dirty_y/total*100:.1f}%)")
print(f"Pitch on grid: {clean_p}/{total} ({clean_p/total*100:.1f}%)  off: {dirty_p} ({dirty_p/total*100:.1f}%)")
print(f"Sub-pixel pitch: {subpixel_p}")
