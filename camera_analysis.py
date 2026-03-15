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
avg_dt = duration_s / (N - 1) * 1000 if N > 1 else 0

print("=" * 70)
print("CAMERA DEBUG CSV ANALYSIS")
print("=" * 70)
print(f"Frames: {N}")
print(f"Duration: {duration_s:.1f}s ({duration_s/60:.1f}min)")
if avg_dt > 0:
    print(f"Avg frame interval: {avg_dt:.2f}ms ({1000/avg_dt:.1f} FPS)")

dts = []
for i in range(1, N):
    dt = rows[i]['time_ms'] - rows[i-1]['time_ms']
    dts.append(dt)

dts_sorted = sorted(dts)
print(f"Frame dt: min={dts_sorted[0]:.2f}ms  median={dts_sorted[len(dts)//2]:.2f}ms  max={dts_sorted[-1]:.2f}ms")
print(f"  p1={dts_sorted[int(len(dts)*0.01)]:.2f}  p99={dts_sorted[int(len(dts)*0.99)]:.2f}ms")

stalls = [(i, dt) for i, dt in enumerate(dts) if dt > 50]
print(f"  Stalls (>50ms): {len(stalls)}")

print(f"\n{'=' * 70}")
print("1. YAW & PITCH RANGE")
print("=" * 70)
yaws = [r['yaw'] for r in rows]
pitches = [r['pitch'] for r in rows]
print(f"Yaw:   min={min(yaws):.1f}  max={max(yaws):.1f}  range={max(yaws)-min(yaws):.1f} deg")
print(f"Pitch: min={min(pitches):.1f}  max={max(pitches):.1f}  range={max(pitches)-min(pitches):.1f} deg")

print(f"\n{'=' * 70}")
print("2. VELOCITY ANALYSIS")
print("=" * 70)
yaw_vels = [r['yaw_vel'] for r in rows]
pitch_vels = [r['pitch_vel'] for r in rows]
total_vels = [math.sqrt(r['yaw_vel']**2 + r['pitch_vel']**2) for r in rows]

print(f"Yaw vel:   mean={sum(yaw_vels)/N:.2f}  max={max(yaw_vels):.2f}  min={min(yaw_vels):.2f} deg/s")
print(f"Pitch vel: mean={sum(pitch_vels)/N:.2f}  max={max(pitch_vels):.2f}  min={min(pitch_vels):.2f} deg/s")
print(f"Total vel: mean={sum(total_vels)/N:.2f}  max={max(total_vels):.2f} deg/s")

vel_buckets = defaultdict(int)
for v in total_vels:
    bucket = int(v / 50) * 50
    vel_buckets[bucket] += 1
print("\nVelocity distribution (deg/s):")
for b in sorted(vel_buckets.keys()):
    pct = vel_buckets[b] / N * 100
    bar = '#' * int(pct / 2)
    print(f"  {b:4d}-{b+50:4d}: {vel_buckets[b]:5d} ({pct:5.1f}%) {bar}")

zero_vel = sum(1 for v in total_vels if v < 0.1)
print(f"\nNear-zero velocity (<0.1 deg/s): {zero_vel} frames ({zero_vel/N*100:.1f}%)")

print(f"\n{'=' * 70}")
print("3. SACCADE ANALYSIS")
print("=" * 70)
saccade_frames = sum(1 for r in rows if r['saccade'] == 1)
print(f"Saccade frames: {saccade_frames} ({saccade_frames/N*100:.1f}%)")

saccades = []
in_sacc = False
sacc_start = 0
for i, r in enumerate(rows):
    if r['saccade'] == 1 and not in_sacc:
        in_sacc = True
        sacc_start = i
    elif r['saccade'] == 0 and in_sacc:
        in_sacc = False
        dur_ms = rows[i-1]['time_ms'] - rows[sacc_start]['time_ms']
        start_yaw = rows[sacc_start]['yaw']
        end_yaw = rows[i-1]['yaw']
        start_pitch = rows[sacc_start]['pitch']
        end_pitch = rows[i-1]['pitch']
        dist = math.sqrt((end_yaw - start_yaw)**2 + (end_pitch - start_pitch)**2)
        peak_vel = max(total_vels[sacc_start:i]) if i > sacc_start else 0
        saccades.append({
            'start_ms': rows[sacc_start]['time_ms'],
            'dur_ms': dur_ms,
            'dist': dist,
            'peak_vel': peak_vel,
            'frames': i - sacc_start,
            'end_tau': rows[i-1]['saccade_tau'] if i-1 < N else 0
        })

print(f"Individual saccades: {len(saccades)}")
if saccades:
    durs = [s['dur_ms'] for s in saccades]
    dists = [s['dist'] for s in saccades]
    peaks = [s['peak_vel'] for s in saccades]
    print(f"  Duration:  min={min(durs):.0f}  mean={sum(durs)/len(durs):.0f}  max={max(durs):.0f} ms")
    print(f"  Distance:  min={min(dists):.1f}  mean={sum(dists)/len(dists):.1f}  max={max(dists):.1f} deg")
    print(f"  Peak vel:  min={min(peaks):.1f}  mean={sum(peaks)/len(peaks):.1f}  max={max(peaks):.1f} deg/s")

    print("\n  Fitts law check (duration vs log2(distance)):")
    for s in saccades[:15]:
        if s['dist'] > 0:
            fitts_pred = 120 + 8 * math.log2(s['dist'] + 1)
            print(f"    dist={s['dist']:6.1f}deg  dur={s['dur_ms']:6.0f}ms  fitts_pred={fitts_pred:.0f}ms  ratio={s['dur_ms']/fitts_pred:.2f}")

    end_taus = [s['end_tau'] for s in saccades]
    print(f"\n  End tau: min={min(end_taus):.3f}  mean={sum(end_taus)/len(end_taus):.3f}  max={max(end_taus):.3f}")

print(f"\n{'=' * 70}")
print("4. TREMOR ANALYSIS")
print("=" * 70)
tremor_yaws = [r['tremor_yaw'] for r in rows]
tremor_pitches = [r['tremor_pitch'] for r in rows]
print(f"Tremor yaw:   mean_abs={sum(abs(t) for t in tremor_yaws)/N:.6f}  max={max(abs(t) for t in tremor_yaws):.6f}")
print(f"Tremor pitch: mean_abs={sum(abs(t) for t in tremor_pitches)/N:.6f}  max={max(abs(t) for t in tremor_pitches):.6f}")

yaw_crossings = 0
pitch_crossings = 0
for i in range(1, N):
    if tremor_yaws[i] * tremor_yaws[i-1] < 0:
        yaw_crossings += 1
    if tremor_pitches[i] * tremor_pitches[i-1] < 0:
        pitch_crossings += 1

yaw_freq_est = yaw_crossings / (2 * duration_s)
pitch_freq_est = pitch_crossings / (2 * duration_s)
print(f"Estimated tremor freq (zero-crossings): yaw={yaw_freq_est:.1f}Hz  pitch={pitch_freq_est:.1f}Hz")
print(f"  Expected: ~10Hz yaw, ~9.3Hz pitch")

chunk_size = N // 10
tremor_chunks = []
for c in range(10):
    chunk = tremor_yaws[c*chunk_size:(c+1)*chunk_size]
    rms = math.sqrt(sum(t*t for t in chunk) / len(chunk))
    tremor_chunks.append(rms)
print(f"\nTremor yaw RMS by 10% chunks (should vary, not constant):")
for i, rms in enumerate(tremor_chunks):
    bar = '#' * int(rms * 2000)
    print(f"  {i*10:3d}-{(i+1)*10:3d}%: {rms:.6f} {bar}")

corr_sum = 0
yaw_var = 0
pitch_var = 0
yaw_mean = sum(tremor_yaws) / N
pitch_mean = sum(tremor_pitches) / N
for i in range(N):
    dy = tremor_yaws[i] - yaw_mean
    dp = tremor_pitches[i] - pitch_mean
    corr_sum += dy * dp
    yaw_var += dy * dy
    pitch_var += dp * dp
if yaw_var > 0 and pitch_var > 0:
    correlation = corr_sum / math.sqrt(yaw_var * pitch_var)
    print(f"\nTremor cross-axis correlation: {correlation:.4f} (expected ~0.35)")

print(f"\n{'=' * 70}")
print("5. GOAL SMOOTHING / LAG ANALYSIS")
print("=" * 70)
lag_yaws = [abs(r['goal_yaw_raw'] - r['goal_yaw_smooth']) for r in rows]
lag_pitches = [abs(r['goal_pitch_raw'] - r['goal_pitch_smooth']) for r in rows]
print(f"Goal lag yaw:   mean={sum(lag_yaws)/N:.2f}  max={max(lag_yaws):.2f} deg")
print(f"Goal lag pitch: mean={sum(lag_pitches)/N:.2f}  max={max(lag_pitches):.2f} deg")

errors = [r['error'] for r in rows]
print(f"\nTracking error: mean={sum(errors)/N:.2f}  max={max(errors):.2f}  min={min(errors):.2f} deg")

err_buckets = defaultdict(int)
for e in errors:
    bucket = int(e / 5) * 5
    err_buckets[bucket] += 1
print("\nError distribution (deg):")
for b in sorted(err_buckets.keys())[:10]:
    pct = err_buckets[b] / N * 100
    bar = '#' * int(pct / 2)
    print(f"  {b:3d}-{b+5:3d}: {err_buckets[b]:5d} ({pct:5.1f}%) {bar}")

print(f"\n{'=' * 70}")
print("6. ATTENTION MODEL")
print("=" * 70)
attns = [r['attention'] for r in rows]
print(f"Attention: min={min(attns):.3f}  mean={sum(attns)/N:.3f}  max={max(attns):.3f}")
print(f"  Expected range: 0.78 - 1.22")

attn_diffs = [abs(attns[i] - attns[i-1]) for i in range(1, N)]
max_attn_jump = max(attn_diffs)
print(f"  Max single-frame jump: {max_attn_jump:.6f} (should be small)")

print(f"\n{'=' * 70}")
print("7. DWELL (POST-SACCADE CORRECTIONS)")
print("=" * 70)
dwell_frames = sum(1 for r in rows if r['dwell'] == 1)
print(f"Dwell frames: {dwell_frames} ({dwell_frames/N*100:.1f}%)")

dwells = []
in_dwell = False
dwell_start = 0
for i, r in enumerate(rows):
    if r['dwell'] == 1 and not in_dwell:
        in_dwell = True
        dwell_start = i
    elif r['dwell'] == 0 and in_dwell:
        in_dwell = False
        dur = rows[i-1]['time_ms'] - rows[dwell_start]['time_ms']
        dwells.append(dur)
print(f"Dwell episodes: {len(dwells)}")
if dwells:
    print(f"  Duration: min={min(dwells):.0f}  mean={sum(dwells)/len(dwells):.0f}  max={max(dwells):.0f} ms")

print(f"\n{'=' * 70}")
print("8. OMEGA SCALE (SPRING STIFFNESS VARIATION)")
print("=" * 70)
omegas = [r['omega_scale'] for r in rows]
print(f"Omega: min={min(omegas):.3f}  mean={sum(omegas)/N:.3f}  max={max(omegas):.3f}")

print(f"\n{'=' * 70}")
print("9. FALLING CAMERA")
print("=" * 70)
fall_frames = sum(1 for r in rows if r['falling'] == 1)
print(f"Falling frames: {fall_frames} ({fall_frames/N*100:.1f}%)")

falls = []
in_fall = False
fall_start = 0
for i, r in enumerate(rows):
    if r['falling'] == 1 and not in_fall:
        in_fall = True
        fall_start = i
    elif r['falling'] == 0 and in_fall:
        in_fall = False
        dur = rows[i-1]['time_ms'] - rows[fall_start]['time_ms']
        pitch_vals = [rows[j]['pitch'] for j in range(fall_start, i)]
        pitch_range = max(pitch_vals) - min(pitch_vals) if pitch_vals else 0
        falls.append({'dur': dur, 'pitch_range': pitch_range})
print(f"Fall episodes: {len(falls)}")
for j, f in enumerate(falls):
    print(f"  Fall {j+1}: {f['dur']:.0f}ms  pitch_range={f['pitch_range']:.1f}deg")

print(f"\n{'=' * 70}")
print("10. FATIGUE MODEL")
print("=" * 70)
fatigues = [r['fatigue'] for r in rows]
print(f"Fatigue: start={fatigues[0]:.4f}  end={fatigues[-1]:.4f}  max={max(fatigues):.4f}")
print(f"  Session {duration_s:.0f}s - expected ~0 for short sessions (<10min)")

print(f"\n{'=' * 70}")
print("11. BREATHING")
print("=" * 70)
breaths = [r['breath_offset'] for r in rows]
print(f"Breath offset: min={min(breaths):.4f}  max={max(breaths):.4f}  range={max(breaths)-min(breaths):.4f}")

breath_crossings = 0
for i in range(1, N):
    if breaths[i] * breaths[i-1] < 0:
        breath_crossings += 1
breath_freq = breath_crossings / (2 * duration_s)
print(f"Breath freq (zero-crossings): {breath_freq:.3f}Hz (expected ~0.25Hz)")

print(f"\n{'=' * 70}")
print("12. MOUSE LIFT")
print("=" * 70)
ml_frames = sum(1 for r in rows if r['mouse_lift'] == 1)
print(f"Mouse lift frames: {ml_frames} ({ml_frames/N*100:.2f}%)")

print(f"\n{'=' * 70}")
print("13. PITCH ANALYSIS")
print("=" * 70)
pitch_zero = sum(1 for r in rows if abs(r['pitch']) < 0.01)
pitch_near_zero = sum(1 for r in rows if abs(r['pitch']) < 1.0)
print(f"Pitch exactly 0 (+/-0.01): {pitch_zero} ({pitch_zero/N*100:.1f}%)")
print(f"Pitch near 0 (+/-1.0): {pitch_near_zero} ({pitch_near_zero/N*100:.1f}%)")
pitch_changes = sum(1 for i in range(1, N) if abs(pitches[i] - pitches[i-1]) > 0.001)
print(f"Pitch changing frames: {pitch_changes} ({pitch_changes/N*100:.1f}%)")

print(f"\n{'=' * 70}")
print("14. SMOOTHNESS - ACCELERATION ANALYSIS")
print("=" * 70)
accels = []
for i in range(1, N):
    dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
    if dt_s > 0.0001:
        accel_yaw = (rows[i]['yaw_vel'] - rows[i-1]['yaw_vel']) / dt_s
        accel_pitch = (rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s
        accels.append(math.sqrt(accel_yaw**2 + accel_pitch**2))

if accels:
    accels_sorted = sorted(accels)
    print(f"Acceleration: mean={sum(accels)/len(accels):.1f}  median={accels_sorted[len(accels)//2]:.1f}  p99={accels_sorted[int(len(accels)*0.99)]:.1f} deg/s2")
    print(f"  max={max(accels):.1f} deg/s2")
    high_accel = sum(1 for a in accels if a > 5000)
    print(f"  High accel spikes (>5000 deg/s2): {high_accel} ({high_accel/len(accels)*100:.2f}%)")

print(f"\n{'=' * 70}")
print("15. HUMANNESS VERDICT")
print("=" * 70)

issues = []
warnings = []

if len(saccades) == 0:
    issues.append("NO SACCADES detected - threshold too high or goal never moved >18 deg")

if abs(yaw_freq_est - 10.0) > 3.0:
    issues.append(f"Tremor yaw frequency off: {yaw_freq_est:.1f}Hz (expected ~10Hz)")
if abs(pitch_freq_est - 9.3) > 3.0:
    issues.append(f"Tremor pitch frequency off: {pitch_freq_est:.1f}Hz (expected ~9.3Hz)")

mean_tremor_yaw = sum(abs(t) for t in tremor_yaws) / N
if mean_tremor_yaw < 0.005:
    warnings.append(f"Tremor yaw very small ({mean_tremor_yaw:.6f}) - may be invisible")
if mean_tremor_yaw > 0.5:
    issues.append(f"Tremor yaw too large ({mean_tremor_yaw:.4f}) - would look shaky")

if pitch_zero / N > 0.8:
    issues.append(f"Pitch stuck at 0 for {pitch_zero/N*100:.0f}% of frames - no vertical aiming")

if max(attns) - min(attns) < 0.05:
    warnings.append(f"Attention barely varies ({min(attns):.3f}-{max(attns):.3f})")

if max(breaths) - min(breaths) < 0.01:
    issues.append("Breathing has no effect - amplitude too small")

if max(omegas) - min(omegas) < 0.05:
    warnings.append(f"Omega scale barely varies ({min(omegas):.3f}-{max(omegas):.3f})")

high_const = 0
for i in range(10, N):
    window = total_vels[i-10:i]
    if len(window) == 10 and max(window) - min(window) < 0.1 and sum(window)/10 > 5.0:
        high_const += 1
if high_const > N * 0.1:
    warnings.append(f"Constant-velocity segments: {high_const} frames - may look robotic")

if sum(lag_yaws) / N < 0.1 and sum(lag_pitches) / N < 0.1:
    warnings.append("Goal lag extremely small - smoothing may not be noticeable")

for issue in issues:
    print(f"  [PROBLEM] {issue}")
for warning in warnings:
    print(f"  [WARNING] {warning}")
if not issues and not warnings:
    print("  All checks passed!")
elif not issues:
    print(f"\n  {len(warnings)} warnings, 0 critical problems")
else:
    print(f"\n  {len(issues)} PROBLEMS, {len(warnings)} warnings")

print(f"\n{'=' * 70}")
