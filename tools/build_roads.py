"""Pack OpenStreetMap roads into the compact offline asset the app loads (roads.bin).

Input, either:
  - an OSM extract (.osm.pbf / .osm), e.g. https://download.geofabrik.de/asia/taiwan-latest.osm.pbf
    (needs `pip install osmium`); this is what the GitHub workflow uses, or
  - a directory of Overpass JSON responses (.json / .json.gz) written by tools/fetch_roads.py.

Kept: every road that has a speed limit, plus major roads without one (so the app does
not mistake which road you are on). Minor roads without a limit are left out.

Usage: python3 tools/build_roads.py <input> app/src/main/assets/roads.bin

Format (big-endian):
  magic "RDS1"
  u32 name_count, then per name: u16 byte_len + UTF-8 bytes
  u32 way_count, then per way:
    i32 name_index (-1 = none)
    u8  oneway (0 two-way, 1 forward, 2 reverse)
    u8  maxspeed, u8 maxspeed_forward, u8 maxspeed_backward   (0 = unknown, km/h)
    u16 node_count
    i32 lat0, i32 lon0 (degrees * 1e6), then (node_count-1) x (zigzag varint dlat, dlon)
"""
import gzip
import json
import os
import re
import struct
import sys

SPEED_RE = re.compile(r"^(\d+(?:\.\d+)?)\s*(mph|km/h|kmh|kph)?$")


def parse_speed(v):
    if not v:
        return 0
    m = SPEED_RE.match(v.split(";")[0].strip())
    if not m:
        return 0
    k = float(m.group(1)) * (1.609344 if m.group(2) == "mph" else 1)
    return int(round(k)) if 5 <= k <= 150 else 0


def speed(tags, key):
    return parse_speed(tags.get(key + ":motorcycle")) or parse_speed(tags.get(key))


def varint(n, out):
    n = (n << 1) ^ (n >> 63)  # zigzag
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


MAJOR = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
         "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"}
WITH_LIMIT = MAJOR | {"residential", "living_street", "road"}
LIMIT_KEYS = ("maxspeed", "maxspeed:forward", "maxspeed:backward", "maxspeed:motorcycle")


def wanted(tags):
    hw = tags.get("highway")
    if hw in MAJOR:
        return True
    return hw in WITH_LIMIT and any(k in tags for k in LIMIT_KEYS)


def load_osm(path):
    import osmium

    ways = {}

    class Handler(osmium.SimpleHandler):
        def way(self, w):
            if "highway" not in w.tags:
                return
            tags = {t.k: t.v for t in w.tags}
            if not wanted(tags):
                return
            geom = []
            for n in w.nodes:
                if n.location.valid():
                    geom.append({"lat": n.location.lat, "lon": n.location.lon})
            if len(geom) >= 2:
                ways[w.id] = {"id": w.id, "tags": tags, "geometry": geom}

    Handler().apply_file(path, locations=True, idx="flex_mem")
    return ways


def load(path):
    if not os.path.isdir(path):
        return load_osm(path)
    chunks_dir = path
    ways = {}
    for fn in sorted(os.listdir(chunks_dir)):
        path = os.path.join(chunks_dir, fn)
        opener = gzip.open if fn.endswith(".gz") else open
        with opener(path, "rb") as f:
            data = json.loads(f.read())
        for e in data.get("elements", []):
            if e.get("type") == "way" and len(e.get("geometry") or []) >= 2 and wanted(e.get("tags", {})):
                ways[e["id"]] = e
    return ways


def main(chunks_dir, dst):
    ways = load(chunks_dir)
    names, name_idx = [], {}
    body = bytearray()
    count = 0
    with_limit = 0
    for wid in sorted(ways):
        e = ways[wid]
        tags = e.get("tags", {})
        hw = tags.get("highway", "")
        ow = tags.get("oneway", "")
        if ow in ("yes", "1", "true"):
            oneway = 1
        elif ow in ("-1", "reverse"):
            oneway = 2
        elif ow == "" and (hw == "motorway" or tags.get("junction") == "roundabout"):
            oneway = 1
        else:
            oneway = 0
        ms, fw, bw = speed(tags, "maxspeed"), speed(tags, "maxspeed:forward"), speed(tags, "maxspeed:backward")
        if ms or fw or bw:
            with_limit += 1
        name = "".join(ch for ch in tags.get("name", "") if ord(ch) < 0x10000)[:60]
        ni = -1
        if name:
            if name not in name_idx:
                name_idx[name] = len(names)
                names.append(name)
            ni = name_idx[name]
        geom = e["geometry"][:65535]
        body += struct.pack(">iBBBBH", ni, oneway, ms, fw, bw, len(geom))
        plat = round(geom[0]["lat"] * 1e6)
        plon = round(geom[0]["lon"] * 1e6)
        body += struct.pack(">ii", plat, plon)
        for g in geom[1:]:
            la, lo = round(g["lat"] * 1e6), round(g["lon"] * 1e6)
            varint(la - plat, body)
            varint(lo - plon, body)
            plat, plon = la, lo
        count += 1

    out = bytearray(b"RDS1")
    out += struct.pack(">I", len(names))
    for n in names:
        b = n.encode("utf-8")
        out += struct.pack(">H", len(b)) + b
    out += struct.pack(">I", count)
    out += body
    with open(dst, "wb") as f:
        f.write(out)
    print(f"wrote {count} roads ({with_limit} with a speed limit), {len(names)} names, {len(out)/1e6:.1f} MB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
