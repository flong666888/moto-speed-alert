"""Fetch Taiwan roads (all with maxspeed + major roads) from Overpass in chunks.

Usage: python3 tools/fetch_roads.py <out_dir>   (resumable; then run tools/build_roads.py)
"""
import gzip, json, os, sys, time, urllib.parse, urllib.request

OUT = sys.argv[1]
# Optional: worker index / count / preferred endpoint index, to run several in parallel.
WORKER, WORKERS, PREF = (int(x) for x in sys.argv[2:5]) if len(sys.argv) > 4 else (0, 1, 0)
EPS = ["https://maps.mail.ru/osm/tools/overpass/api/interpreter",
       "https://overpass.private.coffee/api/interpreter",
       "https://overpass.kumi.systems/api/interpreter"]
MAJOR = "^(motorway|trunk|primary|secondary|tertiary|unclassified|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"
WITH_LIMIT = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"
STEP = 0.4
# Land areas only: main island, Penghu, Kinmen, Matsu (sea chunks still cost a busy server slot).
AREAS = [(21.8, 119.9, 25.4, 122.1), (23.0, 119.2, 23.8, 120.0), (24.2, 118.1, 24.6, 118.5), (25.8, 119.8, 26.4, 120.6)]
chunks = []
for s0, w0, n0, e0 in AREAS:
    lat = s0
    while lat < n0 - 1e-9:
        lon = w0
        while lon < e0 - 1e-9:
            chunks.append((round(lat, 2), round(lon, 2), round(min(lat + STEP, n0), 2), round(min(lon + STEP, e0), 2)))
            lon += STEP
        lat += STEP

def fetch(bb):
    s, w, n, e = bb
    q = (f'[out:json][timeout:180];(way["highway"~"{WITH_LIMIT}"]["maxspeed"]({s},{w},{n},{e});'
         f'way["highway"~"{MAJOR}"]({s},{w},{n},{e}););out tags geom qt;')
    data = urllib.parse.urlencode({"data": q}).encode()
    for attempt in range(8):
        ep = EPS[(PREF + attempt // 2) % len(EPS)]  # two tries per server, then the next
        try:
            req = urllib.request.Request(ep, data=data, headers={"User-Agent": "road-limit-extract/1.0", "Accept-Encoding": "gzip"})
            with urllib.request.urlopen(req, timeout=240) as r:
                raw = r.read()
                if r.headers.get("Content-Encoding") == "gzip":
                    raw = gzip.decompress(raw)
            d = json.loads(raw)
            if "remark" in d and "error" in d["remark"].lower():
                raise RuntimeError(d["remark"][:120])
            return raw
        except Exception as ex:
            print(f"  {bb} attempt {attempt} {ep.split('/')[2]}: {ex}", flush=True)
            time.sleep(15 + attempt * 5)
    return None

for i, bb in enumerate(chunks):
    if i % WORKERS != WORKER:
        continue
    path = os.path.join(OUT, "%s_%s.json.gz" % (bb[0], bb[1]))
    if os.path.exists(path):
        continue
    t = time.time()
    raw = fetch(bb)
    if raw is None:
        print(f"[{i+1}/{len(chunks)}] {bb} FAILED", flush=True)
        continue
    with gzip.open(path, "wb") as f:
        f.write(raw)
    print(f"[{i+1}/{len(chunks)}] {bb} {len(raw)//1024} KB {time.time()-t:.0f}s", flush=True)
print("DONE", flush=True)
