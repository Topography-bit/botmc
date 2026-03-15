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

print("=" * 70)
print(f"FINAL CAMERA ANALYSIS -- {N} frames, {duration_s:.1f}s ({duration_s/60:.1f}min)")
print("=" * 70)

# ════════════════════════════════════════════════════════════════
# 1. MOUSE QUANTIZATION -- DEEP CHECK
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("1. MOUSE QUANTIZATION -- DEEP CHECK")
print("=" * 70)

yaw_deltas = []
pitch_deltas = []
for i in range(1, N):
    dy = rows[i]['yaw'] - rows[i-1]['yaw']
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    while dy > 180: dy -= 360
    while dy < -180: dy += 360
    yaw_deltas.append(dy)
    pitch_deltas.append(dp)

# Expected steps for common sensitivities
sens_steps = {}
for sens_pct in [25, 50, 63, 75, 80, 100, 110, 120, 130, 150, 175, 200]:
    s = sens_pct / 200.0
    f = s * 0.6 + 0.2
    step = f * f * f * 8.0 * 0.15
    sens_steps[sens_pct] = step

# Collect all non-zero absolute deltas
nonzero_yaw = [abs(d) for d in yaw_deltas if abs(d) > 1e-6]
nonzero_pitch = [abs(d) for d in pitch_deltas if abs(d) > 1e-6]

print(f"Non-zero yaw deltas: {len(nonzero_yaw)}/{len(yaw_deltas)}")
print(f"Non-zero pitch deltas: {len(nonzero_pitch)}/{len(pitch_deltas)}")

if nonzero_yaw:
    ny_sorted = sorted(nonzero_yaw)
    print(f"\nSmallest 15 yaw deltas:")
    for d in ny_sorted[:15]:
        print(f"  {d:.8f}")

    print(f"\nLargest 5 yaw deltas:")
    for d in ny_sorted[-5:]:
        print(f"  {d:.4f}")

# Try each sensitivity -- check what % of deltas are exact multiples
print(f"\nGrid alignment test per sensitivity:")
best_sens = None
best_score = 0
for sens_pct, step in sorted(sens_steps.items()):
    on_grid = 0
    total_tested = 0
    for d in nonzero_yaw:
        ratio = d / step
        residual = abs(ratio - round(ratio))
        total_tested += 1
        if residual < 0.005:  # within 0.5% of integer multiple
            on_grid += 1
    pct = on_grid / total_tested * 100 if total_tested > 0 else 0
    marker = ""
    if pct > best_score:
        best_score = pct
        best_sens = sens_pct
        marker = " <-- BEST"
    print(f"  Sens {sens_pct:3d}% (step={step:.6f}): {pct:5.1f}% on-grid ({on_grid}/{total_tested}){marker}")

if best_sens:
    step = sens_steps[best_sens]
    print(f"\nBest match: {best_sens}% sensitivity, step={step:.6f} deg/pixel")

    # Distribution of pixel counts
    pixel_counts = defaultdict(int)
    for d in nonzero_yaw:
        pixels = round(d / step)
        pixel_counts[pixels] += 1
    print(f"Pixel count distribution (yaw):")
    for px in sorted(pixel_counts.keys())[:20]:
        cnt = pixel_counts[px]
        pct = cnt / len(nonzero_yaw) * 100
        bar = '#' * int(pct)
        print(f"  {px:4d}px: {cnt:5d} ({pct:5.1f}%) {bar}")

    # Check for impossible sub-pixel deltas
    sub_pixel = [d for d in nonzero_yaw if d < step * 0.8]
    print(f"\nSub-pixel deltas (< 0.8 x step): {len(sub_pixel)} ({len(sub_pixel)/len(nonzero_yaw)*100:.2f}%)")
    if sub_pixel:
        print(f"  Examples: {[f'{d:.8f}' for d in sorted(sub_pixel)[:5]]}")

# Check if float precision is an issue
print(f"\nFloat precision check:")
# In Java, player angles are float (32-bit). Check if deltas show float artifacts
float_artifacts = 0
for d in nonzero_yaw[:1000]:
    # Float can represent ~7 significant digits
    # Check if delta has suspicious precision
    s = f"{d:.10f}"
    # Count trailing non-zero digits after decimal
    pass
print(f"  Yaw values range: {min(r['yaw'] for r in rows):.4f} to {max(r['yaw'] for r in rows):.4f}")
print(f"  At yaw~{max(abs(r['yaw']) for r in rows):.0f}, float precision ~{max(abs(r['yaw']) for r in rows) * 2**-23:.6f} deg")

# ════════════════════════════════════════════════════════════════
# 2. FALLING CAMERA -- DETAILED EPISODE ANALYSIS
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("2. FALLING CAMERA -- DETAILED EPISODE ANALYSIS")
print("=" * 70)

# Find fall episodes
falls = []
in_fall = False
fall_start = 0
for i, r in enumerate(rows):
    if r['falling'] == 1 and not in_fall:
        in_fall = True
        fall_start = i
    elif r['falling'] == 0 and in_fall:
        in_fall = False
        falls.append((fall_start, i - 1))

print(f"Fall episodes: {len(falls)}")
for j, (start, end) in enumerate(falls):
    dur_ms = rows[end]['time_ms'] - rows[start]['time_ms']
    pitches = [rows[i]['pitch'] for i in range(start, end + 1)]
    pitch_at_start = rows[start]['pitch']
    pitch_min = min(pitches)
    pitch_max = max(pitches)
    pitch_at_end = rows[end]['pitch']

    # Check if pitch actually moved down during fall
    pitch_moved_down = pitch_max - pitch_at_start

    # Check pre-edge before this fall
    pre_edge_before = False
    pre_edge_max_str = 0
    scan_start = max(0, start - 200)  # look 200 frames back
    for i in range(scan_start, start):
        if rows[i].get('pre_edge', 0) == 1:
            pre_edge_before = True
            pe_str = rows[i].get('pre_edge_strength', 0)
            if pe_str > pre_edge_max_str:
                pre_edge_max_str = pe_str

    print(f"\n  Fall {j+1}: {dur_ms:.0f}ms ({end-start} frames)")
    print(f"    Pitch: start={pitch_at_start:.1f}  max_down={pitch_max:.1f}  end={pitch_at_end:.1f}")
    print(f"    Pitch moved DOWN: {pitch_moved_down:.1f} deg {'(GOOD)' if pitch_moved_down > 5 else '(PROBLEM: barely moved)' if pitch_moved_down > 1 else '(PROBLEM: NO downward look!)'}")
    print(f"    Pre-edge before: {'YES (str=' + f'{pre_edge_max_str:.2f})' if pre_edge_before else 'NO'}")

# ════════════════════════════════════════════════════════════════
# 3. PRE-EDGE PEEK ANALYSIS
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("3. PRE-EDGE PEEK ANALYSIS")
print("=" * 70)

pre_edge_frames = sum(1 for r in rows if r.get('pre_edge', 0) == 1)
print(f"Pre-edge active frames: {pre_edge_frames} ({pre_edge_frames/N*100:.1f}%)")

# Find pre-edge episodes
pe_episodes = []
in_pe = False
pe_start = 0
for i, r in enumerate(rows):
    if r.get('pre_edge', 0) == 1 and not in_pe:
        in_pe = True
        pe_start = i
    elif r.get('pre_edge', 0) == 0 and in_pe:
        in_pe = False
        pe_episodes.append((pe_start, i - 1))

print(f"Pre-edge episodes: {len(pe_episodes)}")
for j, (start, end) in enumerate(pe_episodes):
    dur_ms = rows[end]['time_ms'] - rows[start]['time_ms']
    strengths = [rows[i].get('pre_edge_strength', 0) for i in range(start, end + 1)]
    dists = [rows[i].get('pre_edge_dist', -1) for i in range(start, end + 1)]
    valid_dists = [d for d in dists if d >= 0]
    pitch_start = rows[start]['pitch']
    pitch_end = rows[end]['pitch']
    print(f"  Episode {j+1}: {dur_ms:.0f}ms  str={max(strengths):.2f}  dist={min(valid_dists):.1f}-{max(valid_dists):.1f}bl  pitch {pitch_start:.1f}->{pitch_end:.1f}")

# ════════════════════════════════════════════════════════════════
# 4. PITCH MOVEMENT WHILE RUNNING
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("4. PITCH MOVEMENT WHILE RUNNING")
print("=" * 70)

running_frames = []
for i in range(N):
    if rows[i]['saccade'] == 0 and rows[i]['falling'] == 0 and rows[i]['error'] < 10:
        running_frames.append(i)

print(f"Running frames: {len(running_frames)} ({len(running_frames)/N*100:.1f}%)")
if running_frames:
    rp_deltas = [abs(rows[idx]['pitch'] - rows[idx-1]['pitch']) for idx in running_frames if idx > 0]
    if rp_deltas:
        zero = sum(1 for d in rp_deltas if d < 0.001)
        tiny = sum(1 for d in rp_deltas if 0.001 <= d < 0.05)
        small = sum(1 for d in rp_deltas if 0.05 <= d < 0.5)
        med = sum(1 for d in rp_deltas if 0.5 <= d < 2.0)
        big = sum(1 for d in rp_deltas if d >= 2.0)
        total = len(rp_deltas)
        print(f"  Zero (<0.001):    {zero:5d} ({zero/total*100:.1f}%)")
        print(f"  Tiny (0.001-0.05):{tiny:5d} ({tiny/total*100:.1f}%)")
        print(f"  Small (0.05-0.5): {small:5d} ({small/total*100:.1f}%)")
        print(f"  Medium (0.5-2.0): {med:5d} ({med/total*100:.1f}%)")
        print(f"  Big (>2.0):       {big:5d} ({big/total*100:.1f}%)")
        print(f"  Mean: {sum(rp_deltas)/total:.4f} deg")

# ════════════════════════════════════════════════════════════════
# 5. JERKINESS
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("5. JERKINESS / SMOOTHNESS")
print("=" * 70)

total_vels = [math.sqrt(r['yaw_vel']**2 + r['pitch_vel']**2) for r in rows]
accels = []
for i in range(1, N):
    dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
    if 0.0001 < dt_s < 0.1:
        ya = (rows[i]['yaw_vel'] - rows[i-1]['yaw_vel']) / dt_s
        pa = (rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s
        accels.append(math.sqrt(ya*ya + pa*pa))

if accels:
    as_sorted = sorted(accels)
    print(f"Acceleration: mean={sum(accels)/len(accels):.0f}  median={as_sorted[len(accels)//2]:.0f}")
    print(f"  p95={as_sorted[int(len(accels)*0.95)]:.0f}  p99={as_sorted[int(len(accels)*0.99)]:.0f}  max={max(accels):.0f} deg/s2")

    jerks_3k = sum(1 for a in accels if a > 3000)
    jerks_5k = sum(1 for a in accels if a > 5000)
    jerks_10k = sum(1 for a in accels if a > 10000)
    print(f"  >3000: {jerks_3k} ({jerks_3k/len(accels)*100:.1f}%)")
    print(f"  >5000: {jerks_5k} ({jerks_5k/len(accels)*100:.1f}%)")
    print(f"  >10000: {jerks_10k} ({jerks_10k/len(accels)*100:.1f}%)")

# Direction reversals
yaw_reversals = sum(1 for i in range(1, len(yaw_deltas))
                    if yaw_deltas[i] * yaw_deltas[i-1] < 0
                    and abs(yaw_deltas[i]) > 0.01 and abs(yaw_deltas[i-1]) > 0.01)
print(f"\nYaw reversals: {yaw_reversals} ({yaw_reversals/(N-2)*100:.1f}%)")

# ════════════════════════════════════════════════════════════════
# 6. VELOCITY PROFILE
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("6. VELOCITY PROFILE")
print("=" * 70)
print(f"Total vel: mean={sum(total_vels)/N:.1f}  max={max(total_vels):.1f}")
print(f"Yaw vel: max={max(r['yaw_vel'] for r in rows):.1f}  min={min(r['yaw_vel'] for r in rows):.1f}")

# Check for hard cap artifacts -- flat velocity segments
flat_count = 0
for i in range(5, N):
    vels = [total_vels[j] for j in range(i-5, i)]
    if all(v > 400 for v in vels):
        diffs = [abs(vels[j] - vels[j-1]) for j in range(1, len(vels))]
        if max(diffs) < 1.0:
            flat_count += 1
print(f"Flat high-velocity segments (>400, 5-frame window): {flat_count}")

vel_buckets = defaultdict(int)
for v in total_vels:
    bucket = int(v / 50) * 50
    vel_buckets[bucket] += 1
print("\nVelocity distribution:")
for b in sorted(vel_buckets.keys()):
    pct = vel_buckets[b] / N * 100
    bar = '#' * int(pct / 2)
    print(f"  {b:4d}-{b+50:4d}: {vel_buckets[b]:5d} ({pct:5.1f}%) {bar}")

# ════════════════════════════════════════════════════════════════
# 7. TREMOR + BREATHING + ATTENTION
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("7. TREMOR / BREATHING / ATTENTION / FATIGUE")
print("=" * 70)

tremor_yaws = [r['tremor_yaw'] for r in rows]
tremor_pitches = [r['tremor_pitch'] for r in rows]
print(f"Tremor yaw: mean_abs={sum(abs(t) for t in tremor_yaws)/N:.6f}  max={max(abs(t) for t in tremor_yaws):.6f}")
print(f"Tremor pitch: mean_abs={sum(abs(t) for t in tremor_pitches)/N:.6f}  max={max(abs(t) for t in tremor_pitches):.6f}")

yaw_crossings = sum(1 for i in range(1, N) if tremor_yaws[i] * tremor_yaws[i-1] < 0)
yaw_freq = yaw_crossings / (2 * duration_s)
print(f"Tremor yaw freq: {yaw_freq:.1f}Hz (target ~10Hz)")

breaths = [r['breath_offset'] for r in rows]
breath_crossings = sum(1 for i in range(1, N) if breaths[i] * breaths[i-1] < 0)
breath_freq = breath_crossings / (2 * duration_s)
print(f"Breathing freq: {breath_freq:.3f}Hz (target ~0.25Hz)")

attns = [r['attention'] for r in rows]
print(f"Attention: {min(attns):.3f} - {max(attns):.3f} (target 0.78-1.22)")

fatigues = [r['fatigue'] for r in rows]
print(f"Fatigue: {fatigues[0]:.4f} -> {fatigues[-1]:.4f}")

# ════════════════════════════════════════════════════════════════
# 8. SACCADE ANALYSIS
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("8. SACCADE SUMMARY")
print("=" * 70)
saccade_frames = sum(1 for r in rows if r['saccade'] == 1)
saccades = []
in_sacc = False
sacc_start = 0
for i, r in enumerate(rows):
    if r['saccade'] == 1 and not in_sacc:
        in_sacc = True
        sacc_start = i
    elif r['saccade'] == 0 and in_sacc:
        in_sacc = False
        dur = rows[i-1]['time_ms'] - rows[sacc_start]['time_ms']
        sy = rows[sacc_start]['yaw']
        ey = rows[i-1]['yaw']
        sp = rows[sacc_start]['pitch']
        ep = rows[i-1]['pitch']
        dist = math.sqrt((ey-sy)**2 + (ep-sp)**2)
        saccades.append({'dur': dur, 'dist': dist})

print(f"Saccades: {len(saccades)} ({saccade_frames/N*100:.1f}% of frames)")
if saccades:
    durs = [s['dur'] for s in saccades]
    dists = [s['dist'] for s in saccades]
    print(f"  Duration: {min(durs):.0f} - {max(durs):.0f}ms (mean {sum(durs)/len(durs):.0f})")
    print(f"  Distance: {min(dists):.1f} - {max(dists):.1f}deg (mean {sum(dists)/len(dists):.1f})")

# ════════════════════════════════════════════════════════════════
# 9. FINAL HUMANNESS VERDICT
# ════════════════════════════════════════════════════════════════
print("\n" + "=" * 70)
print("9. FINAL HUMANNESS VERDICT")
print("=" * 70)

score = 100
problems = []
warnings = []
good = []

# Quantization
if nonzero_yaw:
    if best_score > 95:
        good.append(f"Mouse quantization: {best_score:.1f}% on-grid at {best_sens}% sens")
    elif best_score > 80:
        warnings.append(f"Quantization partial: {best_score:.1f}% on-grid")
        score -= 10
    else:
        problems.append(f"Quantization broken: only {best_score:.1f}% on-grid")
        score -= 25

# Tremor freq
if abs(yaw_freq - 10.0) < 2.0:
    good.append(f"Tremor frequency: {yaw_freq:.1f}Hz")
elif abs(yaw_freq - 10.0) < 4.0:
    warnings.append(f"Tremor freq slightly off: {yaw_freq:.1f}Hz")
    score -= 5
else:
    problems.append(f"Tremor freq wrong: {yaw_freq:.1f}Hz")
    score -= 15

# Tremor amplitude
mean_trem = sum(abs(t) for t in tremor_yaws) / N
if 0.02 < mean_trem < 0.2:
    good.append(f"Tremor amplitude: {mean_trem:.4f} deg")
elif mean_trem > 0.5:
    problems.append(f"Tremor too large: {mean_trem:.4f}")
    score -= 20

# Breathing
if 0.15 < breath_freq < 0.35:
    good.append(f"Breathing: {breath_freq:.3f}Hz")
else:
    warnings.append(f"Breathing freq off: {breath_freq:.3f}Hz")
    score -= 5

# Velocity cap
if flat_count < 5:
    good.append("No hard velocity cap detected")
else:
    problems.append(f"Hard velocity cap: {flat_count} flat segments")
    score -= 15

# Fall camera
fall_with_pitch = 0
fall_without_pitch = 0
for start, end in falls:
    pitches = [rows[i]['pitch'] for i in range(start, end + 1)]
    if max(pitches) - rows[start]['pitch'] > 5:
        fall_with_pitch += 1
    else:
        fall_without_pitch += 1

if falls:
    if fall_without_pitch == 0:
        good.append(f"All {len(falls)} falls have camera look-down")
    elif fall_without_pitch <= len(falls) * 0.3:
        warnings.append(f"Some falls lack look-down: {fall_without_pitch}/{len(falls)}")
        score -= 5
    else:
        problems.append(f"Most falls lack look-down: {fall_without_pitch}/{len(falls)}")
        score -= 15

# Jerkiness
if accels:
    jerk_pct = jerks_3k / len(accels) * 100
    if jerk_pct < 3:
        good.append(f"Smooth movement (jerks: {jerk_pct:.1f}%)")
    elif jerk_pct < 8:
        warnings.append(f"Slight jerkiness: {jerk_pct:.1f}%")
        score -= 5
    else:
        problems.append(f"Jerky movement: {jerk_pct:.1f}%")
        score -= 10

# Pitch variety
if running_frames:
    rp = [abs(rows[idx]['pitch'] - rows[idx-1]['pitch']) for idx in running_frames if idx > 0]
    if rp:
        zero_pct = sum(1 for d in rp if d < 0.001) / len(rp) * 100
        if zero_pct < 30:
            good.append(f"Pitch active while running ({zero_pct:.0f}% static)")
        elif zero_pct < 60:
            warnings.append(f"Pitch somewhat static: {zero_pct:.0f}%")
            score -= 5
        else:
            problems.append(f"Pitch too static: {zero_pct:.0f}%")
            score -= 10

# Pre-edge
if pe_episodes:
    good.append(f"Pre-edge peek: {len(pe_episodes)} episodes detected")
elif falls:
    warnings.append("No pre-edge peek episodes despite falls")
    score -= 5

# Attention
attn_range = max(attns) - min(attns)
if attn_range > 0.1:
    good.append(f"Attention varies: {min(attns):.2f}-{max(attns):.2f}")
else:
    warnings.append(f"Attention barely varies: range {attn_range:.3f}")
    score -= 3

# Dwell
dwell_frames = sum(1 for r in rows if r['dwell'] == 1)
if dwell_frames > 0:
    good.append(f"Post-saccade dwell: {dwell_frames/N*100:.1f}% of frames")

print(f"\n  HUMANNESS SCORE: {score}/100\n")

for g in good:
    print(f"  [GOOD] {g}")
print()
for w in warnings:
    print(f"  [WARN] {w}")
for p in problems:
    print(f"  [FAIL] {p}")

if score >= 90:
    print(f"\n  VERDICT: Excellent -- very difficult to distinguish from human")
elif score >= 75:
    print(f"\n  VERDICT: Good -- passes casual inspection, some edge cases remain")
elif score >= 60:
    print(f"\n  VERDICT: Fair -- noticeable to trained eye or statistical analysis")
else:
    print(f"\n  VERDICT: Poor -- detectable by basic analysis")

print(f"\n{'=' * 70}")
