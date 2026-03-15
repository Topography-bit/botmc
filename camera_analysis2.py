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
print(f"FRAMES: {N}   DURATION: {duration_s:.1f}s")
print("=" * 70)

# ── Quantization check ──────────────────────────────────────────
print("\n1. QUANTIZATION CHECK")
print("-" * 70)

# Detect sensitivity step from most common delta
yaw_deltas = []
pitch_deltas = []
for i in range(1, N):
    dy = rows[i]['yaw'] - rows[i-1]['yaw']
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    # Wrap yaw
    while dy > 180: dy -= 360
    while dy < -180: dy += 360
    yaw_deltas.append(dy)
    pitch_deltas.append(dp)

# Find smallest non-zero delta (should be the step)
nonzero_yaw = sorted(set(abs(round(d, 6)) for d in yaw_deltas if abs(d) > 0.0001))
nonzero_pitch = sorted(set(abs(round(d, 6)) for d in pitch_deltas if abs(d) > 0.0001))

print(f"Smallest non-zero yaw deltas: {nonzero_yaw[:10]}")
print(f"Smallest non-zero pitch deltas: {nonzero_pitch[:10]}")

# Check if deltas are multiples of a common step
if nonzero_yaw:
    # Try to find GCD-like step
    step_candidate = nonzero_yaw[0]
    print(f"\nCandidate step: {step_candidate:.6f} deg")

    # Check how many deltas are clean multiples
    clean_yaw = 0
    dirty_yaw = 0
    max_residual = 0
    residuals = []
    for d in yaw_deltas:
        if abs(d) < 0.0001:
            clean_yaw += 1
            continue
        ratio = abs(d) / step_candidate
        residual = abs(ratio - round(ratio))
        residuals.append(residual)
        if residual < 0.01:
            clean_yaw += 1
        else:
            dirty_yaw += 1
            max_residual = max(max_residual, residual)

    print(f"Yaw deltas on grid: {clean_yaw}/{N-1} ({clean_yaw/(N-1)*100:.1f}%)")
    print(f"Yaw deltas off grid: {dirty_yaw}/{N-1} ({dirty_yaw/(N-1)*100:.1f}%)")
    if residuals:
        res_sorted = sorted(residuals)
        print(f"Residual: mean={sum(residuals)/len(residuals):.6f}  p99={res_sorted[int(len(residuals)*0.99)]:.6f}  max={max_residual:.6f}")

    # Same for pitch
    clean_pitch = 0
    dirty_pitch = 0
    for d in pitch_deltas:
        if abs(d) < 0.0001:
            clean_pitch += 1
            continue
        ratio = abs(d) / step_candidate
        residual = abs(ratio - round(ratio))
        if residual < 0.01:
            clean_pitch += 1
        else:
            dirty_pitch += 1
    print(f"Pitch deltas on grid: {clean_pitch}/{N-1} ({clean_pitch/(N-1)*100:.1f}%)")

    # Expected step for common sensitivities
    for sens_pct in [50, 80, 100, 120, 150, 200]:
        s = sens_pct / 200.0  # internal value
        f = s * 0.6 + 0.2
        step = f * f * f * 8.0 * 0.15
        match = "  <-- MATCH" if abs(step - step_candidate) / step_candidate < 0.02 else ""
        print(f"  Sens {sens_pct:3d}%: step={step:.6f}{match}")

# ── Pitch movement analysis ─────────────────────────────────────
print("\n2. PITCH MOVEMENT ANALYSIS")
print("-" * 70)

# Identify "running" segments: no saccade, no falling, no combat (low error)
running_frames = []
for i in range(N):
    if rows[i]['saccade'] == 0 and rows[i]['falling'] == 0 and rows[i]['error'] < 10:
        running_frames.append(i)

print(f"'Running' frames (no saccade, no fall, error<10): {len(running_frames)} ({len(running_frames)/N*100:.1f}%)")

if running_frames:
    running_pitch_deltas = []
    for idx in running_frames:
        if idx > 0:
            dp = abs(rows[idx]['pitch'] - rows[idx-1]['pitch'])
            running_pitch_deltas.append(dp)

    if running_pitch_deltas:
        zero_pitch = sum(1 for d in running_pitch_deltas if d < 0.001)
        tiny_pitch = sum(1 for d in running_pitch_deltas if 0.001 <= d < 0.1)
        small_pitch = sum(1 for d in running_pitch_deltas if 0.1 <= d < 1.0)
        med_pitch = sum(1 for d in running_pitch_deltas if d >= 1.0)
        total = len(running_pitch_deltas)
        print(f"  Pitch delta = 0: {zero_pitch} ({zero_pitch/total*100:.1f}%)")
        print(f"  Pitch delta < 0.1: {tiny_pitch} ({tiny_pitch/total*100:.1f}%)")
        print(f"  Pitch delta 0.1-1.0: {small_pitch} ({small_pitch/total*100:.1f}%)")
        print(f"  Pitch delta > 1.0: {med_pitch} ({med_pitch/total*100:.1f}%)")
        print(f"  Mean pitch delta: {sum(running_pitch_deltas)/total:.4f} deg")

# Overall pitch stats
pitch_static = sum(1 for d in pitch_deltas if abs(d) < 0.001)
print(f"\nOverall pitch static frames: {pitch_static}/{N-1} ({pitch_static/(N-1)*100:.1f}%)")

# ── Jerkiness analysis ──────────────────────────────────────────
print("\n3. JERKINESS / SMOOTHNESS ANALYSIS")
print("-" * 70)

# Compute acceleration (change in velocity between frames)
yaw_accels = []
pitch_accels = []
for i in range(1, N):
    dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
    if dt_s > 0.0001 and dt_s < 0.1:
        ya = (rows[i]['yaw_vel'] - rows[i-1]['yaw_vel']) / dt_s
        pa = (rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s
        yaw_accels.append(ya)
        pitch_accels.append(pa)

if yaw_accels:
    total_accels = [math.sqrt(y*y + p*p) for y, p in zip(yaw_accels, pitch_accels)]
    ta_sorted = sorted(total_accels)
    print(f"Acceleration: mean={sum(total_accels)/len(total_accels):.1f}  median={ta_sorted[len(ta_sorted)//2]:.1f}")
    print(f"  p95={ta_sorted[int(len(ta_sorted)*0.95)]:.1f}  p99={ta_sorted[int(len(ta_sorted)*0.99)]:.1f}  max={max(total_accels):.1f} deg/s2")

# Detect "jerks" — sudden velocity changes
jerk_threshold = 3000  # deg/s2
jerks = [(i, a) for i, a in enumerate(total_accels) if a > jerk_threshold]
print(f"\nJerks (accel > {jerk_threshold} deg/s2): {len(jerks)} ({len(jerks)/len(total_accels)*100:.2f}%)")

# Analyze jerk context — are they at saccade boundaries?
if jerks:
    at_saccade_boundary = 0
    at_dwell_boundary = 0
    other = 0
    for idx, accel in jerks[:100]:
        if idx >= N-1: continue
        s_now = rows[idx+1]['saccade']
        s_prev = rows[idx]['saccade'] if idx < len(rows) else 0
        d_now = rows[idx+1]['dwell']
        d_prev = rows[idx]['dwell'] if idx < len(rows) else 0
        if s_now != s_prev:
            at_saccade_boundary += 1
        elif d_now != d_prev:
            at_dwell_boundary += 1
        else:
            other += 1
    print(f"  At saccade start/end: {at_saccade_boundary}")
    print(f"  At dwell start/end: {at_dwell_boundary}")
    print(f"  Other (mid-spring): {other}")

# Velocity sign changes (direction reversals) — too many = jerky
yaw_reversals = 0
for i in range(2, len(yaw_deltas)):
    if yaw_deltas[i] * yaw_deltas[i-1] < 0 and abs(yaw_deltas[i]) > 0.01 and abs(yaw_deltas[i-1]) > 0.01:
        yaw_reversals += 1
print(f"\nYaw direction reversals: {yaw_reversals} ({yaw_reversals/(N-2)*100:.1f}%)")

# Consecutive same-direction frames (smooth = long runs)
run_lengths = []
current_run = 1
for i in range(2, len(yaw_deltas)):
    if yaw_deltas[i] * yaw_deltas[i-1] > 0:
        current_run += 1
    else:
        run_lengths.append(current_run)
        current_run = 1
run_lengths.append(current_run)
if run_lengths:
    rl_sorted = sorted(run_lengths)
    print(f"Same-direction run length: mean={sum(run_lengths)/len(run_lengths):.1f}  median={rl_sorted[len(rl_sorted)//2]}  max={max(run_lengths)}")

# ── Quantization dithering effect on tremor ─────────────────────
print("\n4. QUANTIZATION EFFECT ON TREMOR")
print("-" * 70)

# How many frames have zero delta (quantization ate the movement)?
zero_yaw = sum(1 for d in yaw_deltas if abs(d) < 0.0001)
zero_pitch = sum(1 for d in pitch_deltas if abs(d) < 0.0001)
print(f"Zero yaw delta frames: {zero_yaw}/{N-1} ({zero_yaw/(N-1)*100:.1f}%)")
print(f"Zero pitch delta frames: {zero_pitch}/{N-1} ({zero_pitch/(N-1)*100:.1f}%)")

# ── Standard analysis sections ──────────────────────────────────
print("\n5. VELOCITY & SACCADE SUMMARY")
print("-" * 70)
total_vels = [math.sqrt(r['yaw_vel']**2 + r['pitch_vel']**2) for r in rows]
print(f"Total vel: mean={sum(total_vels)/N:.1f}  max={max(total_vels):.1f} deg/s")
print(f"Yaw vel: max={max(r['yaw_vel'] for r in rows):.1f}  min={min(r['yaw_vel'] for r in rows):.1f}")

saccade_frames = sum(1 for r in rows if r['saccade'] == 1)
fall_frames = sum(1 for r in rows if r['falling'] == 1)
dwell_frames = sum(1 for r in rows if r['dwell'] == 1)
print(f"Saccade: {saccade_frames/N*100:.1f}%  Falling: {fall_frames/N*100:.1f}%  Dwell: {dwell_frames/N*100:.1f}%")

tremor_yaws = [r['tremor_yaw'] for r in rows]
print(f"Tremor yaw: mean_abs={sum(abs(t) for t in tremor_yaws)/N:.6f}  max={max(abs(t) for t in tremor_yaws):.6f}")

breaths = [r['breath_offset'] for r in rows]
print(f"Breath: range={max(breaths)-min(breaths):.4f}")

errors = [r['error'] for r in rows]
print(f"Error: mean={sum(errors)/N:.1f}  max={max(errors):.1f}")

print("\n6. VERDICT")
print("=" * 70)
issues = []
warnings = []

# Quantization
if nonzero_yaw and dirty_yaw / (N-1) > 0.05:
    issues.append(f"Quantization broken: {dirty_yaw/(N-1)*100:.1f}% off-grid")

# Pitch too static when running
if running_frames:
    running_pitch_deltas_v2 = [abs(rows[idx]['pitch'] - rows[idx-1]['pitch']) for idx in running_frames if idx > 0]
    if running_pitch_deltas_v2:
        zero_run_pitch = sum(1 for d in running_pitch_deltas_v2 if d < 0.001)
        if zero_run_pitch / len(running_pitch_deltas_v2) > 0.7:
            issues.append(f"Pitch static {zero_run_pitch/len(running_pitch_deltas_v2)*100:.0f}% while running — needs more vertical micro-movement")

# Jerkiness
if jerks and len(jerks) / len(total_accels) > 0.03:
    warnings.append(f"Jerkiness: {len(jerks)/len(total_accels)*100:.1f}% high-accel frames")

# Tremor freq
yaw_crossings = sum(1 for i in range(1, N) if tremor_yaws[i] * tremor_yaws[i-1] < 0)
yaw_freq_est = yaw_crossings / (2 * duration_s)
if abs(yaw_freq_est - 10.0) > 3.0:
    issues.append(f"Tremor freq off: {yaw_freq_est:.1f}Hz")

for issue in issues:
    print(f"  [PROBLEM] {issue}")
for warning in warnings:
    print(f"  [WARNING] {warning}")
if not issues and not warnings:
    print("  All checks passed!")
else:
    print(f"\n  {len(issues)} problems, {len(warnings)} warnings")
