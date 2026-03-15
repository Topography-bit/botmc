"""
DEEP bot vs human camera comparison -- every possible metric.
"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import csv
import math
import sys
from collections import Counter

INSTANCE = r"C:\Users\Ghost\AppData\Roaming\PrismLauncher\instances\WaldinDastinner1.12.10\minecraft"
BOT_CSV   = INSTANCE + r"\camera_debug.csv"
HUMAN_CSV = INSTANCE + r"\camera_human.csv"

def load(path):
    rows = []
    with open(path, 'r') as f:
        reader = csv.DictReader(f)
        for r in reader:
            row = {}
            for k, v in r.items():
                try: row[k] = float(v)
                except: row[k] = v
            rows.append(row)
    return rows

def wrap180(d):
    while d > 180: d -= 360
    while d < -180: d += 360
    return d

def percentile(arr, p):
    if not arr: return 0
    s = sorted(arr)
    idx = int(len(s) * p / 100.0)
    idx = min(idx, len(s)-1)
    return s[idx]

def mean(arr):
    return sum(arr)/len(arr) if arr else 0

def median(arr):
    if not arr: return 0
    s = sorted(arr)
    return s[len(s)//2]

def stdev(arr):
    if len(arr) < 2: return 0
    m = mean(arr)
    return math.sqrt(sum((x-m)**2 for x in arr) / (len(arr)-1))

def autocorr_lag(deltas, lag=1):
    n = len(deltas)
    if n < lag+2: return 0
    m = mean(deltas)
    var = sum((d-m)**2 for d in deltas) / n
    if var < 1e-15: return 0
    cov = sum((deltas[i]-m)*(deltas[i-lag]-m) for i in range(lag, n)) / (n - lag)
    return cov / var

def run_lengths(deltas):
    runs = []
    cur = 1
    for i in range(1, len(deltas)):
        if deltas[i] * deltas[i-1] > 0:
            cur += 1
        else:
            runs.append(cur)
            cur = 1
    runs.append(cur)
    return runs

def fft_peak(deltas, fps):
    """Find dominant frequency via zero-crossing or simple DFT."""
    N = len(deltas)
    if N < 64: return 0, 0
    # Remove mean
    m = mean(deltas)
    x = [d - m for d in deltas]
    # Simple DFT for frequencies 0.1 - fps/2
    best_freq = 0
    best_power = 0
    n_freqs = min(500, N//2)
    for fi in range(1, n_freqs):
        freq = fi * fps / N
        if freq < 0.05 or freq > fps/2: continue
        re = sum(x[t] * math.cos(2*math.pi*fi*t/N) for t in range(N))
        im = sum(x[t] * math.sin(2*math.pi*fi*t/N) for t in range(N))
        power = re*re + im*im
        if power > best_power:
            best_power = power
            best_freq = freq
    return best_freq, best_power

def analyze_deep(rows, label):
    N = len(rows)
    if N < 20:
        print(f"  [{label}] слишком мало фреймов: {N}")
        return {}
    dur_s = (rows[-1]['time_ms'] - rows[0]['time_ms']) / 1000.0
    fps = (N-1) / dur_s if dur_s > 0 else 60

    # -- Deltas --
    yaw_d = []
    pitch_d = []
    dts_ms = []
    for i in range(1, N):
        dy = wrap180(rows[i]['yaw'] - rows[i-1]['yaw'])
        dp = rows[i]['pitch'] - rows[i-1]['pitch']
        dt = rows[i]['time_ms'] - rows[i-1]['time_ms']
        yaw_d.append(dy)
        pitch_d.append(dp)
        dts_ms.append(dt)

    # -- Velocities (from deltas, not CSV — more honest for human) --
    yaw_vels_calc = []
    pitch_vels_calc = []
    for i in range(len(yaw_d)):
        dt_s = dts_ms[i] / 1000.0
        if dt_s > 0.0001:
            yaw_vels_calc.append(yaw_d[i] / dt_s)
            pitch_vels_calc.append(pitch_d[i] / dt_s)

    # -- Accelerations --
    yaw_accels = []
    pitch_accels = []
    for i in range(1, len(yaw_vels_calc)):
        dt_s = dts_ms[i] / 1000.0 if i < len(dts_ms) else dts_ms[-1] / 1000.0
        if 0.0001 < dt_s < 0.2:
            yaw_accels.append((yaw_vels_calc[i] - yaw_vels_calc[i-1]) / dt_s)
            pitch_accels.append((pitch_vels_calc[i] - pitch_vels_calc[i-1]) / dt_s)

    # -- Jerk (derivative of acceleration) --
    yaw_jerks = []
    pitch_jerks = []
    for i in range(1, len(yaw_accels)):
        dt_s = dts_ms[i+1] / 1000.0 if i+1 < len(dts_ms) else dts_ms[-1] / 1000.0
        if dt_s > 0.0001:
            yaw_jerks.append((yaw_accels[i] - yaw_accels[i-1]) / dt_s)
            pitch_jerks.append((pitch_accels[i] - pitch_accels[i-1]) / dt_s)

    R = {}
    R['label'] = label
    R['frames'] = N
    R['duration_s'] = dur_s
    R['fps'] = fps

    # ==========================================================
    # 1. FRAME TIMING
    # ==========================================================
    R['dt_mean'] = mean(dts_ms)
    R['dt_stdev'] = stdev(dts_ms)
    R['dt_min'] = min(dts_ms) if dts_ms else 0
    R['dt_max'] = max(dts_ms) if dts_ms else 0
    # Frame time consistency
    dt_outliers = sum(1 for d in dts_ms if d > mean(dts_ms) * 2 or d < mean(dts_ms) * 0.3)
    R['dt_outliers_pct'] = dt_outliers / len(dts_ms) * 100

    # ==========================================================
    # 2. VELOCITY
    # ==========================================================
    abs_yv = [abs(v) for v in yaw_vels_calc]
    abs_pv = [abs(v) for v in pitch_vels_calc]
    total_vel = [math.sqrt(yaw_vels_calc[i]**2 + pitch_vels_calc[i]**2) for i in range(len(yaw_vels_calc))]

    R['yaw_vel_mean'] = mean(abs_yv)
    R['yaw_vel_median'] = median(abs_yv)
    R['yaw_vel_p90'] = percentile(abs_yv, 90)
    R['yaw_vel_p99'] = percentile(abs_yv, 99)
    R['yaw_vel_max'] = max(abs_yv) if abs_yv else 0
    R['yaw_vel_stdev'] = stdev(yaw_vels_calc)

    R['pitch_vel_mean'] = mean(abs_pv)
    R['pitch_vel_median'] = median(abs_pv)
    R['pitch_vel_p90'] = percentile(abs_pv, 90)
    R['pitch_vel_p99'] = percentile(abs_pv, 99)
    R['pitch_vel_max'] = max(abs_pv) if abs_pv else 0
    R['pitch_vel_stdev'] = stdev(pitch_vels_calc)

    R['total_vel_mean'] = mean(total_vel)
    R['total_vel_median'] = median(total_vel)
    R['total_vel_p90'] = percentile(total_vel, 90)
    R['total_vel_p99'] = percentile(total_vel, 99)

    # ==========================================================
    # 3. STATIC FRAMES
    # ==========================================================
    yaw_static = sum(1 for d in yaw_d if abs(d) < 0.001)
    pitch_static = sum(1 for d in pitch_d if abs(d) < 0.001)
    both_static = sum(1 for i in range(len(yaw_d)) if abs(yaw_d[i]) < 0.001 and abs(pitch_d[i]) < 0.001)
    R['yaw_static_pct'] = yaw_static / len(yaw_d) * 100
    R['pitch_static_pct'] = pitch_static / len(pitch_d) * 100
    R['both_static_pct'] = both_static / len(yaw_d) * 100

    # Static run lengths (how many consecutive zero-movement frames)
    static_runs_y = []
    cur = 0
    for d in yaw_d:
        if abs(d) < 0.001:
            cur += 1
        else:
            if cur > 0: static_runs_y.append(cur)
            cur = 0
    if cur > 0: static_runs_y.append(cur)
    R['yaw_static_run_mean'] = mean(static_runs_y) if static_runs_y else 0
    R['yaw_static_run_max'] = max(static_runs_y) if static_runs_y else 0

    static_runs_p = []
    cur = 0
    for d in pitch_d:
        if abs(d) < 0.001:
            cur += 1
        else:
            if cur > 0: static_runs_p.append(cur)
            cur = 0
    if cur > 0: static_runs_p.append(cur)
    R['pitch_static_run_mean'] = mean(static_runs_p) if static_runs_p else 0
    R['pitch_static_run_max'] = max(static_runs_p) if static_runs_p else 0

    # ==========================================================
    # 4. DELTA SIZE DISTRIBUTION
    # ==========================================================
    def dist(deltas):
        t = len(deltas)
        return {
            'zero': sum(1 for d in deltas if abs(d) < 0.001) / t * 100,
            '<0.05': sum(1 for d in deltas if 0.001 <= abs(d) < 0.05) / t * 100,
            '0.05-0.1': sum(1 for d in deltas if 0.05 <= abs(d) < 0.1) / t * 100,
            '0.1-0.3': sum(1 for d in deltas if 0.1 <= abs(d) < 0.3) / t * 100,
            '0.3-1.0': sum(1 for d in deltas if 0.3 <= abs(d) < 1.0) / t * 100,
            '1.0-3.0': sum(1 for d in deltas if 1.0 <= abs(d) < 3.0) / t * 100,
            '>=3.0': sum(1 for d in deltas if abs(d) >= 3.0) / t * 100,
        }
    R['yaw_dist'] = dist(yaw_d)
    R['pitch_dist'] = dist(pitch_d)

    # ==========================================================
    # 5. DIRECTION REVERSALS
    # ==========================================================
    def reversals(deltas, thresh=0.01):
        rev = 0
        total = 0
        for i in range(1, len(deltas)):
            if abs(deltas[i]) > thresh and abs(deltas[i-1]) > thresh:
                total += 1
                if deltas[i] * deltas[i-1] < 0:
                    rev += 1
        return (rev, total)

    yr, yt = reversals(yaw_d)
    pr, pt = reversals(pitch_d)
    R['yaw_reversal_pct'] = yr / yt * 100 if yt > 0 else 0
    R['pitch_reversal_pct'] = pr / pt * 100 if pt > 0 else 0
    R['yaw_reversal_count'] = yr
    R['pitch_reversal_count'] = pr

    # ==========================================================
    # 6. SAME-DIRECTION RUN LENGTHS
    # ==========================================================
    yr_runs = run_lengths(yaw_d)
    pr_runs = run_lengths(pitch_d)
    R['yaw_run_mean'] = mean(yr_runs)
    R['yaw_run_median'] = median(yr_runs)
    R['yaw_run_max'] = max(yr_runs) if yr_runs else 0
    R['pitch_run_mean'] = mean(pr_runs)
    R['pitch_run_median'] = median(pr_runs)
    R['pitch_run_max'] = max(pr_runs) if pr_runs else 0

    # ==========================================================
    # 7. AUTOCORRELATION (lag 1-5)
    # ==========================================================
    for lag in [1, 2, 3, 5]:
        R[f'yaw_autocorr_lag{lag}'] = autocorr_lag(yaw_d, lag)
        R[f'pitch_autocorr_lag{lag}'] = autocorr_lag(pitch_d, lag)

    # Velocity autocorrelation
    R['yaw_vel_autocorr'] = autocorr_lag(yaw_vels_calc, 1)
    R['pitch_vel_autocorr'] = autocorr_lag(pitch_vels_calc, 1)

    # ==========================================================
    # 8. ACCELERATION (JERKINESS)
    # ==========================================================
    abs_ya = [abs(a) for a in yaw_accels]
    abs_pa = [abs(a) for a in pitch_accels]
    R['yaw_accel_mean'] = mean(abs_ya)
    R['yaw_accel_p90'] = percentile(abs_ya, 90)
    R['yaw_accel_p95'] = percentile(abs_ya, 95)
    R['yaw_accel_p99'] = percentile(abs_ya, 99)
    R['yaw_accel_max'] = max(abs_ya) if abs_ya else 0

    R['pitch_accel_mean'] = mean(abs_pa)
    R['pitch_accel_p90'] = percentile(abs_pa, 90)
    R['pitch_accel_p95'] = percentile(abs_pa, 95)
    R['pitch_accel_p99'] = percentile(abs_pa, 99)
    R['pitch_accel_max'] = max(abs_pa) if abs_pa else 0

    # ==========================================================
    # 9. JERK (derivative of acceleration)
    # ==========================================================
    abs_yj = [abs(j) for j in yaw_jerks]
    abs_pj = [abs(j) for j in pitch_jerks]
    R['yaw_jerk_mean'] = mean(abs_yj)
    R['yaw_jerk_p95'] = percentile(abs_yj, 95)
    R['pitch_jerk_mean'] = mean(abs_pj)
    R['pitch_jerk_p95'] = percentile(abs_pj, 95)

    # ==========================================================
    # 10. QUANTIZATION ANALYSIS
    # ==========================================================
    best_step = 0
    best_match = 0
    for sens_pct in range(1, 201):
        s = sens_pct / 200.0
        f = s * 0.6 + 0.2
        step = f * f * f * 8.0 * 0.15
        if step < 0.001: continue
        clean = sum(1 for d in yaw_d if abs(d) < 0.0001 or abs(abs(d)/step - round(abs(d)/step)) < 0.015)
        pct = clean / len(yaw_d)
        if pct > best_match:
            best_match = pct
            best_step = step
    R['quant_step'] = best_step

    def grid_pct(deltas, step):
        if step < 0.001: return 0
        clean = sum(1 for d in deltas if abs(d) < 0.0001 or abs(abs(d)/step - round(abs(d)/step)) < 0.015)
        return clean / len(deltas) * 100
    R['yaw_on_grid_pct'] = grid_pct(yaw_d, best_step)
    R['pitch_on_grid_pct'] = grid_pct(pitch_d, best_step)

    # Off-grid deltas — what are they?
    off_grid_yaw = [d for d in yaw_d if abs(d) > 0.0001 and (best_step < 0.001 or abs(abs(d)/best_step - round(abs(d)/best_step)) >= 0.015)]
    if off_grid_yaw:
        R['off_grid_yaw_mean'] = mean([abs(d) for d in off_grid_yaw])
        R['off_grid_yaw_count'] = len(off_grid_yaw)
    else:
        R['off_grid_yaw_mean'] = 0
        R['off_grid_yaw_count'] = 0

    # ==========================================================
    # 11. ALTERNATING PATTERN (±1px dithering)
    # ==========================================================
    alt_yaw = 0
    alt_pitch = 0
    if best_step > 0.001:
        for i in range(1, len(yaw_d)):
            d1 = yaw_d[i-1]; d2 = yaw_d[i]
            if abs(abs(d1) - best_step) < 0.01 and abs(abs(d2) - best_step) < 0.01 and d1*d2 < 0:
                alt_yaw += 1
        for i in range(1, len(pitch_d)):
            d1 = pitch_d[i-1]; d2 = pitch_d[i]
            if abs(abs(d1) - best_step) < 0.01 and abs(abs(d2) - best_step) < 0.01 and d1*d2 < 0:
                alt_pitch += 1
    R['alt_yaw_pct'] = alt_yaw / max(1, len(yaw_d)-1) * 100
    R['alt_pitch_pct'] = alt_pitch / max(1, len(pitch_d)-1) * 100

    # ==========================================================
    # 12. MOVEMENT CONTINUITY (gaps between movements)
    # ==========================================================
    # How long are pauses between movements?
    yaw_pause_lens = []
    cur_pause = 0
    for d in yaw_d:
        if abs(d) < 0.001:
            cur_pause += 1
        else:
            if cur_pause > 0:
                yaw_pause_lens.append(cur_pause)
            cur_pause = 0
    R['yaw_pause_mean_frames'] = mean(yaw_pause_lens) if yaw_pause_lens else 0
    R['yaw_pauses_count'] = len(yaw_pause_lens)

    # ==========================================================
    # 13. FALLS ANALYSIS
    # ==========================================================
    fall_frames = [i for i in range(N) if rows[i]['falling'] == 1]
    R['fall_frame_count'] = len(fall_frames)
    R['fall_pct'] = len(fall_frames) / N * 100

    # Fall episodes
    episodes = []
    in_fall = False
    fs = 0
    for i in range(N):
        if rows[i]['falling'] == 1 and not in_fall:
            in_fall = True; fs = i
        elif rows[i]['falling'] == 0 and in_fall:
            in_fall = False; episodes.append((fs, i-1))
    if in_fall: episodes.append((fs, N-1))
    R['fall_episodes'] = len(episodes)

    # Per-fall pitch behavior
    fall_pitch_ranges = []
    fall_pitch_reactions = []  # how fast pitch starts moving after fall
    fall_durations = []
    for s, e in episodes:
        dur = rows[e]['time_ms'] - rows[s]['time_ms']
        fall_durations.append(dur)
        if dur < 100: continue
        pitches = [rows[i]['pitch'] for i in range(s, e+1)]
        fall_pitch_ranges.append(max(pitches) - min(pitches))
        # Reaction: when pitch moves >0.5 deg from start
        base_p = rows[s]['pitch']
        for i in range(s, e+1):
            if abs(rows[i]['pitch'] - base_p) > 0.5:
                fall_pitch_reactions.append(rows[i]['time_ms'] - rows[s]['time_ms'])
                break
        else:
            fall_pitch_reactions.append(-1)  # no reaction

    R['fall_dur_mean'] = mean(fall_durations) if fall_durations else 0
    R['fall_pitch_range_mean'] = mean(fall_pitch_ranges) if fall_pitch_ranges else 0
    R['fall_pitch_range_max'] = max(fall_pitch_ranges) if fall_pitch_ranges else 0
    reacted = [r for r in fall_pitch_reactions if r >= 0]
    R['fall_reaction_mean_ms'] = mean(reacted) if reacted else -1
    R['fall_no_reaction_count'] = sum(1 for r in fall_pitch_reactions if r < 0)

    # Pitch during falls vs normal
    fall_set = set()
    for s, e in episodes:
        for i in range(s, e+1): fall_set.add(i)
    fall_pv = [abs(pitch_vels_calc[i]) for i in range(len(pitch_vels_calc)) if i+1 in fall_set]
    normal_pv = [abs(pitch_vels_calc[i]) for i in range(len(pitch_vels_calc)) if i+1 not in fall_set]
    R['fall_pitch_vel_mean'] = mean(fall_pv) if fall_pv else 0
    R['normal_pitch_vel_mean'] = mean(normal_pv) if normal_pv else 0

    # ==========================================================
    # 14. FREQUENCY ANALYSIS
    # ==========================================================
    # Dominant yaw frequency
    yf, yp = fft_peak(yaw_d, fps)
    pf, pp = fft_peak(pitch_d, fps)
    R['yaw_dominant_freq'] = yf
    R['pitch_dominant_freq'] = pf

    # ==========================================================
    # 15. PITCH RANGE & YAW RANGE
    # ==========================================================
    all_pitch = [rows[i]['pitch'] for i in range(N)]
    all_yaw = [rows[i]['yaw'] for i in range(N)]
    R['pitch_min'] = min(all_pitch)
    R['pitch_max'] = max(all_pitch)
    R['pitch_range'] = max(all_pitch) - min(all_pitch)
    R['pitch_mean'] = mean(all_pitch)
    R['yaw_total_travel'] = sum(abs(d) for d in yaw_d)
    R['pitch_total_travel'] = sum(abs(d) for d in pitch_d)

    # ==========================================================
    # 16. YAW-PITCH CORRELATION
    # ==========================================================
    # Do yaw and pitch move together or independently?
    if len(yaw_d) > 10:
        ym = mean(yaw_d); pm = mean(pitch_d)
        cov = sum((yaw_d[i]-ym)*(pitch_d[i]-pm) for i in range(len(yaw_d))) / len(yaw_d)
        sy = stdev(yaw_d); sp = stdev(pitch_d)
        R['yaw_pitch_correlation'] = cov / (sy * sp) if sy > 0 and sp > 0 else 0
    else:
        R['yaw_pitch_correlation'] = 0

    # ==========================================================
    # 17. MICRO-MOVEMENT ANALYSIS (sub-pixel)
    # ==========================================================
    if best_step > 0.001:
        sub_pixel_yaw = sum(1 for d in yaw_d if 0 < abs(d) < best_step * 0.5)
        sub_pixel_pitch = sum(1 for d in pitch_d if 0 < abs(d) < best_step * 0.5)
        R['sub_pixel_yaw_pct'] = sub_pixel_yaw / len(yaw_d) * 100
        R['sub_pixel_pitch_pct'] = sub_pixel_pitch / len(pitch_d) * 100
    else:
        R['sub_pixel_yaw_pct'] = 0
        R['sub_pixel_pitch_pct'] = 0

    # ==========================================================
    # 18. MOVEMENT BURSTS (fast movement segments)
    # ==========================================================
    # Count segments where total velocity > 100 deg/s
    fast_frames = sum(1 for v in total_vel if v > 100)
    R['fast_frames_pct'] = fast_frames / len(total_vel) * 100 if total_vel else 0
    very_fast = sum(1 for v in total_vel if v > 300)
    R['very_fast_frames_pct'] = very_fast / len(total_vel) * 100 if total_vel else 0

    # ==========================================================
    # 19. SMOOTHNESS INDEX
    # ==========================================================
    # Ratio of acceleration RMS to velocity RMS — lower = smoother
    if abs_yv and abs_ya:
        R['yaw_smoothness'] = mean(abs_ya) / max(mean(abs_yv), 0.01)
        R['pitch_smoothness'] = mean(abs_pa) / max(mean(abs_pv), 0.01)
    else:
        R['yaw_smoothness'] = 0
        R['pitch_smoothness'] = 0

    # ==========================================================
    # 20. DELTA SIGN RATIO
    # ==========================================================
    yaw_pos = sum(1 for d in yaw_d if d > 0.001)
    yaw_neg = sum(1 for d in yaw_d if d < -0.001)
    pitch_pos = sum(1 for d in pitch_d if d > 0.001)
    pitch_neg = sum(1 for d in pitch_d if d < -0.001)
    R['yaw_pos_neg_ratio'] = yaw_pos / max(yaw_neg, 1)
    R['pitch_pos_neg_ratio'] = pitch_pos / max(pitch_neg, 1)

    return R

def print_deep(B, H):
    def row(name, bv, hv, fmt=".1f", unit=""):
        bs = f"{bv:{fmt}}{unit}"
        hs = f"{hv:{fmt}}{unit}"
        # Rating
        if isinstance(bv, (int, float)) and isinstance(hv, (int, float)):
            if hv == 0 and bv == 0:
                mark = "  ="
            elif hv == 0:
                mark = " !" if abs(bv) > 1 else "  ="
            else:
                ratio = bv / hv if hv != 0 else 999
                if 0.75 <= ratio <= 1.33:
                    mark = "  OK"
                elif 0.5 <= ratio <= 2.0:
                    mark = "  ~"
                else:
                    mark = " !!"
        else:
            mark = ""
        print(f"  {name:<35s} {bs:>14s}  {hs:>14s}{mark}")

    def header(title):
        print(f"\n{'-'*72}")
        print(f"  {title}")
        print(f"{'-'*72}")
        print(f"  {'':35s} {'БОТ':>14s}  {'ЧЕЛОВЕК':>14s}")

    print(f"\n{'='*72}")
    print(f"  ГЛУБОКИЙ АНАЛИЗ: БОТ vs ЧЕЛОВЕК")
    print(f"{'='*72}")

    header("1. ОБЩЕЕ")
    row("Фреймы", B['frames'], H['frames'], "d")
    row("Длительность", B['duration_s'], H['duration_s'], ".1f", "s")
    row("FPS", B['fps'], H['fps'], ".1f")
    row("dt mean", B['dt_mean'], H['dt_mean'], ".2f", "ms")
    row("dt stdev", B['dt_stdev'], H['dt_stdev'], ".2f", "ms")
    row("dt min", B['dt_min'], H['dt_min'], ".2f", "ms")
    row("dt max", B['dt_max'], H['dt_max'], ".1f", "ms")
    row("dt outliers", B['dt_outliers_pct'], H['dt_outliers_pct'], ".2f", "%")

    header("2. СКОРОСТЬ (deg/s)")
    row("Yaw vel mean", B['yaw_vel_mean'], H['yaw_vel_mean'], ".1f")
    row("Yaw vel median", B['yaw_vel_median'], H['yaw_vel_median'], ".1f")
    row("Yaw vel p90", B['yaw_vel_p90'], H['yaw_vel_p90'], ".0f")
    row("Yaw vel p99", B['yaw_vel_p99'], H['yaw_vel_p99'], ".0f")
    row("Yaw vel max", B['yaw_vel_max'], H['yaw_vel_max'], ".0f")
    row("Yaw vel stdev", B['yaw_vel_stdev'], H['yaw_vel_stdev'], ".1f")
    print()
    row("Pitch vel mean", B['pitch_vel_mean'], H['pitch_vel_mean'], ".1f")
    row("Pitch vel median", B['pitch_vel_median'], H['pitch_vel_median'], ".1f")
    row("Pitch vel p90", B['pitch_vel_p90'], H['pitch_vel_p90'], ".0f")
    row("Pitch vel p99", B['pitch_vel_p99'], H['pitch_vel_p99'], ".0f")
    row("Pitch vel max", B['pitch_vel_max'], H['pitch_vel_max'], ".0f")
    row("Pitch vel stdev", B['pitch_vel_stdev'], H['pitch_vel_stdev'], ".1f")
    print()
    row("Total vel mean", B['total_vel_mean'], H['total_vel_mean'], ".1f")
    row("Total vel median", B['total_vel_median'], H['total_vel_median'], ".1f")
    row("Total vel p90", B['total_vel_p90'], H['total_vel_p90'], ".0f")
    row("Total vel p99", B['total_vel_p99'], H['total_vel_p99'], ".0f")

    header("3. СТАТИКА")
    row("Yaw static", B['yaw_static_pct'], H['yaw_static_pct'], ".1f", "%")
    row("Pitch static", B['pitch_static_pct'], H['pitch_static_pct'], ".1f", "%")
    row("Both static", B['both_static_pct'], H['both_static_pct'], ".1f", "%")
    row("Yaw static run mean", B['yaw_static_run_mean'], H['yaw_static_run_mean'], ".1f", " frames")
    row("Yaw static run max", B['yaw_static_run_max'], H['yaw_static_run_max'], "d", " frames")
    row("Pitch static run mean", B['pitch_static_run_mean'], H['pitch_static_run_mean'], ".1f", " frames")
    row("Pitch static run max", B['pitch_static_run_max'], H['pitch_static_run_max'], "d", " frames")

    header("4. РАСПРЕДЕЛЕНИЕ ДЕЛЬТ (%)")
    for axis, key in [("YAW", "yaw_dist"), ("PITCH", "pitch_dist")]:
        print(f"  {axis}:")
        bd = B[key]; hd = H[key]
        for cat in bd:
            mark = ""
            if hd[cat] > 0:
                ratio = bd[cat] / hd[cat]
                if 0.5 <= ratio <= 2.0: mark = " OK"
                elif ratio > 3 or ratio < 0.33: mark = " !!"
                else: mark = " ~"
            print(f"    {cat:<12s}  {bd[cat]:6.2f}%    {hd[cat]:6.2f}%{mark}")

    header("5. РЕВЕРСЫ НАПРАВЛЕНИЯ")
    row("Yaw reversal %", B['yaw_reversal_pct'], H['yaw_reversal_pct'], ".1f", "%")
    row("Yaw reversal count", B['yaw_reversal_count'], H['yaw_reversal_count'], "d")
    row("Pitch reversal %", B['pitch_reversal_pct'], H['pitch_reversal_pct'], ".1f", "%")
    row("Pitch reversal count", B['pitch_reversal_count'], H['pitch_reversal_count'], "d")

    header("6. СЕРИИ ОДНОГО НАПРАВЛЕНИЯ")
    row("Yaw run mean", B['yaw_run_mean'], H['yaw_run_mean'], ".1f")
    row("Yaw run median", B['yaw_run_median'], H['yaw_run_median'], "d")
    row("Yaw run max", B['yaw_run_max'], H['yaw_run_max'], "d")
    row("Pitch run mean", B['pitch_run_mean'], H['pitch_run_mean'], ".1f")
    row("Pitch run median", B['pitch_run_median'], H['pitch_run_median'], "d")
    row("Pitch run max", B['pitch_run_max'], H['pitch_run_max'], "d")

    header("7. АВТОКОРРЕЛЯЦИЯ")
    for lag in [1, 2, 3, 5]:
        row(f"Yaw autocorr lag-{lag}", B[f'yaw_autocorr_lag{lag}'], H[f'yaw_autocorr_lag{lag}'], ".3f")
    print()
    for lag in [1, 2, 3, 5]:
        row(f"Pitch autocorr lag-{lag}", B[f'pitch_autocorr_lag{lag}'], H[f'pitch_autocorr_lag{lag}'], ".3f")
    print()
    row("Yaw vel autocorr", B['yaw_vel_autocorr'], H['yaw_vel_autocorr'], ".3f")
    row("Pitch vel autocorr", B['pitch_vel_autocorr'], H['pitch_vel_autocorr'], ".3f")

    header("8. ДЁРГАНОСТЬ (ускорение, deg/s2)")
    row("Yaw accel mean", B['yaw_accel_mean'], H['yaw_accel_mean'], ".0f")
    row("Yaw accel p90", B['yaw_accel_p90'], H['yaw_accel_p90'], ".0f")
    row("Yaw accel p95", B['yaw_accel_p95'], H['yaw_accel_p95'], ".0f")
    row("Yaw accel p99", B['yaw_accel_p99'], H['yaw_accel_p99'], ".0f")
    row("Yaw accel max", B['yaw_accel_max'], H['yaw_accel_max'], ".0f")
    print()
    row("Pitch accel mean", B['pitch_accel_mean'], H['pitch_accel_mean'], ".0f")
    row("Pitch accel p90", B['pitch_accel_p90'], H['pitch_accel_p90'], ".0f")
    row("Pitch accel p95", B['pitch_accel_p95'], H['pitch_accel_p95'], ".0f")
    row("Pitch accel p99", B['pitch_accel_p99'], H['pitch_accel_p99'], ".0f")
    row("Pitch accel max", B['pitch_accel_max'], H['pitch_accel_max'], ".0f")

    header("9. JERK (производная ускорения, deg/s3)")
    row("Yaw jerk mean", B['yaw_jerk_mean'], H['yaw_jerk_mean'], ".0f")
    row("Yaw jerk p95", B['yaw_jerk_p95'], H['yaw_jerk_p95'], ".0f")
    row("Pitch jerk mean", B['pitch_jerk_mean'], H['pitch_jerk_mean'], ".0f")
    row("Pitch jerk p95", B['pitch_jerk_p95'], H['pitch_jerk_p95'], ".0f")

    header("10. КВАНТИЗАЦИЯ")
    row("Quant step", B['quant_step'], H['quant_step'], ".6f", " deg")
    row("Yaw on grid", B['yaw_on_grid_pct'], H['yaw_on_grid_pct'], ".1f", "%")
    row("Pitch on grid", B['pitch_on_grid_pct'], H['pitch_on_grid_pct'], ".1f", "%")
    row("Off-grid yaw count", B['off_grid_yaw_count'], H['off_grid_yaw_count'], "d")
    row("Off-grid yaw mean |d|", B['off_grid_yaw_mean'], H['off_grid_yaw_mean'], ".4f")
    row("Sub-pixel yaw", B['sub_pixel_yaw_pct'], H['sub_pixel_yaw_pct'], ".2f", "%")
    row("Sub-pixel pitch", B['sub_pixel_pitch_pct'], H['sub_pixel_pitch_pct'], ".2f", "%")

    header("11. ±1px ALTERNATING (дизеринг)")
    row("Yaw alternating", B['alt_yaw_pct'], H['alt_yaw_pct'], ".2f", "%")
    row("Pitch alternating", B['alt_pitch_pct'], H['alt_pitch_pct'], ".2f", "%")

    header("12. ПАУЗЫ В ДВИЖЕНИИ")
    row("Yaw pause count", B['yaw_pauses_count'], H['yaw_pauses_count'], "d")
    row("Yaw pause mean", B['yaw_pause_mean_frames'], H['yaw_pause_mean_frames'], ".1f", " frames")

    header("13. ПАДЕНИЯ")
    row("Fall frames", B['fall_frame_count'], H['fall_frame_count'], "d")
    row("Fall %", B['fall_pct'], H['fall_pct'], ".1f", "%")
    row("Fall episodes", B['fall_episodes'], H['fall_episodes'], "d")
    row("Fall dur mean", B['fall_dur_mean'], H['fall_dur_mean'], ".0f", "ms")
    row("Fall pitch range mean", B['fall_pitch_range_mean'], H['fall_pitch_range_mean'], ".1f", "°")
    row("Fall pitch range max", B['fall_pitch_range_max'], H['fall_pitch_range_max'], ".1f", "°")
    row("Fall reaction mean", B['fall_reaction_mean_ms'], H['fall_reaction_mean_ms'], ".0f", "ms")
    row("Fall no-reaction count", B['fall_no_reaction_count'], H['fall_no_reaction_count'], "d")
    row("Fall pitch vel mean", B['fall_pitch_vel_mean'], H['fall_pitch_vel_mean'], ".1f")
    row("Normal pitch vel mean", B['normal_pitch_vel_mean'], H['normal_pitch_vel_mean'], ".1f")

    header("14. ЧАСТОТНЫЙ АНАЛИЗ")
    row("Yaw dominant freq", B['yaw_dominant_freq'], H['yaw_dominant_freq'], ".2f", " Hz")
    row("Pitch dominant freq", B['pitch_dominant_freq'], H['pitch_dominant_freq'], ".2f", " Hz")

    header("15. ДИАПАЗОН И ПРОБЕГ")
    row("Pitch min", B['pitch_min'], H['pitch_min'], ".1f", "°")
    row("Pitch max", B['pitch_max'], H['pitch_max'], ".1f", "°")
    row("Pitch range", B['pitch_range'], H['pitch_range'], ".1f", "°")
    row("Pitch mean", B['pitch_mean'], H['pitch_mean'], ".1f", "°")
    row("Yaw total travel", B['yaw_total_travel'], H['yaw_total_travel'], ".0f", "°")
    row("Pitch total travel", B['pitch_total_travel'], H['pitch_total_travel'], ".0f", "°")

    header("16. КОРРЕЛЯЦИЯ YAW<->PITCH")
    row("Yaw-pitch correlation", B['yaw_pitch_correlation'], H['yaw_pitch_correlation'], ".3f")

    header("17. БЫСТРЫЕ ДВИЖЕНИЯ")
    row("Fast frames (>100°/s)", B['fast_frames_pct'], H['fast_frames_pct'], ".1f", "%")
    row("Very fast (>300°/s)", B['very_fast_frames_pct'], H['very_fast_frames_pct'], ".1f", "%")

    header("18. SMOOTHNESS INDEX (accel/vel)")
    row("Yaw smoothness", B['yaw_smoothness'], H['yaw_smoothness'], ".1f")
    row("Pitch smoothness", B['pitch_smoothness'], H['pitch_smoothness'], ".1f")

    header("19. БАЛАНС НАПРАВЛЕНИЙ")
    row("Yaw +/- ratio", B['yaw_pos_neg_ratio'], H['yaw_pos_neg_ratio'], ".2f")
    row("Pitch +/- ratio", B['pitch_pos_neg_ratio'], H['pitch_pos_neg_ratio'], ".2f")

    # ==========================================================
    # VERDICT
    # ==========================================================
    print(f"\n{'='*72}")
    print(f"  ВЕРДИКТ — ГЛАВНЫЕ ОТЛИЧИЯ БОТА ОТ ЧЕЛОВЕКА")
    print(f"{'='*72}")

    issues = []

    # Static
    if abs(B['pitch_static_pct'] - H['pitch_static_pct']) > 10:
        issues.append(("PITCH СТАТИКА", f"Бот {B['pitch_static_pct']:.1f}% vs человек {H['pitch_static_pct']:.1f}%",
            "Бот слишком часто держит pitch неподвижным" if B['pitch_static_pct'] > H['pitch_static_pct'] else "Бот слишком часто двигает pitch"))
    if abs(B['yaw_static_pct'] - H['yaw_static_pct']) > 10:
        issues.append(("YAW СТАТИКА", f"Бот {B['yaw_static_pct']:.1f}% vs человек {H['yaw_static_pct']:.1f}%",
            "Бот слишком часто держит yaw неподвижным" if B['yaw_static_pct'] > H['yaw_static_pct'] else "Бот двигает yaw слишком постоянно"))

    # Autocorrelation
    if abs(B['yaw_autocorr_lag1'] - H['yaw_autocorr_lag1']) > 0.15:
        issues.append(("YAW АВТОКОРРЕЛЯЦИЯ", f"Бот {B['yaw_autocorr_lag1']:.3f} vs человек {H['yaw_autocorr_lag1']:.3f}",
            "Движения yaw слишком " + ("коррелированы" if B['yaw_autocorr_lag1'] > H['yaw_autocorr_lag1'] else "случайны")))
    if abs(B['pitch_autocorr_lag1'] - H['pitch_autocorr_lag1']) > 0.15:
        issues.append(("PITCH АВТОКОРРЕЛЯЦИЯ", f"Бот {B['pitch_autocorr_lag1']:.3f} vs человек {H['pitch_autocorr_lag1']:.3f}",
            "Движения pitch слишком " + ("коррелированы" if B['pitch_autocorr_lag1'] > H['pitch_autocorr_lag1'] else "случайны")))

    # Reversals
    if abs(B['pitch_reversal_pct'] - H['pitch_reversal_pct']) > 8:
        issues.append(("PITCH РЕВЕРСЫ", f"Бот {B['pitch_reversal_pct']:.1f}% vs человек {H['pitch_reversal_pct']:.1f}%",
            "Бот " + ("слишком часто" if B['pitch_reversal_pct'] > H['pitch_reversal_pct'] else "редко") + " меняет направление pitch"))
    if abs(B['yaw_reversal_pct'] - H['yaw_reversal_pct']) > 8:
        issues.append(("YAW РЕВЕРСЫ", f"Бот {B['yaw_reversal_pct']:.1f}% vs человек {H['yaw_reversal_pct']:.1f}%",
            "Бот " + ("слишком часто" if B['yaw_reversal_pct'] > H['yaw_reversal_pct'] else "редко") + " меняет направление yaw"))

    # Acceleration
    if B['pitch_accel_p95'] > H['pitch_accel_p95'] * 2.5 or (H['pitch_accel_p95'] > 0 and B['pitch_accel_p95'] < H['pitch_accel_p95'] * 0.3):
        issues.append(("PITCH ДЁРГАНОСТЬ", f"Бот p95={B['pitch_accel_p95']:.0f} vs человек p95={H['pitch_accel_p95']:.0f}",
            "Pitch " + ("слишком дёрганый" if B['pitch_accel_p95'] > H['pitch_accel_p95'] else "слишком плавный")))
    if B['yaw_accel_p95'] > H['yaw_accel_p95'] * 2.5 or (H['yaw_accel_p95'] > 0 and B['yaw_accel_p95'] < H['yaw_accel_p95'] * 0.3):
        issues.append(("YAW ДЁРГАНОСТЬ", f"Бот p95={B['yaw_accel_p95']:.0f} vs человек p95={H['yaw_accel_p95']:.0f}",
            "Yaw " + ("слишком дёрганый" if B['yaw_accel_p95'] > H['yaw_accel_p95'] else "слишком плавный")))

    # Alternating
    if B['alt_pitch_pct'] > H['alt_pitch_pct'] * 3 + 0.5:
        issues.append(("PITCH ДИЗЕРИНГ", f"Бот {B['alt_pitch_pct']:.2f}% vs человек {H['alt_pitch_pct']:.2f}%",
            "Pitch показывает ±1px alternating паттерн — признак error diffusion"))
    if B['alt_yaw_pct'] > H['alt_yaw_pct'] * 3 + 0.5:
        issues.append(("YAW ДИЗЕРИНГ", f"Бот {B['alt_yaw_pct']:.2f}% vs человек {H['alt_yaw_pct']:.2f}%",
            "Yaw показывает ±1px alternating паттерн"))

    # Velocity
    if H['yaw_vel_mean'] > 0 and (B['yaw_vel_mean'] / H['yaw_vel_mean'] > 2.0 or B['yaw_vel_mean'] / H['yaw_vel_mean'] < 0.4):
        issues.append(("СКОРОСТЬ YAW", f"Бот mean={B['yaw_vel_mean']:.1f} vs человек mean={H['yaw_vel_mean']:.1f}",
            "Бот двигает yaw " + ("быстрее" if B['yaw_vel_mean'] > H['yaw_vel_mean'] else "медленнее") + " чем человек"))
    if H['pitch_vel_mean'] > 0 and (B['pitch_vel_mean'] / H['pitch_vel_mean'] > 2.5 or B['pitch_vel_mean'] / H['pitch_vel_mean'] < 0.3):
        issues.append(("СКОРОСТЬ PITCH", f"Бот mean={B['pitch_vel_mean']:.1f} vs человек mean={H['pitch_vel_mean']:.1f}",
            "Бот двигает pitch " + ("быстрее" if B['pitch_vel_mean'] > H['pitch_vel_mean'] else "медленнее") + " чем человек"))

    # Run lengths
    if H['yaw_run_mean'] > 0 and abs(B['yaw_run_mean'] - H['yaw_run_mean']) / H['yaw_run_mean'] > 0.5:
        issues.append(("YAW СЕРИИ", f"Бот mean={B['yaw_run_mean']:.1f} vs человек mean={H['yaw_run_mean']:.1f}",
            "Серии одного направления " + ("длиннее" if B['yaw_run_mean'] > H['yaw_run_mean'] else "короче") + " у бота"))
    if H['pitch_run_mean'] > 0 and abs(B['pitch_run_mean'] - H['pitch_run_mean']) / H['pitch_run_mean'] > 0.5:
        issues.append(("PITCH СЕРИИ", f"Бот mean={B['pitch_run_mean']:.1f} vs человек mean={H['pitch_run_mean']:.1f}",
            "Серии одного направления " + ("длиннее" if B['pitch_run_mean'] > H['pitch_run_mean'] else "короче") + " у бота"))

    # Smoothness
    if H['yaw_smoothness'] > 0 and B['yaw_smoothness'] / H['yaw_smoothness'] > 2.0:
        issues.append(("YAW SMOOTHNESS", f"Бот {B['yaw_smoothness']:.1f} vs человек {H['yaw_smoothness']:.1f}",
            "Yaw менее плавный чем у человека (выше = дёрганнее)"))
    if H['pitch_smoothness'] > 0 and B['pitch_smoothness'] / H['pitch_smoothness'] > 2.0:
        issues.append(("PITCH SMOOTHNESS", f"Бот {B['pitch_smoothness']:.1f} vs человек {H['pitch_smoothness']:.1f}",
            "Pitch менее плавный чем у человека"))

    # Falls
    if B['fall_episodes'] > 0 and H['fall_episodes'] > 0:
        if abs(B['fall_pitch_range_mean'] - H['fall_pitch_range_mean']) > 5:
            issues.append(("FALL PITCH RANGE", f"Бот {B['fall_pitch_range_mean']:.1f}° vs человек {H['fall_pitch_range_mean']:.1f}°",
                "Бот " + ("больше" if B['fall_pitch_range_mean'] > H['fall_pitch_range_mean'] else "меньше") + " двигает pitch при падении"))
        if B['fall_reaction_mean_ms'] >= 0 and H['fall_reaction_mean_ms'] >= 0:
            if abs(B['fall_reaction_mean_ms'] - H['fall_reaction_mean_ms']) > 100:
                issues.append(("FALL REACTION", f"Бот {B['fall_reaction_mean_ms']:.0f}ms vs человек {H['fall_reaction_mean_ms']:.0f}ms",
                    "Время реакции на падение " + ("медленнее" if B['fall_reaction_mean_ms'] > H['fall_reaction_mean_ms'] else "быстрее") + " у бота"))

    # Quantization
    if abs(B['yaw_on_grid_pct'] - H['yaw_on_grid_pct']) > 15:
        issues.append(("КВАНТИЗАЦИЯ YAW", f"Бот {B['yaw_on_grid_pct']:.1f}% vs человек {H['yaw_on_grid_pct']:.1f}%",
            "Разница в квантизации — " + ("бот больше on-grid" if B['yaw_on_grid_pct'] > H['yaw_on_grid_pct'] else "человек больше on-grid")))

    # Sub-pixel
    if B['sub_pixel_yaw_pct'] > 1 and H['sub_pixel_yaw_pct'] < 0.5:
        issues.append(("SUB-PIXEL YAW", f"Бот {B['sub_pixel_yaw_pct']:.2f}% vs человек {H['sub_pixel_yaw_pct']:.2f}%",
            "Бот генерирует sub-pixel движения которых нет у человека — признак дробной арифметики"))

    # Yaw-pitch correlation
    if abs(B['yaw_pitch_correlation'] - H['yaw_pitch_correlation']) > 0.1:
        issues.append(("YAW<->PITCH КОРРЕЛЯЦИЯ", f"Бот {B['yaw_pitch_correlation']:.3f} vs человек {H['yaw_pitch_correlation']:.3f}",
            "Разная степень связи между yaw и pitch"))

    if not issues:
        print("\n  OK Все метрики в пределах нормы! Бот неотличим от человека.\n")
    else:
        print(f"\n  Найдено {len(issues)} отличий:\n")
        for i, (title, numbers, explanation) in enumerate(issues, 1):
            print(f"  {i}. [{title}]")
            print(f"     {numbers}")
            print(f"     -> {explanation}")
            print()

    print(f"{'='*72}")

# -- Main --
print("Загрузка бота:", BOT_CSV)
bot_rows = load(BOT_CSV)
print(f"  {len(bot_rows)} фреймов")

print("Загрузка человека:", HUMAN_CSV)
human_rows = load(HUMAN_CSV)
print(f"  {len(human_rows)} фреймов")

print("\nАнализ бота...")
B = analyze_deep(bot_rows, "BOT")

print("Анализ человека...")
H = analyze_deep(human_rows, "HUMAN")

print_deep(B, H)
