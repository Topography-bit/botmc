"""
Forensic analysis of pitch spikes: what patterns surround them?
"""
import sys, io, csv, math
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

INSTANCE = r"C:\Users\Ghost\AppData\Roaming\PrismLauncher\instances\WaldinDastinner1.12.10\minecraft"
BOT_CSV = INSTANCE + r"\camera_debug.csv"

def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append({k: float(v) for k, v in r.items()})
    return rows

rows = load(BOT_CSV)
N = len(rows)

# Find ALL pitch spikes > 1.5 deg
spikes = []
for i in range(1, N):
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    dt_ms = rows[i]['time_ms'] - rows[i-1]['time_ms']
    if abs(dp) > 1.5:
        spikes.append((i, dp, dt_ms))

print(f"Total frames: {N}")
print(f"Total pitch spikes (|dp| > 1.5): {len(spikes)}")

# Group consecutive spikes (within 30 frames of each other)
groups = []
current_group = []
for s in spikes:
    if current_group and s[0] - current_group[-1][0] > 30:
        groups.append(current_group)
        current_group = [s]
    else:
        current_group.append(s)
if current_group:
    groups.append(current_group)

print(f"Spike groups (bursts within 30 frames): {len(groups)}")

# Analyze each group
print(f"\n{'='*80}")
print(f"  SPIKE GROUP ANALYSIS")
print(f"{'='*80}")

for gi, group in enumerate(groups):
    first_frame = group[0][0]
    last_frame = group[-1][0]
    max_dp = max(abs(s[1]) for s in group)
    total_dp = sum(s[1] for s in group)
    falling = rows[first_frame].get('falling', 0)

    # Check 30 frames before: was pitch moving?
    pre_start = max(0, first_frame - 30)
    pre_deltas = [rows[j]['pitch'] - rows[j-1]['pitch'] for j in range(pre_start+1, first_frame)]
    pre_mean_dp = sum(abs(d) for d in pre_deltas) / len(pre_deltas) if pre_deltas else 0
    pre_direction = sum(1 if d > 0.001 else (-1 if d < -0.001 else 0) for d in pre_deltas)

    # Check 30 frames after
    post_end = min(N, last_frame + 31)
    post_deltas = [rows[j]['pitch'] - rows[j-1]['pitch'] for j in range(last_frame+1, post_end)]
    post_mean_dp = sum(abs(d) for d in post_deltas) / len(post_deltas) if post_deltas else 0

    # Check yaw context: was there a big yaw change?
    yaw_before = rows[max(0, first_frame-1)]['yaw']
    yaw_at = rows[first_frame]['yaw']
    yaw_after = rows[min(N-1, last_frame+1)]['yaw']

    # Total pitch change in the spike burst
    pitch_before = rows[max(0, first_frame-1)]['pitch']
    pitch_after = rows[min(N-1, last_frame)]['pitch']

    # Was there a DIRECTION change? (reversal)
    spike_dir = 1 if total_dp > 0 else -1
    pre_dir = 1 if pre_direction > 0 else (-1 if pre_direction < 0 else 0)
    is_reversal = (pre_dir != 0 and pre_dir != spike_dir)

    # Time span
    time_span_ms = rows[last_frame]['time_ms'] - rows[first_frame]['time_ms']

    print(f"\n--- Group #{gi+1}: frames {first_frame}-{last_frame} ({len(group)} spikes, {time_span_ms:.0f}ms) ---")
    print(f"  Max |dp|: {max_dp:.3f}  Total dp: {total_dp:+.1f}  Falling: {int(falling)}")
    print(f"  Pitch: {pitch_before:.1f} -> {pitch_after:.1f}")
    print(f"  Pre-spike mean |dp|: {pre_mean_dp:.4f} deg/frame")
    print(f"  Post-spike mean |dp|: {post_mean_dp:.4f} deg/frame")
    print(f"  Direction reversal: {'YES' if is_reversal else 'no'}")

    # Print spike values
    for idx, dp, dt_ms in group:
        falling_now = int(rows[idx].get('falling', 0))
        print(f"    frame {idx}: dp={dp:+8.3f}  dt={dt_ms:.1f}ms  fall={falling_now}  pitch={rows[idx]['pitch']:.1f}")

# Special analysis: the recurring 8.138 value
print(f"\n{'='*80}")
print(f"  RECURRING SPIKE VALUES")
print(f"{'='*80}")
from collections import Counter
spike_values = Counter()
for i, dp, dt in spikes:
    rounded = round(abs(dp), 3)
    spike_values[rounded] += 1

for val, count in spike_values.most_common(15):
    print(f"  |dp| = {val:.3f}: {count} times")

# Check if 8.138 is a specific number of mouseSteps
mouse_step = 0.084534
for val in [8.138, 5.510, 6.527, 7.629, 3.899, 4.323, 3.221]:
    pixels = val / mouse_step
    print(f"  {val:.3f} / {mouse_step:.6f} = {pixels:.2f} pixels")

# Analyze: do spikes happen at tick boundaries?
print(f"\n{'='*80}")
print(f"  TICK BOUNDARY ANALYSIS")
print(f"{'='*80}")
# Tick = 50ms interval. Check if spikes cluster at specific phases
for i, dp, dt_ms in spikes[:20]:
    t = rows[i]['time_ms']
    # Estimate tick phase (0-50ms within a tick)
    phase = t % 50.0
    falling = int(rows[i].get('falling', 0))
    print(f"  frame {i}: t={t:.1f}ms  tick_phase={phase:.1f}ms  |dp|={abs(dp):.3f}  fall={falling}")

# Analyze escalating velocity pattern before spikes
print(f"\n{'='*80}")
print(f"  VELOCITY ESCALATION BEFORE SPIKES")
print(f"{'='*80}")
# For each big spike (>5deg), show the pitch velocity in preceding 20 frames
big_spikes = [(i, dp, dt) for i, dp, dt in spikes if abs(dp) > 5.0]
for si, (idx, dp, dt_ms) in enumerate(big_spikes[:8]):
    print(f"\n  Spike at frame {idx}: dp={dp:+.3f}")
    start = max(1, idx-20)
    for j in range(start, min(N, idx+5)):
        d = rows[j]['pitch'] - rows[j-1]['pitch']
        t = rows[j]['time_ms'] - rows[j-1]['time_ms']
        vel = abs(d) / (t/1000) if t > 0.1 else 0
        marker = ">>>" if j == idx else "   "
        fall = int(rows[j].get('falling', 0))
        print(f"    {marker} {j}: dp={d:+8.4f} dt={t:.1f}ms vel={vel:>7.1f} fall={fall}")
