"""
Deep fall behavior comparison: bot vs human.
"""
import sys, io, csv, math
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

INSTANCE = r"C:\Users\Ghost\AppData\Roaming\PrismLauncher\instances\WaldinDastinner1.12.10\minecraft"
BOT_CSV   = INSTANCE + r"\camera_debug.csv"
HUMAN_CSV = INSTANCE + r"\camera_human.csv"

def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append({k: float(v) for k, v in r.items()})
    return rows

def wrap180(d):
    while d > 180: d -= 360
    while d < -180: d += 360
    return d

def analyze_falls(rows, label):
    N = len(rows)

    # Build deltas
    pitch_d = []
    yaw_d = []
    for i in range(1, N):
        pitch_d.append(rows[i]['pitch'] - rows[i-1]['pitch'])
        dy = rows[i]['yaw'] - rows[i-1]['yaw']
        pitch_d_last = pitch_d[-1]
        yaw_d.append(wrap180(dy))

    # Find fall episodes
    episodes = []
    in_fall = False
    fs = 0
    for i in range(N):
        if rows[i]['falling'] == 1 and not in_fall:
            in_fall = True; fs = i
        elif rows[i]['falling'] == 0 and in_fall:
            in_fall = False; episodes.append((fs, i-1))
    if in_fall: episodes.append((fs, N-1))

    print(f"\n{'='*75}")
    print(f"  {label}: {len(episodes)} fall episodes, {N} total frames")
    print(f"{'='*75}")

    # Categorize
    short = [(s,e) for s,e in episodes if (rows[e]['time_ms']-rows[s]['time_ms']) < 200]
    medium = [(s,e) for s,e in episodes if 200 <= (rows[e]['time_ms']-rows[s]['time_ms']) < 600]
    long_ = [(s,e) for s,e in episodes if (rows[e]['time_ms']-rows[s]['time_ms']) >= 600]
    print(f"  Short (<200ms): {len(short)}  Medium (200-600ms): {len(medium)}  Long (>600ms): {len(long_)}")

    # Aggregate metrics across significant falls (>200ms)
    sig_falls = medium + long_
    if not sig_falls:
        print("  No significant falls!")
        return

    all_reaction_ms = []
    all_pitch_range = []
    all_pitch_vel_mean = []
    all_pitch_accel_p95 = []
    all_reversal_pct = []
    all_max_pitch_delta = []
    all_pitch_at_end = []

    print(f"\n  --- DETAILED FALLS (>200ms) ---")
    for fi, (fs, fe) in enumerate(sig_falls):
        dur_ms = rows[fe]['time_ms'] - rows[fs]['time_ms']
        base_pitch = rows[fs]['pitch']

        pitches = [rows[i]['pitch'] for i in range(fs, fe+1)]
        pitch_range = max(pitches) - min(pitches)

        # Pitch velocity during fall
        pvs = []
        for i in range(fs, min(fe+1, N)):
            dt_s = (rows[i]['time_ms'] - rows[max(i-1,0)]['time_ms']) / 1000.0
            if i > fs and 0.0001 < dt_s < 0.1:
                dp = rows[i]['pitch'] - rows[i-1]['pitch']
                pvs.append(dp / dt_s)

        # Pitch acceleration
        accels = []
        for i in range(1, len(pvs)):
            dt_s = (rows[fs+i+1]['time_ms'] - rows[fs+i]['time_ms']) / 1000.0 if fs+i+1 < N else 0.003
            if 0.0001 < dt_s < 0.1:
                accels.append(abs((pvs[i] - pvs[i-1]) / dt_s))

        # Reaction time (pitch moves >0.5 deg from start)
        react_ms = -1
        for i in range(fs, fe+1):
            if abs(rows[i]['pitch'] - base_pitch) > 0.5:
                react_ms = rows[i]['time_ms'] - rows[fs]['time_ms']
                break

        # Reversals during fall
        fall_pd = [rows[i]['pitch'] - rows[i-1]['pitch'] for i in range(fs+1, fe+1)]
        rev = 0
        total_pairs = 0
        for j in range(1, len(fall_pd)):
            if abs(fall_pd[j]) > 0.01 and abs(fall_pd[j-1]) > 0.01:
                total_pairs += 1
                if fall_pd[j] * fall_pd[j-1] < 0:
                    rev += 1

        max_pd = max(abs(d) for d in fall_pd) if fall_pd else 0
        end_pitch = rows[fe]['pitch']

        all_reaction_ms.append(react_ms)
        all_pitch_range.append(pitch_range)
        all_pitch_vel_mean.append(sum(abs(v) for v in pvs) / len(pvs) if pvs else 0)
        all_pitch_accel_p95.append(sorted(accels)[int(len(accels)*0.95)] if accels else 0)
        all_reversal_pct.append(rev / total_pairs * 100 if total_pairs > 0 else 0)
        all_max_pitch_delta.append(max_pd)
        all_pitch_at_end.append(end_pitch)

        react_str = f"{react_ms:.0f}ms" if react_ms >= 0 else "NONE"
        rev_str = f"{rev}/{total_pairs}" if total_pairs > 0 else "0/0"

        if fi < 15 or dur_ms > 500:  # show first 15 + all long
            print(f"\n  Fall #{fi+1}: frames {fs}-{fe} ({dur_ms:.0f}ms)")
            print(f"    Pitch: {base_pitch:.1f} -> {end_pitch:.1f} (range: {pitch_range:.1f})")
            print(f"    Reaction: {react_str}  Reversals: {rev_str}  Max |dp|: {max_pd:.3f}")
            print(f"    Pitch vel mean: {sum(abs(v) for v in pvs)/len(pvs):.1f}" if pvs else "")

            # Pitch profile
            step = max(1, (fe - fs) // 12)
            print(f"    Profile:", end="")
            for i in range(fs, fe+1, step):
                t_rel = rows[i]['time_ms'] - rows[fs]['time_ms']
                print(f" {t_rel:.0f}:{rows[i]['pitch']:.1f}", end="")
            print()

    # Aggregate stats
    print(f"\n  --- AGGREGATE ({len(sig_falls)} significant falls) ---")

    reacted = [r for r in all_reaction_ms if r >= 0]
    no_react = sum(1 for r in all_reaction_ms if r < 0)
    print(f"  Reaction time: mean={sum(reacted)/len(reacted):.0f}ms  min={min(reacted):.0f}ms  max={max(reacted):.0f}ms" if reacted else "  No reactions")
    print(f"  No-reaction falls: {no_react}/{len(sig_falls)}")

    print(f"  Pitch range: mean={sum(all_pitch_range)/len(all_pitch_range):.1f}  max={max(all_pitch_range):.1f}")

    mean_vel = sum(all_pitch_vel_mean)/len(all_pitch_vel_mean)
    print(f"  Pitch vel during falls: mean={mean_vel:.1f} deg/s")

    mean_accel = sum(all_pitch_accel_p95)/len(all_pitch_accel_p95)
    print(f"  Pitch accel p95: mean={mean_accel:.0f} deg/s2")

    mean_rev = sum(all_reversal_pct)/len(all_reversal_pct)
    print(f"  Pitch reversal %: mean={mean_rev:.1f}%")

    mean_max_pd = sum(all_max_pitch_delta)/len(all_max_pitch_delta)
    print(f"  Max |pitch delta|/frame: mean={mean_max_pd:.3f} deg")

    # Pre-fall and post-fall behavior
    print(f"\n  --- PRE/POST FALL ---")
    pre_pitch_movement = []
    post_pitch_recovery = []
    for fs, fe in sig_falls:
        # Pre-fall: pitch movement in 20 frames before
        pre_start = max(0, fs - 20)
        pre_pd = sum(abs(rows[i]['pitch'] - rows[i-1]['pitch']) for i in range(pre_start+1, fs))
        pre_pitch_movement.append(pre_pd)

        # Post-fall: how quickly pitch returns to pre-fall value
        post_end = min(N-1, fe + 40)
        if post_end > fe:
            fall_pitch_change = rows[fe]['pitch'] - rows[fs]['pitch']
            recovery = rows[post_end]['pitch'] - rows[fe]['pitch']
            # If fell and looked down, recovery is positive (looking back up)
            post_pitch_recovery.append(recovery)

    print(f"  Pre-fall pitch activity (20 frames): mean={sum(pre_pitch_movement)/len(pre_pitch_movement):.2f} deg")
    print(f"  Post-fall pitch recovery (40 frames): mean={sum(post_pitch_recovery)/len(post_pitch_recovery):.1f} deg")

    # During-fall: frame-by-frame pitch delta stats
    print(f"\n  --- PITCH DELTAS DURING FALLS ---")
    fall_deltas = []
    for fs, fe in sig_falls:
        for i in range(fs+1, fe+1):
            fall_deltas.append(rows[i]['pitch'] - rows[i-1]['pitch'])

    if fall_deltas:
        nz = [d for d in fall_deltas if abs(d) > 0.001]
        zero_pct = (len(fall_deltas) - len(nz)) / len(fall_deltas) * 100
        print(f"  Total fall pitch deltas: {len(fall_deltas)}")
        print(f"  Zero deltas: {zero_pct:.1f}%")
        if nz:
            pos = sum(1 for d in nz if d > 0)
            neg = sum(1 for d in nz if d < 0)
            print(f"  Positive: {pos} ({pos/len(nz)*100:.1f}%)  Negative: {neg} ({neg/len(nz)*100:.1f}%)")
            abs_nz = sorted([abs(d) for d in nz])
            print(f"  |delta| mean={sum(abs_nz)/len(abs_nz):.4f}  median={abs_nz[len(abs_nz)//2]:.4f}  p95={abs_nz[int(len(abs_nz)*0.95)]:.4f}  max={abs_nz[-1]:.4f}")

print("Loading bot...")
bot = load(BOT_CSV)
print("Loading human...")
human = load(HUMAN_CSV)

analyze_falls(bot, "BOT")
analyze_falls(human, "HUMAN")
