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

# Compute deltas
pitch_deltas = []
yaw_deltas = []
for i in range(1, N):
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    dy = rows[i]['yaw'] - rows[i-1]['yaw']
    while dy > 180: dy -= 360
    while dy < -180: dy += 360
    pitch_deltas.append(dp)
    yaw_deltas.append(dy)

# =====================================================================
# 1. FALL EPISODES — DETAILED CAMERA BEHAVIOR
# =====================================================================
print("\n" + "=" * 75)
print("1. FALL EPISODES — CAMERA BEHAVIOR DETAIL")
print("=" * 75)

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

print(f"Total fall episodes: {len(fall_episodes)}")

# Categorize falls
short_falls = [(s,e) for s,e in fall_episodes if (rows[e]['time_ms']-rows[s]['time_ms']) < 200]
medium_falls = [(s,e) for s,e in fall_episodes if 200 <= (rows[e]['time_ms']-rows[s]['time_ms']) < 600]
long_falls = [(s,e) for s,e in fall_episodes if (rows[e]['time_ms']-rows[s]['time_ms']) >= 600]
print(f"Short (<200ms): {len(short_falls)}  Medium (200-600ms): {len(medium_falls)}  Long (>600ms): {len(long_falls)}")

# Detailed analysis of medium and long falls
for label, falls in [("MEDIUM", medium_falls), ("LONG", long_falls)]:
    print(f"\n--- {label} FALLS ---")
    for fi, (fs, fe) in enumerate(falls):
        dur_ms = rows[fe]['time_ms'] - rows[fs]['time_ms']
        base_pitch = rows[fs]['pitch']
        max_pitch = max(rows[i]['pitch'] for i in range(fs, fe+1))
        min_pitch = min(rows[i]['pitch'] for i in range(fs, fe+1))
        pitch_range = max_pitch - min_pitch

        # Pitch velocity during fall
        pvs = [rows[i]['pitch_vel'] for i in range(fs, fe+1)]
        max_pv = max(pvs)
        min_pv = min(pvs)

        # Reaction: when pitch moves >1deg from start
        react_ms = -1
        for i in range(fs, fe+1):
            if rows[i]['pitch'] > base_pitch + 1.0:
                react_ms = rows[i]['time_ms'] - rows[fs]['time_ms']
                break

        # Check pre-edge before
        pre_edge_before = sum(1 for i in range(max(0, fs-60), fs) if rows[i]['pre_edge'] == 1)
        fall_type = "EXPECTED" if pre_edge_before > 10 else "SURPRISE"

        # Pitch acceleration during fall
        fall_accels = []
        for i in range(fs+1, min(fe+1, N)):
            dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
            if 0.0001 < dt_s < 0.1:
                pa = (rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s
                fall_accels.append(pa)

        max_accel = max(abs(a) for a in fall_accels) if fall_accels else 0

        # Direction reversals during fall
        fall_pitch_d = [rows[i]['pitch'] - rows[i-1]['pitch'] for i in range(fs+1, fe+1)]
        reversals = 0
        for j in range(1, len(fall_pitch_d)):
            if fall_pitch_d[j] * fall_pitch_d[j-1] < 0 and abs(fall_pitch_d[j]) > 0.01 and abs(fall_pitch_d[j-1]) > 0.01:
                reversals += 1

        react_str = f"{react_ms:.0f}ms" if react_ms >= 0 else "NO REACTION"

        print(f"\n  Fall frames {fs}-{fe} ({dur_ms:.0f}ms) [{fall_type}]")
        print(f"    Pitch: {base_pitch:.1f} -> range [{min_pitch:.1f}, {max_pitch:.1f}] (span: {pitch_range:.1f})")
        print(f"    Reaction: {react_str}")
        print(f"    Pitch vel: [{min_pv:.1f}, {max_pv:.1f}] deg/s")
        print(f"    Max pitch accel: {max_accel:.0f} deg/s2")
        print(f"    Pitch reversals: {reversals}/{len(fall_pitch_d)-1 if len(fall_pitch_d)>1 else 1}")

        # Show pitch profile (sampled every ~50ms)
        print(f"    Pitch profile:", end="")
        step = max(1, (fe - fs) // 15)
        for i in range(fs, fe+1, step):
            t_rel = rows[i]['time_ms'] - rows[fs]['time_ms']
            print(f" {t_rel:.0f}:{rows[i]['pitch']:.1f}", end="")
        print()

        # Show what goal pitch was doing
        print(f"    Goal pitch:", end="")
        for i in range(fs, fe+1, step):
            t_rel = rows[i]['time_ms'] - rows[fs]['time_ms']
            print(f" {t_rel:.0f}:{rows[i]['goal_pitch_smooth']:.1f}", end="")
        print()

# =====================================================================
# 2. JERKINESS DURING FALLS vs NORMAL
# =====================================================================
print("\n" + "=" * 75)
print("2. JERKINESS: FALLS vs NORMAL MOVEMENT")
print("=" * 75)

fall_set = set()
for fs, fe in fall_episodes:
    for i in range(fs, fe+1):
        fall_set.add(i)

# Pitch acceleration
fall_pa = []
normal_pa = []
for i in range(1, N):
    dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
    if 0.0001 < dt_s < 0.1:
        pa = abs((rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s)
        if i in fall_set:
            fall_pa.append(pa)
        else:
            normal_pa.append(pa)

if fall_pa:
    fpa = sorted(fall_pa)
    print(f"FALL pitch accel:   mean={sum(fpa)/len(fpa):.0f}  p90={fpa[int(len(fpa)*0.9)]:.0f}  p95={fpa[int(len(fpa)*0.95)]:.0f}  p99={fpa[int(len(fpa)*0.99)]:.0f}  max={fpa[-1]:.0f}")
if normal_pa:
    npa = sorted(normal_pa)
    print(f"NORMAL pitch accel: mean={sum(npa)/len(npa):.0f}  p90={npa[int(len(npa)*0.9)]:.0f}  p95={npa[int(len(npa)*0.95)]:.0f}  p99={npa[int(len(npa)*0.99)]:.0f}  max={npa[-1]:.0f}")

# Pitch reversals
fall_rev = 0
fall_total = 0
normal_rev = 0
normal_total = 0
for i in range(1, len(pitch_deltas)):
    if abs(pitch_deltas[i]) > 0.01 and abs(pitch_deltas[i-1]) > 0.01:
        if i+1 in fall_set:
            fall_total += 1
            if pitch_deltas[i] * pitch_deltas[i-1] < 0:
                fall_rev += 1
        else:
            normal_total += 1
            if pitch_deltas[i] * pitch_deltas[i-1] < 0:
                normal_rev += 1

if fall_total > 0:
    print(f"\nFALL pitch reversals:   {fall_rev}/{fall_total} ({fall_rev/fall_total*100:.1f}%)")
if normal_total > 0:
    print(f"NORMAL pitch reversals: {normal_rev}/{normal_total} ({normal_rev/normal_total*100:.1f}%)")

# =====================================================================
# 3. SACCADE→FALL TRANSITIONS (biggest jerks)
# =====================================================================
print("\n" + "=" * 75)
print("3. SACCADE/FALL TRANSITIONS")
print("=" * 75)

# Find frames where saccade AND falling are both active
sacc_fall = sum(1 for r in rows if r['saccade'] == 1 and r['falling'] == 1)
sacc_total = sum(1 for r in rows if r['saccade'] == 1)
fall_total_frames = sum(1 for r in rows if r['falling'] == 1)
print(f"Saccade frames: {sacc_total}  Falling frames: {fall_total_frames}  Saccade+Falling overlap: {sacc_fall}")

# Find saccade start/end during falls
transitions = []
for i in range(1, N):
    if rows[i]['falling'] == 1:
        # Saccade boundary during fall
        if rows[i]['saccade'] != rows[i-1]['saccade']:
            dt_s = (rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0
            if 0.0001 < dt_s < 0.1:
                pa = abs((rows[i]['pitch_vel'] - rows[i-1]['pitch_vel']) / dt_s)
                kind = "SACC_START" if rows[i]['saccade'] == 1 else "SACC_END"
                transitions.append((i, kind, pa))

print(f"\nSaccade transitions during falls: {len(transitions)}")
for idx, kind, accel in sorted(transitions, key=lambda x: x[2], reverse=True)[:10]:
    print(f"  frame={idx} {kind} pitch_accel={accel:.0f} deg/s2  pitch_vel={rows[idx]['pitch_vel']:.1f}  pitch={rows[idx]['pitch']:.1f}")

# =====================================================================
# 4. GOAL PITCH STABILITY DURING FALLS
# =====================================================================
print("\n" + "=" * 75)
print("4. GOAL PITCH BEHAVIOR DURING FALLS")
print("=" * 75)

for fi, (fs, fe) in enumerate(long_falls + medium_falls):
    dur_ms = rows[fe]['time_ms'] - rows[fs]['time_ms']
    if dur_ms < 250: continue

    goal_pitches = [rows[i]['goal_pitch_smooth'] for i in range(fs, fe+1)]
    goal_deltas = [goal_pitches[j+1] - goal_pitches[j] for j in range(len(goal_pitches)-1)]

    # Goal pitch acceleration
    goal_accels = []
    for j in range(1, len(goal_deltas)):
        dt_s = (rows[fs+j+1]['time_ms'] - rows[fs+j]['time_ms']) / 1000.0
        if 0.0001 < dt_s < 0.1:
            ga = (goal_deltas[j] - goal_deltas[j-1]) / (dt_s * dt_s) if dt_s > 0 else 0
            goal_accels.append(abs(ga))

    goal_rev = 0
    for j in range(1, len(goal_deltas)):
        if goal_deltas[j] * goal_deltas[j-1] < 0 and abs(goal_deltas[j]) > 0.01:
            goal_rev += 1

    max_gd = max(abs(d) for d in goal_deltas) if goal_deltas else 0
    print(f"\n  Fall {fs}-{fe} ({dur_ms:.0f}ms):")
    print(f"    Goal pitch range: [{min(goal_pitches):.1f}, {max(goal_pitches):.1f}]")
    print(f"    Goal pitch max delta/frame: {max_gd:.3f} deg")
    print(f"    Goal pitch reversals: {goal_rev}/{len(goal_deltas)}")

# =====================================================================
# 5. OVERALL PITCH JERKINESS METRICS
# =====================================================================
print("\n" + "=" * 75)
print("5. OVERALL PITCH SMOOTHNESS")
print("=" * 75)

# Alternating pattern check
sens_step = 0.0845
alternating = 0
for i in range(2, len(pitch_deltas)):
    d1 = pitch_deltas[i-1]
    d2 = pitch_deltas[i]
    if abs(abs(d1) - sens_step) < 0.005 and abs(abs(d2) - sens_step) < 0.005:
        if d1 * d2 < 0:
            alternating += 1
print(f"Alternating 1px pitch: {alternating} ({alternating/(N-2)*100:.2f}%)")

# Pitch same-dir run lengths
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

# Pitch static
running = [i for i in range(N) if rows[i]['saccade'] == 0 and rows[i]['falling'] == 0 and rows[i]['error'] < 10]
if running:
    run_pd = [abs(rows[i]['pitch'] - rows[i-1]['pitch']) for i in running if i > 0]
    zero_r = sum(1 for d in run_pd if d < 0.001)
    print(f"Pitch static while running: {zero_r}/{len(run_pd)} ({zero_r/len(run_pd)*100:.1f}%)")

# Quantization grid
total = len(pitch_deltas)
clean_y = sum(1 for d in yaw_deltas if abs(d) < 0.0001 or abs(abs(d)/sens_step - round(abs(d)/sens_step)) < 0.015)
dirty_y = total - clean_y
clean_p = sum(1 for d in pitch_deltas if abs(d) < 0.0001 or abs(abs(d)/sens_step - round(abs(d)/sens_step)) < 0.015)
dirty_p = total - clean_p
print(f"\nYaw on grid: {clean_y}/{total} ({clean_y/total*100:.1f}%)  off: {dirty_y}")
print(f"Pitch on grid: {clean_p}/{total} ({clean_p/total*100:.1f}%)  off: {dirty_p}")

# =====================================================================
# 6. SPRING OMEGA DURING FALLS
# =====================================================================
print("\n" + "=" * 75)
print("6. PITCH SPRING BEHAVIOR DURING FALLS")
print("=" * 75)

# How fast is the pitch spring responding?
for fi, (fs, fe) in enumerate(long_falls[:5]):
    dur_ms = rows[fe]['time_ms'] - rows[fs]['time_ms']
    print(f"\n  Long fall {fs}-{fe} ({dur_ms:.0f}ms):")
    print(f"    {'frame':>6} {'t_ms':>6} {'pitch':>7} {'goal_p':>7} {'pv':>7} {'error':>7} {'sacc':>5}")
    step = max(1, (fe - fs) // 20)
    for i in range(fs, fe+1, step):
        t = rows[i]['time_ms'] - rows[fs]['time_ms']
        p = rows[i]['pitch']
        gp = rows[i]['goal_pitch_smooth']
        pv = rows[i]['pitch_vel']
        err = rows[i]['error']
        s = "SACC" if rows[i]['saccade'] == 1 else ""
        print(f"    {i:6d} {t:6.0f} {p:7.1f} {gp:7.1f} {pv:7.1f} {err:7.1f} {s:>5}")
