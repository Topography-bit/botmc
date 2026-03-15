import csv

data = []
with open('C:/Users/Ghost/AppData/Roaming/PrismLauncher/instances/WaldinDastinner1.12.10/minecraft/autopilot_debug.csv', 'r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        d = {}
        for k,v in row.items():
            try:
                d[k] = float(v)
            except:
                d[k] = v
        data.append(d)

total = len(data)
print(f'Total ticks: {total}')
print(f'Time range: tick {int(data[0]["tick"])} to {int(data[-1]["tick"])}')
first_ms = data[0]['time_ms']
last_ms = data[-1]['time_ms']
print(f'Duration: {(last_ms - first_ms)/1000:.1f} seconds')
print()

# === 1. STOPPING PROBLEM ===
print('='*80)
print('1. STOPPING PROBLEM (forward=0 segments)')
print('='*80)

segments = []
start = None
for i, d in enumerate(data):
    fwd = int(d['forward'])
    if fwd == 0:
        if start is None:
            start = i
    else:
        if start is not None:
            segments.append((start, i-1))
            start = None
if start is not None:
    segments.append((start, len(data)-1))

long_segments = [(s,e) for s,e in segments if e-s+1 >= 3]
print(f'Total forward=0 ticks: {sum(1 for d in data if int(d["forward"])==0)}')
print(f'Total forward=1 ticks: {sum(1 for d in data if int(d["forward"])==1)}')
pct_stop = sum(1 for d in data if int(d["forward"])==0)/total*100
print(f'Percentage stopped: {pct_stop:.1f}%')
print(f'Stop segments (any length): {len(segments)}')
print(f'Stop segments (>=3 ticks): {len(long_segments)}')
print(f'Stop segments (>=5 ticks): {len([(s,e) for s,e in segments if e-s+1 >= 5])}')
print(f'Stop segments (>=10 ticks): {len([(s,e) for s,e in segments if e-s+1 >= 10])}')
print()

print('DETAILED STOP SEGMENTS (>=3 ticks):')
for s, e in long_segments:
    length = e - s + 1
    tick_s = int(data[s]['tick'])
    tick_e = int(data[e]['tick'])

    aim_errors = [data[i]['aim_error'] for i in range(s, e+1)]
    mob_dists = [data[i]['mob_dist'] for i in range(s, e+1)]
    in_combats = [int(data[i]['in_combat']) for i in range(s, e+1)]
    combat_stucks = [int(data[i]['combat_stuck']) for i in range(s, e+1)]

    avg_aim = sum(aim_errors)/len(aim_errors)
    valid_dists = [d for d in mob_dists if d < 1e10]
    avg_dist = sum(valid_dists)/len(valid_dists) if valid_dists else 999
    any_combat = any(c == 1 for c in in_combats)
    max_stuck = max(combat_stucks) if combat_stucks else 0
    target = data[s].get('target', 'unknown')

    brake_like = any(data[i]['aim_error'] > 30 and data[i]['mob_dist'] < 6 and data[i]['mob_dist'] < 1e10 for i in range(s, e+1))

    print(f'  T{tick_s}-{tick_e} ({length}t): dist={avg_dist:.1f} aim={avg_aim:.1f} combat={any_combat} cs={max_stuck} brake={brake_like} [{target}]')

print()

# === 2. COMBAT EFFICIENCY ===
print('='*80)
print('2. COMBAT EFFICIENCY')
print('='*80)

combat_ticks = [d for d in data if int(d['in_combat'])==1]
pursuit_ticks = [d for d in data if int(d['direct_pursuit'])==1]
attack_ticks = [d for d in data if int(d['attack'])==1]
crosshair_ticks = [d for d in data if int(d['crosshair_entity'])==1]

print(f'Total in_combat=1 ticks: {len(combat_ticks)} ({len(combat_ticks)/total*100:.1f}%)')
print(f'Total direct_pursuit=1 ticks: {len(pursuit_ticks)} ({len(pursuit_ticks)/total*100:.1f}%)')
print(f'Total attack=1 ticks: {len(attack_ticks)} ({len(attack_ticks)/total*100:.1f}%)')
print(f'Total crosshair_entity=1 ticks: {len(crosshair_ticks)} ({len(crosshair_ticks)/total*100:.1f}%)')
print()

if combat_ticks:
    combat_attacks = sum(1 for d in combat_ticks if int(d['attack'])==1)
    combat_crosshair = sum(1 for d in combat_ticks if int(d['crosshair_entity'])==1)
    combat_aim_errors = [d['aim_error'] for d in combat_ticks if d['aim_error'] < 500]
    combat_stopped = sum(1 for d in combat_ticks if int(d['forward'])==0)

    print(f'In combat: attacks={combat_attacks}/{len(combat_ticks)} ({combat_attacks/len(combat_ticks)*100:.1f}%)')
    print(f'In combat: crosshair on mob={combat_crosshair}/{len(combat_ticks)} ({combat_crosshair/len(combat_ticks)*100:.1f}%)')
    print(f'In combat: stopped (fwd=0)={combat_stopped}/{len(combat_ticks)} ({combat_stopped/len(combat_ticks)*100:.1f}%)')
    if combat_aim_errors:
        combat_aim_errors_sorted = sorted(combat_aim_errors)
        print(f'In combat: aim_error avg={sum(combat_aim_errors)/len(combat_aim_errors):.1f} median={combat_aim_errors_sorted[len(combat_aim_errors)//2]:.1f}')
        print(f'  aim_error <10: {sum(1 for a in combat_aim_errors if a < 10)} ticks')
        print(f'  aim_error 10-30: {sum(1 for a in combat_aim_errors if 10 <= a < 30)} ticks')
        print(f'  aim_error 30-60: {sum(1 for a in combat_aim_errors if 30 <= a < 60)} ticks')
        print(f'  aim_error 60-90: {sum(1 for a in combat_aim_errors if 60 <= a < 90)} ticks')
        print(f'  aim_error >90: {sum(1 for a in combat_aim_errors if a >= 90)} ticks')
print()

# Find combat segments
print('COMBAT ENCOUNTERS (in_combat segments):')
cseg = []
cstart = None
for i, d in enumerate(data):
    if int(d['in_combat'])==1:
        if cstart is None:
            cstart = i
    else:
        if cstart is not None:
            cseg.append((cstart, i-1))
            cstart = None
if cstart is not None:
    cseg.append((cstart, len(data)-1))

for s, e in cseg:
    length = e - s + 1
    tick_s = int(data[s]['tick'])
    tick_e = int(data[e]['tick'])
    attacks_in = sum(1 for i in range(s,e+1) if int(data[i]['attack'])==1)
    fwd0_in = sum(1 for i in range(s,e+1) if int(data[i]['forward'])==0)
    aims = [data[i]['aim_error'] for i in range(s,e+1) if data[i]['aim_error'] < 500]
    avg_aim = sum(aims)/len(aims) if aims else 999
    max_cs = max(int(data[i]['combat_stuck']) for i in range(s,e+1))
    dists = [data[i]['mob_dist'] for i in range(s,e+1) if data[i]['mob_dist'] < 1e10]
    avg_d = sum(dists)/len(dists) if dists else 999
    print(f'  T{tick_s}-{tick_e} ({length}t): attacks={attacks_in} fwd0={fwd0_in} avg_aim={avg_aim:.1f} avg_dist={avg_d:.2f} max_cs={max_cs}')

print()

# === 3. STRAFING ===
print('='*80)
print('3. STRAFE ANALYSIS')
print('='*80)

strafe_ticks = sum(1 for d in data if int(d['strafe']) != 0)
strafe_left = sum(1 for d in data if int(d['strafe']) == -1)
strafe_right = sum(1 for d in data if int(d['strafe']) == 1)
print(f'Strafe ticks: {strafe_ticks}/{total} ({strafe_ticks/total*100:.1f}%)')
print(f'Strafe left (-1): {strafe_left}')
print(f'Strafe right (1): {strafe_right}')

strafe_combat = sum(1 for d in data if int(d['strafe'])!=0 and int(d['in_combat'])==1)
strafe_pursuit = sum(1 for d in data if int(d['strafe'])!=0 and int(d['direct_pursuit'])==1)
strafe_nav = sum(1 for d in data if int(d['strafe'])!=0 and int(d['in_combat'])==0 and int(d['direct_pursuit'])==0)
print(f'Strafe during combat: {strafe_combat}')
print(f'Strafe during pursuit: {strafe_pursuit}')
print(f'Strafe during nav (potential leak): {strafe_nav}')
print()

if strafe_nav > 0:
    print('NAV STRAFE INSTANCES:')
    count = 0
    for i, d in enumerate(data):
        if int(d['strafe'])!=0 and int(d['in_combat'])==0 and int(d['direct_pursuit'])==0:
            mob_d = d['mob_dist']
            mob_str = f'{mob_d:.1f}' if mob_d < 1e10 else 'INF'
            print(f'  T{int(d["tick"])}: strafe={int(d["strafe"])} dist={mob_str} fwd={int(d["forward"])}')
            count += 1
            if count > 20:
                print(f'  ... and more')
                break
print()

# === 4. YAW TRACKING ===
print('='*80)
print('4. YAW TRACKING')
print('='*80)

yaw_diffs = []
for d in data:
    g = d['goal_yaw']
    c = d['current_yaw']
    diff = abs(g - c)
    if diff > 180:
        diff = 360 - diff
    yaw_diffs.append(diff)

print(f'Yaw diff avg: {sum(yaw_diffs)/len(yaw_diffs):.1f}')
yaw_sorted = sorted(yaw_diffs)
print(f'Yaw diff median: {yaw_sorted[len(yaw_diffs)//2]:.1f}')
print(f'Yaw diff >30: {sum(1 for y in yaw_diffs if y>30)} ticks ({sum(1 for y in yaw_diffs if y>30)/total*100:.1f}%)')
print(f'Yaw diff >60: {sum(1 for y in yaw_diffs if y>60)} ticks ({sum(1 for y in yaw_diffs if y>60)/total*100:.1f}%)')
print(f'Yaw diff >90: {sum(1 for y in yaw_diffs if y>90)} ticks ({sum(1 for y in yaw_diffs if y>90)/total*100:.1f}%)')
print()

ylag_segs = []
ystart = None
for i, yd in enumerate(yaw_diffs):
    if yd > 30:
        if ystart is None:
            ystart = i
    else:
        if ystart is not None:
            ylag_segs.append((ystart, i-1))
            ystart = None
if ystart is not None:
    ylag_segs.append((ystart, len(data)-1))

long_ylag = [(s,e) for s,e in ylag_segs if e-s+1 >= 5]
print(f'Extended yaw-lag segments (>30deg, >=5 ticks): {len(long_ylag)}')
for s, e in long_ylag[:20]:
    length = e - s + 1
    tick_s = int(data[s]['tick'])
    tick_e = int(data[e]['tick'])
    max_diff = max(yaw_diffs[i] for i in range(s,e+1))
    in_c = any(int(data[i]['in_combat'])==1 for i in range(s,e+1))
    cnt = sum(1 for i in range(s,e+1) if data[i]['mob_dist']<1e10)
    valid_d = [data[i]['mob_dist'] for i in range(s,e+1) if data[i]['mob_dist']<1e10]
    avg_d = sum(valid_d)/len(valid_d) if valid_d else 999
    fwd0 = sum(1 for i in range(s,e+1) if int(data[i]['forward'])==0)
    print(f'  T{tick_s}-{tick_e} ({length}t): max_ydiff={max_diff:.1f} combat={in_c} dist={avg_d:.1f} fwd0={fwd0}')
print()

# === 5. OVERALL FLOW ===
print('='*80)
print('5. OVERALL FLOW')
print('='*80)

nav_ticks = sum(1 for d in data if int(d['in_combat'])==0 and int(d['direct_pursuit'])==0)
combat_t = sum(1 for d in data if int(d['in_combat'])==1)
pursuit_t = sum(1 for d in data if int(d['direct_pursuit'])==1 and int(d['in_combat'])==0)
stopped_all = sum(1 for d in data if int(d['forward'])==0)
stopped_nav = sum(1 for d in data if int(d['forward'])==0 and int(d['in_combat'])==0 and int(d['direct_pursuit'])==0)
no_target = sum(1 for d in data if d.get('target','') == 'none')

print(f'Navigation: {nav_ticks} ticks ({nav_ticks/total*100:.1f}%)')
if nav_ticks > 0:
    print(f'  of which stopped: {stopped_nav} ({stopped_nav/nav_ticks*100:.1f}% of nav)')
print(f'Combat: {combat_t} ticks ({combat_t/total*100:.1f}%)')
print(f'Direct pursuit (not combat): {pursuit_t} ticks ({pursuit_t/total*100:.1f}%)')
print(f'No target (none): {no_target} ticks ({no_target/total*100:.1f}%)')
print(f'Total stopped: {stopped_all} ticks ({stopped_all/total*100:.1f}%)')
print()

# Mob kills
print('PROBABLE KILLS (combat->non-combat with dist jump):')
kills = 0
for i in range(1, len(data)):
    prev_d = data[i-1]['mob_dist']
    curr_d = data[i]['mob_dist']
    prev_combat = int(data[i-1]['in_combat'])
    curr_combat = int(data[i]['in_combat'])
    if prev_combat == 1 and curr_combat == 0 and prev_d < 4 and (curr_d > 10 or curr_d > 1e10):
        kills += 1
        cd_str = f'{curr_d:.2f}' if curr_d < 1e10 else 'INF'
        print(f'  Kill at tick {int(data[i]["tick"])}: prev_dist={prev_d:.2f} -> {cd_str}')

print(f'Total probable kills: {kills}')
print()

# Stuck
stuck_ticks = sum(1 for d in data if d['stuck'] > 0)
high_stuck = sum(1 for d in data if d['stuck'] >= 5)
print(f'Stuck>0 ticks: {stuck_ticks} ({stuck_ticks/total*100:.1f}%)')
print(f'Stuck>=5 ticks: {high_stuck} ({high_stuck/total*100:.1f}%)')
print(f'Max stuck value: {max(d["stuck"] for d in data)}')
print()

# === 6. CIRCLING PATTERN ===
print('='*80)
print('6. CIRCLING/ORBIT DETECTION')
print('='*80)

# Look for combat segments where bot walks forward but aim_error stays high
print('Long combat without attack (>=10 ticks, 0 attacks):')
for s, e in cseg:
    length = e - s + 1
    if length >= 10:
        attacks_in = sum(1 for i in range(s,e+1) if int(data[i]['attack'])==1)
        if attacks_in == 0:
            tick_s = int(data[s]['tick'])
            tick_e = int(data[e]['tick'])
            aims = [data[i]['aim_error'] for i in range(s,e+1) if data[i]['aim_error'] < 500]
            avg_aim = sum(aims)/len(aims) if aims else 999
            fwd1 = sum(1 for i in range(s,e+1) if int(data[i]['forward'])==1)
            print(f'  T{tick_s}-{tick_e} ({length}t): 0 attacks, avg_aim={avg_aim:.1f}, fwd1={fwd1}/{length}')

print()

# Overshooting: when bot gets very close (<0.5) but aim_error is huge
print('OVERSHOOT INSTANCES (dist<1.0, aim>60):')
count = 0
for d in data:
    if d['mob_dist'] < 1.0 and d['mob_dist'] > 0 and d['aim_error'] > 60 and d['aim_error'] < 500 and d['mob_dist'] < 1e10:
        count += 1
print(f'  Total: {count} ticks')

# Bot running past mob
print('RUN-PAST INSTANCES (was approaching, dist<2 then dist grows while fwd=1):')
run_past = 0
for i in range(2, len(data)):
    d0 = data[i-2]['mob_dist']
    d1 = data[i-1]['mob_dist']
    d2 = data[i]['mob_dist']
    if d0 < 1e10 and d1 < 1e10 and d2 < 1e10:
        if d1 < 2.0 and d0 > d1 and d2 > d1 and int(data[i-1]['forward'])==1 and int(data[i-1]['in_combat'])==1:
            run_past += 1
print(f'  Total: {run_past} ticks')
