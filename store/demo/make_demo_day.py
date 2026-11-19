import json, math, random, urllib.request, datetime as dt
from zoneinfo import ZoneInfo
random.seed(7)
TZ = ZoneInfo("America/New_York")     # the emulator's own zone: clock times read as they will on screen
DAY = dt.date(2026, 11, 10)
def ms(d, hh, mm, ss=0): return int(dt.datetime(d.year, d.month, d.day, hh, mm, ss, tzinfo=TZ).timestamp() * 1000)
P = {
    "Home": (47.6253, -122.3141), "Office": (47.6226, -122.3368), "Sweetgreen": (47.6189, -122.3383),
    "QFC": (47.6232, -122.3209), "Volunteer Park": (47.6301, -122.3149),
}
def metres(a, b):
    R = 6371000; p1, p2 = math.radians(a[0]), math.radians(b[0]); dl = math.radians(b[1] - a[1])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))
def route(a, b):
    url = f"https://router.project-osrm.org/route/v1/driving/{P[a][1]},{P[a][0]};{P[b][1]},{P[b][0]}?overview=full&geometries=geojson"
    g = json.load(urllib.request.urlopen(url, timeout=20))["routes"][0]["geometry"]["coordinates"]
    return [(lat, lon) for lon, lat in g]
fixes = []
def fix(t, ll, acc=6.0, speed=None):
    fixes.append((t, ll[0], ll[1], acc, speed))
def jitter(ll, m):
    return (ll[0] + random.uniform(-m, m) / 111000, ll[1] + random.uniform(-m, m) / 75000)
def stay(name, t0, t1, every_min=3):
    t = t0
    while t < t1:
        fix(t, jitter(P[name], 4), acc=random.choice([5.0, 6.0, 8.0])); t += every_min * 60000
def travel(a, b, t0, t1, step_s):
    pts = route(a, b); pts = [P[a]] + pts + [P[b]]
    segs = [metres(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]; total = sum(segs)
    n = max(2, int((t1 - t0) / 1000 / step_s))
    for k in range(1, n):
        d = total * k / n; i = 0
        while i < len(segs) - 1 and d > segs[i]: d -= segs[i]; i += 1
        f = d / segs[i] if segs[i] else 0
        ll = (pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f)
        fix(t0 + int((t1 - t0) * k / n), jitter(ll, 2), acc=5.0, speed=total / ((t1 - t0) / 1000))
    return total
prev = DAY - dt.timedelta(days=1)
def go(a, b, start, kmh, step_s):
    """Travel from a to b leaving at start, at a realistic pace for the route's length; returns arrival."""
    pts = route(a, b); dist = sum(metres(pts[i], pts[i + 1]) for i in range(len(pts) - 1)) + metres(P[a], pts[0]) + metres(pts[-1], P[b])
    end = start + int(dist / (kmh / 3.6) * 1000)
    travel(a, b, start, end, step_s)
    print(f"  {a} -> {b}: {dist:.0f} m, {(end - start) / 60000:.1f} min")
    return end
t = ms(DAY, 8, 40); stay("Home", ms(prev, 21, 30), t, every_min=20)
t2 = go("Home", "Office", t, 28, 2); stay("Office", t2, ms(DAY, 12, 10))
t3 = go("Office", "Sweetgreen", ms(DAY, 12, 10), 5, 7); stay("Sweetgreen", t3, ms(DAY, 12, 55), every_min=2)
t4 = go("Sweetgreen", "Office", ms(DAY, 12, 55), 5, 7); stay("Office", t4, ms(DAY, 17, 35))
t5 = go("Office", "QFC", ms(DAY, 17, 35), 32, 2); stay("QFC", t5, ms(DAY, 18, 22), every_min=2)
t6 = go("QFC", "Home", ms(DAY, 18, 22), 34, 2); stay("Home", t6, ms(DAY, 19, 25))
t7 = go("Home", "Volunteer Park", ms(DAY, 19, 25), 5, 7); stay("Volunteer Park", t7, ms(DAY, 20, 10), every_min=2)
t8 = go("Volunteer Park", "Home", ms(DAY, 20, 10), 5, 7)
stay("Home", t8, ms(DAY + dt.timedelta(days=1), 2, 0), every_min=20)
PARK_AT = t2 + 2 * 60000      # the parking paid two minutes after arriving

sql = ["BEGIN;"]
q = lambda s: "'" + str(s).replace("'", "''") + "'"
for t, la, lo, acc, sp in fixes:
    sql.append(f"INSERT INTO fix(time,latitude,longitude,accuracy,altitude,speed,bearing,provider) VALUES ({t},{la:.6f},{lo:.6f},{acc},NULL,{'NULL' if sp is None else round(sp,1)},NULL,'recorder');")
for name, (la, lo) in P.items():
    sql.append(f"INSERT INTO place(name,latitude,longitude,radius) VALUES ({q(name)},{la},{lo},60.0);")
BANK, APP = "com.infonow.bofa", "BofA"
def pay(t, amount, merchant):
    text = f"${amount:,.2f} Credit card 1234 {merchant.upper()}"
    sql.append(f"INSERT INTO payment(time,pkg,app,title,text,amount,currency,merchant,kind,verdict) VALUES ({t},{q(BANK)},{q(APP)},'Credit card charge',{q(text)},{amount},'USD',{q(merchant)},'purchase',0);")
pay(PARK_AT, 6.00, "PayByPhone Parking")
pay(ms(DAY, 12, 41), 14.60, "Sweetgreen")
pay(ms(DAY, 18, 15), 46.32, "QFC")
sql.append(f"INSERT INTO cost_item(at,amount,currency,what,cleared) VALUES ({ms(DAY,10,15)},4.50,'USD','Coffee',0);")
# The rest of the month, for the calendar: a day's purchases each, no positions.
month = {1: (23.40, "Trader Joe's"), 2: (61.85, "Target"), 3: (9.75, "Starbucks"), 4: (32.10, "Chipotle"),
         5: (118.40, "Costco"), 6: (14.25, "Starbucks"), 7: (52.90, "Safeway"), 8: (7.50, "Starbucks"),
         9: (27.30, "Pho Bac"), 11: (18.60, "Subway"), 12: (41.75, "Shell"), 13: (12.40, "Starbucks"),
         14: (86.20, "Whole Foods Market"), 15: (22.95, "Chipotle"), 16: (9.80, "Starbucks"), 17: (35.60, "Trader Joe's"),
         18: (15.25, "Sweetgreen")}
for d, (amt, m) in month.items():
    pay(ms(dt.date(2026, 11, d), 12, 30), amt, m)
# Screen time for the demo day: sessions, per-app totals, the day.
apps = {"com.google.android.gm": "Gmail", "com.google.android.apps.maps": "Maps", "com.android.chrome": "Chrome",
        "com.google.android.apps.messaging": "Messages", "com.google.android.youtube": "YouTube",
        "com.google.android.apps.photos": "Photos", "com.google.android.calendar": "Calendar",
        "com.google.android.apps.youtube.music": "YouTube Music"}
sessions = [("com.google.android.gm", 7, 50, 8, 5), ("com.google.android.apps.maps", 8, 41, 8, 50),
            ("com.android.chrome", 10, 5, 10, 22), ("com.google.android.apps.messaging", 11, 30, 11, 37),
            ("com.google.android.youtube", 12, 21, 12, 44), ("com.google.android.calendar", 13, 5, 13, 9),
            ("com.google.android.apps.youtube.music", 17, 36, 17, 45), ("com.google.android.apps.messaging", 18, 40, 18, 48),
            ("com.google.android.apps.photos", 19, 45, 19, 53), ("com.google.android.youtube", 21, 10, 21, 58),
            ("com.android.chrome", 22, 5, 22, 21)]
key = DAY.isoformat(); totals = {}
for pkg, h1, m1, h2, m2 in sessions:
    a, b = ms(DAY, h1, m1), ms(DAY, h2, m2); totals[pkg] = totals.get(pkg, 0) + (b - a)
    sql.append(f"INSERT INTO screen_session(day,packageName,label,startAt,endAt) VALUES ({q(key)},{q(pkg)},{q(apps[pkg])},{a},{b});")
for pkg, mss in totals.items():
    sql.append(f"INSERT INTO screen_app(day,packageName,label,millis) VALUES ({q(key)},{q(pkg)},{q(apps[pkg])},{mss});")
sql.append(f"INSERT INTO screen_day(day,totalMillis,unlocks,savedAt) VALUES ({q(key)},{sum(totals.values())},46,{ms(DAY,23,59)});")
sql.append("COMMIT;")
open("demo.sql", "w").write("\n".join(sql))
print(len(fixes), "positions,", len(month) + 3, "purchases,", len(sessions), "app sessions")
