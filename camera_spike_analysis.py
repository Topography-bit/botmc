"""Find what causes large pitch deltas — dt spikes or something else."""
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

print(f"Total frames: {N}")
print(f"\n=== ALL FRAMES WITH |pitch delta| > 1.5 deg ===")
print(f"{'frame':>7} {'dt_ms':>7} {'|dp|':>8} {'pitch':>8} {'prev_p':>8} {'falling':>7} {'yaw_vel':>8} {'pitch_v':>8}")

big_deltas = []
for i in range(1, N):
    dp = rows[i]['pitch'] - rows[i-1]['pitch']
    dt_ms = rows[i]['time_ms'] - rows[i-1]['time_ms']
    if abs(dp) > 1.5:
        falling = rows[i].get('falling', 0)
        dy = rows[i]['yaw'] - rows[i-1]['yaw']
        # wrap yaw delta
        while dy > 180: dy -= 360
        while dy < -180: dy += 360
        yaw_vel = dy / (dt_ms/1000.0) if dt_ms > 0.1 else 0
        pitch_vel = dp / (dt_ms/1000.0) if dt_ms > 0.1 else 0
        print(f"{i:>7} {dt_ms:>7.1f} {abs(dp):>8.3f} {rows[i]['pitch']:>8.1f} {rows[i-1]['pitch']:>8.1f} {int(falling):>7} {yaw_vel:>8.0f} {pitch_vel:>8.0f}")
        big_deltas.append((i, dt_ms, abs(dp), falling))

print(f"\nTotal big pitch deltas: {len(big_deltas)}")

# Correlate with dt
if big_deltas:
    dt_spike = [b for b in big_deltas if b[1] > 10]
    dt_normal = [b for b in big_deltas if b[1] <= 10]
    fall_spikes = [b for b in big_deltas if b[3] == 1]
    normal_spikes = [b for b in big_deltas if b[3] == 0]
    print(f"  During dt > 10ms: {len(dt_spike)}")
    print(f"  During dt <= 10ms: {len(dt_normal)}")
    print(f"  During fall: {len(fall_spikes)}")
    print(f"  During normal: {len(normal_spikes)}")

# Also show pitch velocity distribution during falls
print(f"\n=== PITCH VELOCITY DURING FALLS ===")
fall_vels = []
for i in range(1, N):
    if rows[i].get('falling', 0) == 1:
        dp = rows[i]['pitch'] - rows[i-1]['pitch']
        dt_ms = rows[i]['time_ms'] - rows[i-1]['time_ms']
        if dt_ms > 0.1:
            fall_vels.append(abs(dp) / (dt_ms / 1000.0))

if fall_vels:
    fall_vels.sort()
    print(f"  Count: {len(fall_vels)}")
    print(f"  Mean: {sum(fall_vels)/len(fall_vels):.1f} deg/s")
    print(f"  Median: {fall_vels[len(fall_vels)//2]:.1f} deg/s")
    print(f"  p90: {fall_vels[int(len(fall_vels)*0.9)]:.1f} deg/s")
    print(f"  p95: {fall_vels[int(len(fall_vels)*0.95)]:.1f} deg/s")
    print(f"  p99: {fall_vels[int(len(fall_vels)*0.99)]:.1f} deg/s")
    print(f"  Max: {fall_vels[-1]:.1f} deg/s")

# Show context around biggest spikes
print(f"\n=== CONTEXT AROUND TOP 5 BIGGEST PITCH SPIKES ===")
big_deltas.sort(key=lambda x: -x[2])
for rank, (idx, dt_ms, dp, falling) in enumerate(big_deltas[:5]):
    print(f"\n--- Spike #{rank+1}: frame {idx}, |dp|={dp:.3f}, dt={dt_ms:.1f}ms, falling={int(falling)} ---")
    start = max(0, idx - 5)
    end = min(N, idx + 6)
    for j in range(start, end):
        marker = " >>>" if j == idx else "    "
        dp_j = rows[j]['pitch'] - rows[j-1]['pitch'] if j > 0 else 0
        dt_j = rows[j]['time_ms'] - rows[j-1]['time_ms'] if j > 0 else 0
        f_j = int(rows[j].get('falling', 0))
        print(f"  {marker} frame {j}: pitch={rows[j]['pitch']:>8.2f}  dp={dp_j:>+8.3f}  dt={dt_j:>6.1f}ms  fall={f_j}")
