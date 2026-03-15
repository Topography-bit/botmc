"""
Bot vs Human camera comparison.
Usage: python camera_compare.py
Reads camera_debug.csv (bot) and camera_human.csv (human) from the Minecraft instance.
"""
import csv
import math
import sys

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

def analyze(rows, label):
    N = len(rows)
    if N < 10:
        print(f"  [{label}] Too few frames: {N}")
        return {}
    dur_s = (rows[-1]['time_ms'] - rows[0]['time_ms']) / 1000.0

    # Deltas
    yaw_d = []
    pitch_d = []
    dts = []
    for i in range(1, N):
        dy = rows[i]['yaw'] - rows[i-1]['yaw']
        while dy > 180: dy -= 360
        while dy < -180: dy += 360
        yaw_d.append(dy)
        pitch_d.append(rows[i]['pitch'] - rows[i-1]['pitch'])
        dts.append((rows[i]['time_ms'] - rows[i-1]['time_ms']) / 1000.0)

    # Velocities from CSV
    yaw_vels = [abs(rows[i]['yaw_vel']) for i in range(N)]
    pitch_vels = [abs(rows[i]['pitch_vel']) for i in range(N)]

    # --- Metrics ---
    results = {}
    results['frames'] = N
    results['duration_s'] = dur_s
    results['fps'] = N / dur_s if dur_s > 0 else 0

    # 1. Velocity stats
    results['yaw_vel_mean'] = sum(yaw_vels) / N
    results['yaw_vel_max'] = max(yaw_vels)
    results['pitch_vel_mean'] = sum(pitch_vels) / N
    results['pitch_vel_max'] = max(pitch_vels)

    # 2. Static percentage
    yaw_static = sum(1 for d in yaw_d if abs(d) < 0.001)
    pitch_static = sum(1 for d in pitch_d if abs(d) < 0.001)
    results['yaw_static_pct'] = yaw_static / len(yaw_d) * 100
    results['pitch_static_pct'] = pitch_static / len(pitch_d) * 100

    # 3. Direction reversals
    def reversal_pct(deltas):
        rev = 0
        total = 0
        for i in range(1, len(deltas)):
            if abs(deltas[i]) > 0.01 and abs(deltas[i-1]) > 0.01:
                total += 1
                if deltas[i] * deltas[i-1] < 0:
                    rev += 1
        return (rev / total * 100) if total > 0 else 0
    results['yaw_reversal_pct'] = reversal_pct(yaw_d)
    results['pitch_reversal_pct'] = reversal_pct(pitch_d)

    # 4. Same-direction run lengths
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
    yr = run_lengths(yaw_d)
    pr = run_lengths(pitch_d)
    results['yaw_run_mean'] = sum(yr) / len(yr) if yr else 0
    results['pitch_run_mean'] = sum(pr) / len(pr) if pr else 0
    results['yaw_run_median'] = sorted(yr)[len(yr)//2] if yr else 0
    results['pitch_run_median'] = sorted(pr)[len(pr)//2] if pr else 0

    # 5. Autocorrelation (lag-1)
    def autocorr(deltas):
        n = len(deltas)
        if n < 3: return 0
        mean = sum(deltas) / n
        var = sum((d - mean)**2 for d in deltas) / n
        if var < 1e-12: return 0
        cov = sum((deltas[i] - mean) * (deltas[i-1] - mean) for i in range(1, n)) / (n - 1)
        return cov / var
    results['yaw_autocorr'] = autocorr(yaw_d)
    results['pitch_autocorr'] = autocorr(pitch_d)

    # 6. Acceleration (jerkiness)
    yaw_accels = []
    pitch_accels = []
    for i in range(1, len(yaw_d)):
        dt = dts[i] if i < len(dts) else dts[-1]
        if 0.0001 < dt < 0.1:
            yaw_accels.append(abs((rows[i+1]['yaw_vel'] - rows[i]['yaw_vel']) / dt) if i+1 < N else 0)
            pitch_accels.append(abs((rows[i+1]['pitch_vel'] - rows[i]['pitch_vel']) / dt) if i+1 < N else 0)
    if yaw_accels:
        ya = sorted(yaw_accels)
        pa = sorted(pitch_accels)
        results['yaw_accel_p95'] = ya[int(len(ya)*0.95)]
        results['pitch_accel_p95'] = pa[int(len(pa)*0.95)]
        results['yaw_accel_max'] = ya[-1]
        results['pitch_accel_max'] = pa[-1]
    else:
        results['yaw_accel_p95'] = 0
        results['pitch_accel_p95'] = 0
        results['yaw_accel_max'] = 0
        results['pitch_accel_max'] = 0

    # 7. Quantization grid check
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
    results['quant_step'] = best_step
    results['yaw_on_grid_pct'] = best_match * 100
    clean_p = sum(1 for d in pitch_d if abs(d) < 0.0001 or (best_step > 0 and abs(abs(d)/best_step - round(abs(d)/best_step)) < 0.015))
    results['pitch_on_grid_pct'] = clean_p / len(pitch_d) * 100

    # 8. Delta size distribution
    def delta_dist(deltas):
        total = len(deltas)
        zero = sum(1 for d in deltas if abs(d) < 0.001)
        tiny = sum(1 for d in deltas if 0.001 <= abs(d) < 0.1)
        small = sum(1 for d in deltas if 0.1 <= abs(d) < 0.5)
        med = sum(1 for d in deltas if 0.5 <= abs(d) < 2.0)
        big = sum(1 for d in deltas if abs(d) >= 2.0)
        return {
            'zero': zero/total*100, 'tiny': tiny/total*100,
            'small': small/total*100, 'med': med/total*100, 'big': big/total*100
        }
    results['yaw_dist'] = delta_dist(yaw_d)
    results['pitch_dist'] = delta_dist(pitch_d)

    # 9. Alternating 1px pattern
    if best_step > 0.001:
        alt = 0
        for i in range(2, len(pitch_d)):
            d1 = pitch_d[i-1]
            d2 = pitch_d[i]
            if abs(abs(d1) - best_step) < 0.005 and abs(abs(d2) - best_step) < 0.005:
                if d1 * d2 < 0:
                    alt += 1
        results['pitch_alternating_pct'] = alt / (len(pitch_d)-1) * 100
    else:
        results['pitch_alternating_pct'] = 0

    # 10. Fall behavior
    fall_frames = [i for i in range(N) if rows[i]['falling'] == 1]
    results['fall_frames'] = len(fall_frames)
    results['fall_pct'] = len(fall_frames) / N * 100

    # Fall episodes
    episodes = []
    in_fall = False
    fs = 0
    for i in range(N):
        if rows[i]['falling'] == 1 and not in_fall:
            in_fall = True
            fs = i
        elif rows[i]['falling'] == 0 and in_fall:
            in_fall = False
            episodes.append((fs, i-1))
    if in_fall:
        episodes.append((fs, N-1))
    results['fall_episodes'] = len(episodes)

    # Pitch range during falls
    fall_pitch_ranges = []
    for s, e in episodes:
        dur = rows[e]['time_ms'] - rows[s]['time_ms']
        if dur < 200: continue
        pitches = [rows[i]['pitch'] for i in range(s, e+1)]
        fall_pitch_ranges.append(max(pitches) - min(pitches))
    results['fall_pitch_range_mean'] = sum(fall_pitch_ranges)/len(fall_pitch_ranges) if fall_pitch_ranges else 0

    # 11. Velocity distribution (speed profile shape)
    total_vel = [math.sqrt(rows[i]['yaw_vel']**2 + rows[i]['pitch_vel']**2) for i in range(N)]
    tv = sorted(total_vel)
    results['vel_median'] = tv[len(tv)//2]
    results['vel_p90'] = tv[int(len(tv)*0.9)]
    results['vel_p99'] = tv[int(len(tv)*0.99)]

    return results

def print_comparison(bot, human):
    def row(name, b, h, fmt=".1f", unit="", good_range=None):
        bs = f"{b:{fmt}}{unit}"
        hs = f"{h:{fmt}}{unit}"
        diff = ""
        if isinstance(b, (int, float)) and isinstance(h, (int, float)) and h != 0:
            ratio = b / h
            if ratio > 1.5 or ratio < 0.67:
                diff = " ⚠️"
            elif 0.8 <= ratio <= 1.25:
                diff = " ✓"
        print(f"  {name:<30s} {bs:>12s}  {hs:>12s}{diff}")

    print(f"\n{'='*65}")
    print(f"  {'METRIC':<30s} {'BOT':>12s}  {'HUMAN':>12s}")
    print(f"{'='*65}")

    print(f"\n── Общее ──")
    row("Фреймы", bot['frames'], human['frames'], "d")
    row("Длительность", bot['duration_s'], human['duration_s'], ".1f", "s")
    row("FPS", bot['fps'], human['fps'], ".0f")

    print(f"\n── Скорость (deg/s) ──")
    row("Yaw vel mean", bot['yaw_vel_mean'], human['yaw_vel_mean'])
    row("Yaw vel max", bot['yaw_vel_max'], human['yaw_vel_max'], ".0f")
    row("Pitch vel mean", bot['pitch_vel_mean'], human['pitch_vel_mean'])
    row("Pitch vel max", bot['pitch_vel_max'], human['pitch_vel_max'], ".0f")
    row("Total vel median", bot['vel_median'], human['vel_median'])
    row("Total vel p90", bot['vel_p90'], human['vel_p90'], ".0f")
    row("Total vel p99", bot['vel_p99'], human['vel_p99'], ".0f")

    print(f"\n── Статика (%) ──")
    row("Yaw static", bot['yaw_static_pct'], human['yaw_static_pct'], ".1f", "%")
    row("Pitch static", bot['pitch_static_pct'], human['pitch_static_pct'], ".1f", "%")

    print(f"\n── Реверсы (%) ──")
    row("Yaw reversals", bot['yaw_reversal_pct'], human['yaw_reversal_pct'], ".1f", "%")
    row("Pitch reversals", bot['pitch_reversal_pct'], human['pitch_reversal_pct'], ".1f", "%")

    print(f"\n── Серии одного направления ──")
    row("Yaw run mean", bot['yaw_run_mean'], human['yaw_run_mean'])
    row("Yaw run median", bot['yaw_run_median'], human['yaw_run_median'], "d")
    row("Pitch run mean", bot['pitch_run_mean'], human['pitch_run_mean'])
    row("Pitch run median", bot['pitch_run_median'], human['pitch_run_median'], "d")

    print(f"\n── Автокорреляция (lag-1) ──")
    row("Yaw autocorr", bot['yaw_autocorr'], human['yaw_autocorr'], ".3f")
    row("Pitch autocorr", bot['pitch_autocorr'], human['pitch_autocorr'], ".3f")

    print(f"\n── Дёрганость (deg/s²) ──")
    row("Yaw accel p95", bot['yaw_accel_p95'], human['yaw_accel_p95'], ".0f")
    row("Pitch accel p95", bot['pitch_accel_p95'], human['pitch_accel_p95'], ".0f")
    row("Yaw accel max", bot['yaw_accel_max'], human['yaw_accel_max'], ".0f")
    row("Pitch accel max", bot['pitch_accel_max'], human['pitch_accel_max'], ".0f")

    print(f"\n── Квантизация ──")
    row("Quant step", bot['quant_step'], human['quant_step'], ".6f")
    row("Yaw on grid", bot['yaw_on_grid_pct'], human['yaw_on_grid_pct'], ".1f", "%")
    row("Pitch on grid", bot['pitch_on_grid_pct'], human['pitch_on_grid_pct'], ".1f", "%")
    row("Pitch ±1px alternating", bot['pitch_alternating_pct'], human['pitch_alternating_pct'], ".2f", "%")

    print(f"\n── Распределение дельт (%) ──")
    for axis, key in [("Yaw", "yaw_dist"), ("Pitch", "pitch_dist")]:
        bd = bot[key]
        hd = human[key]
        print(f"  {axis}:")
        for cat in ['zero', 'tiny', 'small', 'med', 'big']:
            labels = {'zero': '|d|=0', 'tiny': '|d|<0.1', 'small': '0.1-0.5', 'med': '0.5-2.0', 'big': '>=2.0'}
            print(f"    {labels[cat]:<12s}  bot: {bd[cat]:5.1f}%   human: {hd[cat]:5.1f}%")

    print(f"\n── Падения ──")
    row("Fall frames", bot['fall_frames'], human['fall_frames'], "d")
    row("Fall %", bot['fall_pct'], human['fall_pct'], ".1f", "%")
    row("Fall episodes", bot['fall_episodes'], human['fall_episodes'], "d")
    row("Fall pitch range mean", bot['fall_pitch_range_mean'], human['fall_pitch_range_mean'], ".1f", "°")

    # Verdict
    print(f"\n{'='*65}")
    print("ВЕРДИКТ:")
    issues = []
    if abs(bot['yaw_autocorr'] - human['yaw_autocorr']) > 0.2:
        issues.append(f"  Yaw автокорреляция: бот {bot['yaw_autocorr']:.3f} vs человек {human['yaw_autocorr']:.3f}")
    if abs(bot['pitch_autocorr'] - human['pitch_autocorr']) > 0.2:
        issues.append(f"  Pitch автокорреляция: бот {bot['pitch_autocorr']:.3f} vs человек {human['pitch_autocorr']:.3f}")
    if abs(bot['pitch_static_pct'] - human['pitch_static_pct']) > 15:
        issues.append(f"  Pitch статика: бот {bot['pitch_static_pct']:.1f}% vs человек {human['pitch_static_pct']:.1f}%")
    if abs(bot['yaw_static_pct'] - human['yaw_static_pct']) > 15:
        issues.append(f"  Yaw статика: бот {bot['yaw_static_pct']:.1f}% vs человек {human['yaw_static_pct']:.1f}%")
    if abs(bot['pitch_reversal_pct'] - human['pitch_reversal_pct']) > 10:
        issues.append(f"  Pitch реверсы: бот {bot['pitch_reversal_pct']:.1f}% vs человек {human['pitch_reversal_pct']:.1f}%")
    if bot['pitch_alternating_pct'] > human['pitch_alternating_pct'] * 3 + 1:
        issues.append(f"  Pitch ±1px: бот {bot['pitch_alternating_pct']:.2f}% vs человек {human['pitch_alternating_pct']:.2f}%")
    if bot['pitch_accel_p95'] > human['pitch_accel_p95'] * 2:
        issues.append(f"  Pitch дёрганость p95: бот {bot['pitch_accel_p95']:.0f} vs человек {human['pitch_accel_p95']:.0f}")

    if not issues:
        print("  ✓ Все метрики в пределах нормы! Бот неотличим от человека.")
    else:
        print("  Отличия от человека:")
        for iss in issues:
            print(iss)
    print(f"{'='*65}")

# ── Main ──
print("Загрузка бота:", BOT_CSV)
bot_rows = load(BOT_CSV)
print(f"  {len(bot_rows)} фреймов")

print("Загрузка человека:", HUMAN_CSV)
human_rows = load(HUMAN_CSV)
print(f"  {len(human_rows)} фреймов")

print("\nАнализ бота...")
bot_metrics = analyze(bot_rows, "BOT")

print("Анализ человека...")
human_metrics = analyze(human_rows, "HUMAN")

print_comparison(bot_metrics, human_metrics)
