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
            try:
                row[k] = float(v)
            except:
                row[k] = v
        rows.append(row)

N = len(rows)
duration_s = (rows[-1]['time_ms'] - rows[0]['time_ms']) / 1000.0
fps = N / duration_s if duration_s > 0 else 0

print("=" * 75)
print(f"FRAMES: {N}   DURATION: {duration_s:.1f}s   FPS: {fps:.1f}")
print("=" * 75)

# =====================================================================
# 1. FALL CAMERA REACTION TIME
# =====================================================================
print("\n" + "=" * 75)
print("1. FALL CAMERA: REACTION TIME ANALYSIS")
print("=" * 75)

# Find fall episodes: falling=1 segments
fall_episodes = []
in_fall = False
fall_start = -1
for i in range(N):
    if rows[i]['falling'] == 1 and not in_fall:
        in_fall = True
        fall_start = i
    elif rows[i]['falling'] == 0 and in_fall:
        in_fall = False
        fall_episodes.append((fall_start, i - 1))
if in_fall:
    fall_episodes.append((fall_start, N - 1))

print(f"\nFall episodes: {len(fall_episodes)}")

for fi, (fs, fe) in enumerate(fall_episodes):
    dur_ms = rows[fe]['time_ms'] - rows[fs]['time_ms']

    # Find when pitch actually starts moving down (pitch increasing = looking down)
    base_pitch = rows[fs]['pitch']
    pitch_react_frame = -1
    for i in range(fs, fe + 1):
        # Pitch > base + 1 degree = reacting
        if rows[i]['pitch'] > base_pitch + 1.0:
            pitch_react_frame = i
            break

    if pitch_react_frame >= 0:
        react_ms = rows[pitch_react_frame]['time_ms'] - rows[fs]['time_ms']
        max_pitch = max(rows[i]['pitch'] for i in range(fs, fe + 1))
        min_pitch = min(rows[i]['pitch'] for i in range(fs, fe + 1))
        pitch_range = max_pitch - min_pitch

        # Check pre-edge before fall
        pre_edge_before = 0
        for i in range(max(0, fs - 60), fs):
            if rows[i]['pre_edge'] == 1:
                pre_edge_before += 1

        expected = "EXPECTED" if pre_edge_before > 10 else "SURPRISE"
        print(f"\n  Fall #{fi+1}: frames {fs}-{fe} ({dur_ms:.0f}ms)")
        print(f"    Type: {expected} (pre_edge frames before: {pre_edge_before})")
        print(f"    Reaction time: {react_ms:.0f}ms")
        print(f"    Base pitch: {base_pitch:.1f} -> max: {max_pitch:.1f} (range: {pitch_range:.1f})")
        if react_ms > 400:
            print(f"    [SLOW] Reaction > 400ms!")
    else:
        print(f"\n  Fall #{fi+1}: frames {fs}-{fe} ({dur_ms:.0f}ms)")
        print(f"    [NO REACTION] Pitch never moved down!")
        # Show what pitch did
        pitches = [rows[i]['pitch'] for i in range(fs, min(fe+1, fs+30))]
        print(f"    Pitch values: {['%.1f' % p for p in pitches[:10]]}")

# Also check: are there falls without falling=1 flag?
# (player actually falls but threshold not met)
print("\n  -- Velocity analysis around falls --")
for fi, (fs, fe) in enumerate(fall_episodes[:5]):
    print(f"\n  Fall #{fi+1} velocity profile (first 15 frames):")
    for i in range(max(0, fs-3), min(fe+1, fs+15)):
        pv = rows[i]['pitch_vel']
        pitch = rows[i]['pitch']
        falling = int(rows[i]['falling'])
        pre_edge = int(rows[i]['pre_edge'])
        print(f"    [{i}] t={rows[i]['time_ms']:.0f}ms pitch={pitch:.1f} pitch_vel={pv:.1f} falling={falling} pre_edge={pre_edge}")

# =====================================================================
# 2. MOUSE QUANTIZATION ANALYSIS
# =====================================================================
print("\n" + "=" * 75)
print("2. MOUSE QUANTIZATION (SENSITIVITY GRID)")
print("=" * 75)

yaw_deltas = []
pitch_deltas = []
for i in range(1, N):
    dy = rows[i]['yaw'] - rows[i-1]['yaw']
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    while dy > 180: dy -= 360
    while dy < -180: dy += 360
    yaw_deltas.append(dy)
    pitch_deltas.append(dp)

# Find the sensitivity step
nonzero_yaw = sorted(set(abs(round(d, 6)) for d in yaw_deltas if abs(d) > 0.0001))
nonzero_pitch = sorted(set(abs(round(d, 6)) for d in pitch_deltas if abs(d) > 0.0001))

print(f"\n10 smallest non-zero yaw deltas: {nonzero_yaw[:10]}")
print(f"10 smallest non-zero pitch deltas: {nonzero_pitch[:10]}")

# Try all sensitivities 1-200%
best_sens = -1
best_match = 0
best_step = 0
for sens_pct in range(1, 201):
    s = sens_pct / 200.0
    f = s * 0.6 + 0.2
    step = f * f * f * 8.0 * 0.15
    if step < 0.001: continue

    clean = 0
    total = 0
    for d in yaw_deltas:
        if abs(d) < 0.0001:
            clean += 1
            total += 1
            continue
        total += 1
        ratio = abs(d) / step
        residual = abs(ratio - round(ratio))
        if residual < 0.015:
            clean += 1

    pct = clean / total if total > 0 else 0
    if pct > best_match:
        best_match = pct
        best_sens = sens_pct
        best_step = step

print(f"\nBest sensitivity match: {best_sens}% (step={best_step:.6f} deg/px, grid match={best_match*100:.1f}%)")

step = best_step

# Detailed grid analysis
clean_yaw = 0
dirty_yaw = 0
residuals_yaw = []
for d in yaw_deltas:
    if abs(d) < 0.0001:
        clean_yaw += 1
        continue
    ratio = abs(d) / step
    residual = abs(ratio - round(ratio))
    residuals_yaw.append(residual)
    if residual < 0.015:
        clean_yaw += 1
    else:
        dirty_yaw += 1

print(f"Yaw on grid: {clean_yaw}/{N-1} ({clean_yaw/(N-1)*100:.1f}%)")
print(f"Yaw off grid: {dirty_yaw}/{N-1} ({dirty_yaw/(N-1)*100:.1f}%)")

clean_pitch = 0
dirty_pitch = 0
for d in pitch_deltas:
    if abs(d) < 0.0001:
        clean_pitch += 1
        continue
    ratio = abs(d) / step
    residual = abs(ratio - round(ratio))
    if residual < 0.015:
        clean_pitch += 1
    else:
        dirty_pitch += 1

print(f"Pitch on grid: {clean_pitch}/{N-1} ({clean_pitch/(N-1)*100:.1f}%)")
print(f"Pitch off grid: {dirty_pitch}/{N-1} ({dirty_pitch/(N-1)*100:.1f}%)")

# Sub-pixel deltas (smaller than 1 pixel step)
subpixel_yaw = sum(1 for d in yaw_deltas if 0.0001 < abs(d) < step * 0.5)
subpixel_pitch = sum(1 for d in pitch_deltas if 0.0001 < abs(d) < step * 0.5)
print(f"\nSub-pixel yaw deltas (<0.5 step): {subpixel_yaw} ({subpixel_yaw/(N-1)*100:.2f}%)")
print(f"Sub-pixel pitch deltas (<0.5 step): {subpixel_pitch} ({subpixel_pitch/(N-1)*100:.2f}%)")
if subpixel_yaw > 0 or subpixel_pitch > 0:
    print("  [PROBLEM] Sub-pixel deltas exist! Real mouse NEVER produces these.")

# Pixel count distribution
print("\n  Pixel counts per frame (yaw):")
pixel_counts = defaultdict(int)
for d in yaw_deltas:
    if abs(d) < 0.0001:
        pixel_counts[0] += 1
    else:
        px = round(abs(d) / step)
        pixel_counts[px] += 1

for px in sorted(pixel_counts.keys())[:20]:
    bar = "#" * min(60, pixel_counts[px] * 60 // max(pixel_counts.values()))
    print(f"    {px:3d}px: {pixel_counts[px]:5d} {bar}")

# =====================================================================
# 3. VELOCITY PROFILE ANALYSIS (human vs robotic)
# =====================================================================
print("\n" + "=" * 75)
print("3. VELOCITY PROFILE (SMOOTHNESS)")
print("=" * 75)

total_vels = [math.sqrt(rows[i]['yaw_vel']**2 + rows[i]['pitch_vel']**2) for i in range(N)]
tv_sorted = sorted(total_vels)

print(f"Velocity: mean={sum(total_vels)/N:.1f}  median={tv_sorted[N//2]:.1f}  p95={tv_sorted[int(N*0.95)]:.1f}  max={max(total_vels):.1f} deg/s")

# Velocity histogram
print("\n  Velocity distribution:")
bins = [0, 5, 15, 30, 60, 100, 200, 350, 500, 9999]
for bi in range(len(bins)-1):
    cnt = sum(1 for v in total_vels if bins[bi] <= v < bins[bi+1])
    pct = cnt / N * 100
    bar = "#" * min(50, int(pct))
    label = f"{bins[bi]}-{bins[bi+1]}" if bins[bi+1] < 9999 else f"{bins[bi]}+"
    print(f"    {label:>8s}: {pct:5.1f}% {bar}")

# Acceleration analysis
accels = []
for i in range(1, N):
    dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
    if 0.0001 < dt_s < 0.1:
        ya = (rows[i]['yaw_vel'] - rows[i-1]['yaw_vel']) / dt_s
        pa = (rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s
        accels.append(math.sqrt(ya*ya + pa*pa))

if accels:
    ac_sorted = sorted(accels)
    print(f"\nAcceleration: mean={sum(accels)/len(accels):.0f}  p95={ac_sorted[int(len(accels)*0.95)]:.0f}  p99={ac_sorted[int(len(accels)*0.99)]:.0f}  max={max(accels):.0f} deg/s2")

    jerk_2000 = sum(1 for a in accels if a > 2000)
    jerk_3000 = sum(1 for a in accels if a > 3000)
    jerk_5000 = sum(1 for a in accels if a > 5000)
    print(f"Jerks >2000: {jerk_2000} ({jerk_2000/len(accels)*100:.2f}%)")
    print(f"Jerks >3000: {jerk_3000} ({jerk_3000/len(accels)*100:.2f}%)")
    print(f"Jerks >5000: {jerk_5000} ({jerk_5000/len(accels)*100:.2f}%)")

# =====================================================================
# 4. DIRECTION REVERSALS (roboticness indicator)
# =====================================================================
print("\n" + "=" * 75)
print("4. DIRECTION REVERSALS & RUN LENGTHS")
print("=" * 75)

# Yaw reversals (rapid back-and-forth = robotic)
yaw_reversals = 0
for i in range(1, len(yaw_deltas)):
    if yaw_deltas[i] * yaw_deltas[i-1] < 0 and abs(yaw_deltas[i]) > 0.01 and abs(yaw_deltas[i-1]) > 0.01:
        yaw_reversals += 1

pitch_reversals = 0
for i in range(1, len(pitch_deltas)):
    if pitch_deltas[i] * pitch_deltas[i-1] < 0 and abs(pitch_deltas[i]) > 0.01 and abs(pitch_deltas[i-1]) > 0.01:
        pitch_reversals += 1

print(f"Yaw reversals: {yaw_reversals}/{N-2} ({yaw_reversals/(N-2)*100:.1f}%)")
print(f"Pitch reversals: {pitch_reversals}/{N-2} ({pitch_reversals/(N-2)*100:.1f}%)")
print("  (Real mouse: 15-30% yaw reversals during aiming, <10% while running)")

# Same-direction run lengths
run_lengths_yaw = []
cur_run = 1
for i in range(1, len(yaw_deltas)):
    if yaw_deltas[i] * yaw_deltas[i-1] > 0:
        cur_run += 1
    else:
        if cur_run > 0:
            run_lengths_yaw.append(cur_run)
        cur_run = 1
run_lengths_yaw.append(cur_run)

if run_lengths_yaw:
    rl_sorted = sorted(run_lengths_yaw)
    print(f"\nYaw same-direction runs: mean={sum(run_lengths_yaw)/len(run_lengths_yaw):.1f}  median={rl_sorted[len(rl_sorted)//2]}  max={max(run_lengths_yaw)}")
    print("  (Real mouse: mean 3-8, humans hold direction for short bursts)")

# =====================================================================
# 5. TREMOR ANALYSIS (frequency, amplitude, naturalness)
# =====================================================================
print("\n" + "=" * 75)
print("5. TREMOR ANALYSIS")
print("=" * 75)

tremor_yaws = [r['tremor_yaw'] for r in rows]
tremor_pitches = [r['tremor_pitch'] for r in rows]

# Amplitude
ty_abs = [abs(t) for t in tremor_yaws]
tp_abs = [abs(t) for t in tremor_pitches]
print(f"Tremor yaw:   mean_abs={sum(ty_abs)/N:.6f}  max={max(ty_abs):.6f} deg")
print(f"Tremor pitch: mean_abs={sum(tp_abs)/N:.6f}  max={max(tp_abs):.6f} deg")

# Zero-crossing frequency estimate
yaw_crossings = sum(1 for i in range(1, N) if tremor_yaws[i] * tremor_yaws[i-1] < 0)
pitch_crossings = sum(1 for i in range(1, N) if tremor_pitches[i] * tremor_pitches[i-1] < 0)
yaw_freq = yaw_crossings / (2 * duration_s)
pitch_freq = pitch_crossings / (2 * duration_s)
print(f"\nEstimated frequency: yaw={yaw_freq:.1f}Hz  pitch={pitch_freq:.1f}Hz")
print("  (Real physiological tremor: 8-12Hz)")

# Is tremor visible in actual movement? (after quantization)
# Count frames where tremor is the ONLY source of movement
tremor_only_frames = 0
for i in range(1, N):
    dy = abs(yaw_deltas[i-1])
    dp = abs(pitch_deltas[i-1])
    # Small movement that could be tremor
    if 0.0001 < dy < step * 2 and rows[i]['saccade'] == 0:
        tremor_only_frames += 1

print(f"Frames with tremor-scale movement (1-2px, no saccade): {tremor_only_frames} ({tremor_only_frames/(N-1)*100:.1f}%)")

# =====================================================================
# 6. SACCADE ANALYSIS (velocity profiles, timing)
# =====================================================================
print("\n" + "=" * 75)
print("6. SACCADE ANALYSIS")
print("=" * 75)

# Find saccade episodes
saccade_eps = []
in_sacc = False
sacc_start = -1
for i in range(N):
    if rows[i]['saccade'] == 1 and not in_sacc:
        in_sacc = True
        sacc_start = i
    elif rows[i]['saccade'] == 0 and in_sacc:
        in_sacc = False
        saccade_eps.append((sacc_start, i - 1))
if in_sacc:
    saccade_eps.append((sacc_start, N - 1))

print(f"Saccade count: {len(saccade_eps)}")

if saccade_eps:
    sacc_durations = []
    sacc_distances = []
    sacc_peak_vels = []

    for ss, se in saccade_eps:
        dur = rows[se]['time_ms'] - rows[ss]['time_ms']
        sacc_durations.append(dur)

        # Total angle traversed
        total_yaw = 0
        total_pitch = 0
        for i in range(ss, se):
            dy = rows[i+1]['yaw'] - rows[i]['yaw']
            while dy > 180: dy -= 360
            while dy < -180: dy += 360
            total_yaw += dy
            total_pitch += rows[i+1]['pitch'] - rows[i]['pitch']
        dist = math.sqrt(total_yaw**2 + total_pitch**2)
        sacc_distances.append(dist)

        peak = max(math.sqrt(rows[i]['yaw_vel']**2 + rows[i]['pitch_vel']**2) for i in range(ss, se+1))
        sacc_peak_vels.append(peak)

    sd = sorted(sacc_durations)
    print(f"Duration: mean={sum(sd)/len(sd):.0f}ms  median={sd[len(sd)//2]:.0f}ms  range={sd[0]:.0f}-{sd[-1]:.0f}ms")

    sdi = sorted(sacc_distances)
    print(f"Distance: mean={sum(sdi)/len(sdi):.1f}deg  median={sdi[len(sdi)//2]:.1f}deg  range={sdi[0]:.1f}-{sdi[-1]:.1f}deg")

    spv = sorted(sacc_peak_vels)
    print(f"Peak vel: mean={sum(spv)/len(spv):.0f}  median={spv[len(spv)//2]:.0f}  max={spv[-1]:.0f} deg/s")

    # Check velocity profile shape: should be bell-shaped (peak at ~40%)
    print("\n  Velocity profile shape check (first 5 saccades > 30deg):")
    checked = 0
    for ss, se in saccade_eps:
        if se - ss < 10: continue
        total_yaw = 0
        for i in range(ss, se):
            dy = rows[i+1]['yaw'] - rows[i]['yaw']
            while dy > 180: dy -= 360
            while dy < -180: dy += 360
            total_yaw += dy
        if abs(total_yaw) < 30: continue

        vels = [math.sqrt(rows[i]['yaw_vel']**2 + rows[i]['pitch_vel']**2) for i in range(ss, se+1)]
        peak_idx = vels.index(max(vels))
        peak_tau = peak_idx / (se - ss) if se > ss else 0

        # Is it bell-shaped? Check symmetry
        first_half_mean = sum(vels[:len(vels)//2]) / max(1, len(vels)//2)
        second_half_mean = sum(vels[len(vels)//2:]) / max(1, len(vels) - len(vels)//2)

        print(f"    Saccade {ss}-{se}: dist={abs(total_yaw):.0f}deg  peak@tau={peak_tau:.2f}  peak={max(vels):.0f}deg/s")
        print(f"      1st half avg={first_half_mean:.0f}  2nd half avg={second_half_mean:.0f}  {'BELL' if 0.25 < peak_tau < 0.65 else 'NOT BELL'}")

        checked += 1
        if checked >= 5: break

# =====================================================================
# 7. BREATHING ANALYSIS
# =====================================================================
print("\n" + "=" * 75)
print("7. BREATHING RHYTHM")
print("=" * 75)

breaths = [r['breath_offset'] for r in rows]
print(f"Breath offset: range={max(breaths)-min(breaths):.4f}  mean={sum(breaths)/N:.4f}")

# Zero-crossing frequency
breath_cross = sum(1 for i in range(1, N) if (breaths[i] - sum(breaths)/N) * (breaths[i-1] - sum(breaths)/N) < 0)
breath_freq = breath_cross / (2 * duration_s)
print(f"Estimated breath freq: {breath_freq:.2f}Hz (target: ~0.25Hz)")

# =====================================================================
# 8. PITCH WHILE RUNNING (vertical movement naturalness)
# =====================================================================
print("\n" + "=" * 75)
print("8. PITCH MOVEMENT WHILE RUNNING")
print("=" * 75)

running = [i for i in range(N) if rows[i]['saccade'] == 0 and rows[i]['falling'] == 0 and rows[i]['error'] < 10]
print(f"'Running' frames: {len(running)} ({len(running)/N*100:.1f}%)")

if running:
    run_pitch_d = [abs(pitch_deltas[i-1]) for i in running if i > 0]
    if run_pitch_d:
        zero_p = sum(1 for d in run_pitch_d if d < 0.001)
        print(f"  Pitch static: {zero_p}/{len(run_pitch_d)} ({zero_p/len(run_pitch_d)*100:.1f}%)")
        print(f"  Mean pitch delta: {sum(run_pitch_d)/len(run_pitch_d):.4f} deg")

        if zero_p / len(run_pitch_d) > 0.6:
            print("  [PROBLEM] Pitch too static! Real players have constant micro-movement.")

# =====================================================================
# 9. DWELL & ATTENTION
# =====================================================================
print("\n" + "=" * 75)
print("9. DWELL & ATTENTION")
print("=" * 75)

dwell_frames = sum(1 for r in rows if r['dwell'] == 1)
saccade_frames = sum(1 for r in rows if r['saccade'] == 1)
fall_frames = sum(1 for r in rows if r['falling'] == 1)
pre_edge_frames = sum(1 for r in rows if r['pre_edge'] == 1)

print(f"Saccade: {saccade_frames/N*100:.1f}%  Dwell: {dwell_frames/N*100:.1f}%  Fall: {fall_frames/N*100:.1f}%  Pre-edge: {pre_edge_frames/N*100:.1f}%")

attns = [r['attention'] for r in rows]
print(f"Attention: mean={sum(attns)/N:.3f}  range={min(attns):.3f}-{max(attns):.3f}")

fatigues = [r['fatigue'] for r in rows]
print(f"Fatigue: start={fatigues[0]:.4f}  end={fatigues[-1]:.4f}")

# =====================================================================
# 10. FRAME TIMING CONSISTENCY
# =====================================================================
print("\n" + "=" * 75)
print("10. FRAME TIMING")
print("=" * 75)

dts = [(rows[i]['time_ms'] - rows[i-1]['time_ms']) for i in range(1, N)]
dt_sorted = sorted(dts)
print(f"Frame dt: mean={sum(dts)/len(dts):.2f}ms  median={dt_sorted[len(dts)//2]:.2f}ms  min={dts[0]:.2f}ms  max={max(dts):.2f}ms")
print(f"  p1={dt_sorted[int(len(dts)*0.01)]:.2f}ms  p99={dt_sorted[int(len(dts)*0.99)]:.2f}ms")

# =====================================================================
# 11. ZERO-MOVEMENT FRAMES (suspiciously still)
# =====================================================================
print("\n" + "=" * 75)
print("11. ZERO-MOVEMENT ANALYSIS")
print("=" * 75)

zero_both = sum(1 for i in range(len(yaw_deltas)) if abs(yaw_deltas[i]) < 0.0001 and abs(pitch_deltas[i]) < 0.0001)
zero_yaw = sum(1 for d in yaw_deltas if abs(d) < 0.0001)
zero_pitch = sum(1 for d in pitch_deltas if abs(d) < 0.0001)

print(f"Zero yaw: {zero_yaw}/{N-1} ({zero_yaw/(N-1)*100:.1f}%)")
print(f"Zero pitch: {zero_pitch}/{N-1} ({zero_pitch/(N-1)*100:.1f}%)")
print(f"Zero BOTH: {zero_both}/{N-1} ({zero_both/(N-1)*100:.1f}%)")
print("  (Real mouse: typically 5-20% zero frames depending on activity)")

# Consecutive zero-movement streaks
max_streak = 0
cur_streak = 0
streaks = []
for i in range(len(yaw_deltas)):
    if abs(yaw_deltas[i]) < 0.0001 and abs(pitch_deltas[i]) < 0.0001:
        cur_streak += 1
    else:
        if cur_streak > 0:
            streaks.append(cur_streak)
        cur_streak = 0
if cur_streak > 0:
    streaks.append(cur_streak)

if streaks:
    s_sorted = sorted(streaks)
    print(f"Zero-movement streaks: count={len(streaks)}  mean={sum(streaks)/len(streaks):.1f}  max={max(streaks)} frames")
    long_streaks = sum(1 for s in streaks if s > 10)
    print(f"  Streaks >10 frames: {long_streaks}")
    if max(streaks) > 30:
        max_streak_ms = max(streaks) * (sum(dts)/len(dts))
        print(f"  [WARNING] Longest still period: {max(streaks)} frames ({max_streak_ms:.0f}ms) -- may look suspicious")

# =====================================================================
# 12. AUTOCORRELATION (robotic periodicity check)
# =====================================================================
print("\n" + "=" * 75)
print("12. AUTOCORRELATION (PERIODICITY CHECK)")
print("=" * 75)

# Check if yaw deltas have suspicious periodicity
# Compute autocorrelation at various lags
mean_yd = sum(yaw_deltas) / len(yaw_deltas)
var_yd = sum((d - mean_yd)**2 for d in yaw_deltas) / len(yaw_deltas)

if var_yd > 0.0001:
    print("  Yaw delta autocorrelation:")
    for lag in [1, 2, 3, 5, 10, 20, 50]:
        if lag >= len(yaw_deltas): break
        cov = sum((yaw_deltas[i] - mean_yd) * (yaw_deltas[i-lag] - mean_yd)
                   for i in range(lag, len(yaw_deltas))) / (len(yaw_deltas) - lag)
        acf = cov / var_yd
        bar = "#" * int(abs(acf) * 40)
        sign = "+" if acf > 0 else "-"
        print(f"    lag={lag:3d}: {sign}{abs(acf):.3f} {bar}")
    print("  (Real mouse: lag-1 ~0.7-0.9 positive, drops off; robotic = high at all lags)")

# =====================================================================
# VERDICT
# =====================================================================
print("\n" + "=" * 75)
print("VERDICT")
print("=" * 75)

issues = []
warnings = []

# Quantization
if subpixel_yaw > 0 or subpixel_pitch > 0:
    issues.append(f"Sub-pixel deltas found: {subpixel_yaw} yaw, {subpixel_pitch} pitch -- impossible with real mouse")

if dirty_yaw / (N-1) > 0.03:
    issues.append(f"Off-grid yaw: {dirty_yaw/(N-1)*100:.1f}% -- quantization broken")

# Fall reaction
for fi, (fs, fe) in enumerate(fall_episodes):
    base_pitch = rows[fs]['pitch']
    reacted = False
    for i in range(fs, fe + 1):
        if rows[i]['pitch'] > base_pitch + 1.0:
            react_ms = rows[i]['time_ms'] - rows[fs]['time_ms']
            if react_ms > 500:
                warnings.append(f"Fall #{fi+1}: slow reaction ({react_ms:.0f}ms)")
            reacted = True
            break
    if not reacted:
        dur_ms = rows[fe]['time_ms'] - rows[fs]['time_ms']
        if dur_ms > 300:
            issues.append(f"Fall #{fi+1}: NO pitch reaction during {dur_ms:.0f}ms fall")

# Tremor freq
if abs(yaw_freq - 10.0) > 3.0:
    warnings.append(f"Tremor freq {yaw_freq:.1f}Hz (expected ~10Hz)")

# Pitch static
if running:
    run_pitch_d2 = [abs(pitch_deltas[i-1]) for i in running if i > 0]
    if run_pitch_d2:
        zp = sum(1 for d in run_pitch_d2 if d < 0.001)
        if zp / len(run_pitch_d2) > 0.6:
            warnings.append(f"Pitch static {zp/len(run_pitch_d2)*100:.0f}% while running")

# Reversals too high
if yaw_reversals / (N-2) > 0.35:
    warnings.append(f"Yaw reversals {yaw_reversals/(N-2)*100:.0f}% -- may look jittery")

# Zero movement
if zero_both / (N-1) > 0.40:
    warnings.append(f"Camera completely still {zero_both/(N-1)*100:.0f}% of frames")

for issue in issues:
    print(f"  [PROBLEM] {issue}")
for warning in warnings:
    print(f"  [WARNING] {warning}")
if not issues and not warnings:
    print("  All checks passed!")
else:
    print(f"\n  {len(issues)} problems, {len(warnings)} warnings")
