"""Full humanness evaluation: bot vs human camera CSV."""
import csv, math, sys
sys.stdout = open(sys.stdout.fileno(), mode='w', encoding='utf-8', errors='replace')

BOT = r'C:\Users\Ghost\AppData\Roaming\PrismLauncher\instances\WaldinDastinner1.12.10\minecraft\camera_debug.csv'
HUMAN = r'C:\Users\Ghost\AppData\Roaming\PrismLauncher\instances\WaldinDastinner1.12.10\minecraft\camera_human.csv'

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

def pctl(arr, p):
    return arr[min(int(len(arr)*p), len(arr)-1)]

def autocorr1(arr):
    n = len(arr)
    m = sum(arr)/n
    var = sum((x-m)**2 for x in arr)/n
    if var < 1e-12: return 0
    cov = sum((arr[i]-m)*(arr[i-1]-m) for i in range(1,n))/(n-1)
    return cov/var

def analyze(rows, label):
    N = len(rows)
    print(f'\n{"="*70}')
    print(f'  {label}: {N} frames')
    print(f'{"="*70}')

    dp_list, dy_list, dt_list = [], [], []
    for i in range(1, N):
        dp_list.append(rows[i]['pitch'] - rows[i-1]['pitch'])
        dy_list.append(wrap180(rows[i]['yaw'] - rows[i-1]['yaw']))
        dt_list.append(rows[i]['time_ms'] - rows[i-1]['time_ms'])

    abs_dp = sorted([abs(d) for d in dp_list])
    abs_dy = sorted([abs(d) for d in dy_list])

    # 1. SPIKES
    print(f'\n  PITCH SPIKES:')
    for thresh in [1.5, 3.0, 5.0, 8.0]:
        c = sum(1 for d in dp_list if abs(d) > thresh)
        print(f'    |dp| > {thresh}: {c} ({c/(N-1)*100:.3f}%)')

    print(f'\n  YAW SPIKES:')
    for thresh in [3.0, 5.0, 8.0]:
        c = sum(1 for d in dy_list if abs(d) > thresh)
        print(f'    |dy| > {thresh}: {c} ({c/(N-1)*100:.3f}%)')

    # 2. DISTRIBUTIONS
    print(f'\n  PITCH |dp|: mean={sum(abs_dp)/len(abs_dp):.4f}  med={pctl(abs_dp,0.5):.4f}  p95={pctl(abs_dp,0.95):.4f}  p99={pctl(abs_dp,0.99):.4f}  max={abs_dp[-1]:.4f}')
    print(f'  YAW   |dy|: mean={sum(abs_dy)/len(abs_dy):.4f}  med={pctl(abs_dy,0.5):.4f}  p95={pctl(abs_dy,0.95):.4f}  p99={pctl(abs_dy,0.99):.4f}  max={abs_dy[-1]:.4f}')

    # 3. ZERO FRAMES
    z_both = sum(1 for i in range(len(dp_list)) if abs(dp_list[i]) < 0.001 and abs(dy_list[i]) < 0.001)
    z_p = sum(1 for d in dp_list if abs(d) < 0.001)
    z_y = sum(1 for d in dy_list if abs(d) < 0.001)
    print(f'\n  ZERO FRAMES (<0.001):  both={z_both} ({z_both/(N-1)*100:.1f}%)  pitch={z_p} ({z_p/(N-1)*100:.1f}%)  yaw={z_y} ({z_y/(N-1)*100:.1f}%)')

    # 4. REVERSALS
    def reversals(arr):
        rev, pairs = 0, 0
        for i in range(1, len(arr)):
            if abs(arr[i]) > 0.01 and abs(arr[i-1]) > 0.01:
                pairs += 1
                if arr[i] * arr[i-1] < 0: rev += 1
        return rev, pairs
    pr, pp = reversals(dp_list)
    yr, yp = reversals(dy_list)
    print(f'\n  REVERSALS:  pitch={pr}/{pp} ({pr/pp*100:.1f}%)' if pp else '\n  REVERSALS: pitch=0', end='')
    print(f'  yaw={yr}/{yp} ({yr/yp*100:.1f}%)' if yp else '  yaw=0')

    # 5. AUTOCORRELATION
    ac_p = autocorr1(dp_list)
    ac_y = autocorr1(dy_list)
    print(f'\n  AUTOCORR lag-1:  pitch={ac_p:.3f}  yaw={ac_y:.3f}')

    # 6. CROSS-CORRELATION
    n = len(dp_list)
    mp = sum(dp_list)/n; my = sum(dy_list)/n
    sp = max(1e-12, (sum((x-mp)**2 for x in dp_list)/n)**0.5)
    sy = max(1e-12, (sum((x-my)**2 for x in dy_list)/n)**0.5)
    cross = sum((dp_list[i]-mp)*(dy_list[i]-my) for i in range(n))/n/(sp*sy)
    print(f'  CROSS-CORR (pitch x yaw): {cross:.3f}')

    # 7. QUANTIZATION CHECK
    nonzero_dp = sorted([abs(d) for d in dp_list if abs(d) > 0.001])
    if len(nonzero_dp) >= 20:
        step_est = sum(nonzero_dp[:20])/20
        on_grid = sum(1 for d in nonzero_dp if abs(d/step_est - round(d/step_est)) < 0.15)
        print(f'\n  QUANTIZATION:  est_step={step_est:.6f}  on_grid={on_grid}/{len(nonzero_dp)} ({on_grid/len(nonzero_dp)*100:.1f}%)')
        unique = sorted(set(round(d, 6) for d in nonzero_dp))[:12]
        print(f'    smallest unique |dp|: {[round(v,6) for v in unique]}')

    # 8. ACCELERATION
    accels = []
    for i in range(1, len(dp_list)):
        dt1 = max(dt_list[i], 1.0)
        dt0 = max(dt_list[i-1], 1.0)
        v1 = dp_list[i] / (dt1/1000.0)
        v0 = dp_list[i-1] / (dt0/1000.0)
        accels.append(abs(v1 - v0) / (dt1/1000.0))
    accels.sort()
    print(f'\n  PITCH ACCEL (deg/s2):  mean={sum(accels)/len(accels):.0f}  p95={pctl(accels,0.95):.0f}  p99={pctl(accels,0.99):.0f}  max={accels[-1]:.0f}')

    # 9. FALL ANALYSIS
    fall_dp = []
    for i in range(1, N):
        if rows[i].get('falling', 0) == 1:
            fall_dp.append(rows[i]['pitch'] - rows[i-1]['pitch'])
    if fall_dp:
        abs_fall = sorted([abs(d) for d in fall_dp])
        spk = sum(1 for d in fall_dp if abs(d) > 1.5)
        print(f'\n  DURING FALLS ({len(fall_dp)} frames):  |dp| mean={sum(abs_fall)/len(abs_fall):.4f}  p95={pctl(abs_fall,0.95):.4f}  max={abs_fall[-1]:.4f}  spikes>1.5={spk}')

    # 10. RUN-LENGTH (consecutive same-direction frames)
    runs = []
    cur = 1
    for i in range(1, len(dp_list)):
        if dp_list[i] * dp_list[i-1] > 0:
            cur += 1
        else:
            if cur > 0: runs.append(cur)
            cur = 1
    if cur > 0: runs.append(cur)
    if runs:
        runs.sort()
        print(f'\n  RUN-LENGTH (same dir):  mean={sum(runs)/len(runs):.1f}  med={pctl(runs,0.5)}  p95={pctl(runs,0.95)}  max={runs[-1]}')

    # 11. dt distribution
    dt_sorted = sorted(dt_list)
    print(f'\n  DT (ms):  mean={sum(dt_sorted)/len(dt_sorted):.1f}  med={pctl(dt_sorted,0.5):.1f}  p99={pctl(dt_sorted,0.99):.1f}  max={dt_sorted[-1]:.1f}')

    return dp_list, dy_list

print('Loading bot...')
bot = load(BOT)
print('Loading human...')
human = load(HUMAN)

dp_b, dy_b = analyze(bot, 'BOT (latest)')
dp_h, dy_h = analyze(human, 'HUMAN')

# COMPARISON TABLE
print(f'\n{"="*70}')
print(f'  SIDE-BY-SIDE')
print(f'{"="*70}')
print(f'{"Metric":<35} {"BOT":>14} {"HUMAN":>14} {"VERDICT":>8}')
print(f'{"-"*71}')

def s(arr): return sorted([abs(x) for x in arr])
bp = s(dp_b); hp = s(dp_h)
by = s(dy_b); hy = s(dy_h)

def grade(b, h, lower_better=True):
    if h == 0: return '?'
    ratio = b/h if h != 0 else 999
    if lower_better:
        if ratio < 1.5: return 'OK'
        if ratio < 3.0: return '~'
        return 'BAD'
    else:
        if 0.5 < ratio < 2.0: return 'OK'
        return '~'

rows_cmp = [
    ('Pitch |dp| mean', sum(bp)/len(bp), sum(hp)/len(hp), True),
    ('Pitch |dp| p95', pctl(bp,0.95), pctl(hp,0.95), True),
    ('Pitch |dp| p99', pctl(bp,0.99), pctl(hp,0.99), True),
    ('Pitch |dp| max', bp[-1], hp[-1], True),
    ('Pitch spikes > 1.5', sum(1 for d in dp_b if abs(d) > 1.5), sum(1 for d in dp_h if abs(d) > 1.5), True),
    ('Pitch spikes > 5.0', sum(1 for d in dp_b if abs(d) > 5.0), sum(1 for d in dp_h if abs(d) > 5.0), True),
    ('Yaw |dy| mean', sum(by)/len(by), sum(hy)/len(hy), True),
    ('Yaw |dy| p99', pctl(by,0.99), pctl(hy,0.99), True),
    ('Pitch reversals %', (lambda: (sum(1 for i in range(1,len(dp_b)) if abs(dp_b[i])>0.01 and abs(dp_b[i-1])>0.01 and dp_b[i]*dp_b[i-1]<0) / max(1,sum(1 for i in range(1,len(dp_b)) if abs(dp_b[i])>0.01 and abs(dp_b[i-1])>0.01))*100))(),
     (lambda: (sum(1 for i in range(1,len(dp_h)) if abs(dp_h[i])>0.01 and abs(dp_h[i-1])>0.01 and dp_h[i]*dp_h[i-1]<0) / max(1,sum(1 for i in range(1,len(dp_h)) if abs(dp_h[i])>0.01 and abs(dp_h[i-1])>0.01))*100))(), True),
    ('Pitch autocorr', autocorr1(dp_b), autocorr1(dp_h), False),
    ('Yaw autocorr', autocorr1(dy_b), autocorr1(dy_h), False),
]

for name, b, h, lb in rows_cmp:
    v = grade(b, h, lb)
    print(f'{name:<35} {b:>14.4f} {h:>14.4f} {v:>8}')

# TOP 10 BIGGEST PITCH SPIKES (with context)
print(f'\n{"="*70}')
print(f'  TOP 10 BIGGEST PITCH SPIKES')
print(f'{"="*70}')
all_spikes = []
for i in range(1, len(bot)):
    dp = bot[i]['pitch'] - bot[i-1]['pitch']
    if abs(dp) > 1.0:
        fall = int(bot[i].get('falling', 0))
        sacc = bot[i].get('saccade', 0)
        all_spikes.append((i, dp, fall, sacc))
all_spikes.sort(key=lambda x: -abs(x[1]))
for rank, (idx, dp, fall, sacc) in enumerate(all_spikes[:10]):
    dt = bot[idx]['time_ms'] - bot[idx-1]['time_ms']
    print(f'  #{rank+1}: frame {idx}  dp={dp:+.3f}  dt={dt:.1f}ms  fall={fall}  saccade={sacc:.0f}')
    # 3 frames before/after
    for j in range(max(1, idx-3), min(len(bot), idx+4)):
        d = bot[j]['pitch'] - bot[j-1]['pitch']
        f = int(bot[j].get('falling', 0))
        s = bot[j].get('saccade', 0)
        marker = ' >>>' if j == idx else '    '
        print(f'    {marker} {j}: dp={d:+8.4f}  pitch={bot[j]["pitch"]:>8.2f}  fall={f}  sacc={s:.0f}')
