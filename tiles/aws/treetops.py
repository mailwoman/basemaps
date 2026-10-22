"""Extracts urban treetops from Meta and WRI's global canopy height map as GeoParquet points.

The canopy height map is 1 m GeoTIFF tiles named by zoom-9 quadkey in s3://dataforgood-fb-data. A treetop is a pixel
that is the tallest within MAX_WINDOW_METERS and at least MIN_HEIGHT_METERS tall. Pixels inside continuous canopy
are dropped: forests are drawn as land cover, and one point per crown there would add billions of trees.

A canopy peak over a carriageway is a crown overhanging the road, and a trunk drawn there would stand in traffic.
With --roads, treetops within a road's half-width plus ROAD_MARGIN_METERS of its centerline are dropped.

Usage: python3 treetops.py OUTPUT.parquet [--cities NAME,...] [--roads ROADS.parquet]
"""
import argparse
import math

import geopandas as gpd
import numpy as np
import pyarrow.parquet as pq
import rasterio
import shapely
from pyproj import Transformer
from rasterio.windows import Window, from_bounds
from scipy import ndimage
from shapely.geometry import Point

BUCKET = "/vsis3/dataforgood-fb-data/forests/v1/alsgedi_global_v6_float/chm"

# lon_min, lat_min, lon_max, lat_max
CITIES = {
    "tokyo": (139.56, 35.52, 139.92, 35.82),
    "yokohama": (139.47, 35.31, 139.72, 35.59),
    "osaka": (135.38, 34.55, 135.62, 34.80),
    "kobe": (135.09, 34.63, 135.30, 34.75),
    "kyoto": (135.65, 34.92, 135.84, 35.08),
    "nagoya": (136.79, 35.04, 137.06, 35.27),
    "sapporo": (141.23, 42.97, 141.47, 43.13),
    "sendai": (140.80, 38.20, 140.97, 38.33),
    "fukuoka": (130.30, 33.54, 130.47, 33.67),
    "kagoshima": (130.48, 31.52, 130.60, 31.65),
}

MIN_HEIGHT_METERS = 5
# One treetop per crown: a peak must be the tallest pixel across a typical street-tree crown.
MAX_WINDOW_METERS = 9
# A crown is domed: its peak stands DOME_METERS above the mean of the pixels around it. A flat roof misread as
# canopy has a peak level with its surroundings.
DOME_WINDOW_METERS = 5
DOME_METERS = 1.0
# A pixel whose surrounding DENSE_WINDOW_METERS square is at least DENSE_COVER canopy belongs to a forest.
DENSE_WINDOW_METERS = 41
DENSE_COVER = 0.6
# Two tile features per treetop make a large total expensive to build and serve, so the build skips treetops above
# this count rather than shipping them.
MAX_TREETOPS = 8_000_000
BLOCK = 4096
OVERLAP = DENSE_WINDOW_METERS

# Carriageway width by Overture road class, in meters. Unlisted classes use DEFAULT_ROAD_WIDTH_METERS.
ROAD_WIDTH_METERS = {
    "motorway": 14,
    "trunk": 12,
    "primary": 10,
    "secondary": 8,
    "tertiary": 7,
    "residential": 5,
    "unclassified": 5,
    "living_street": 4,
    "service": 4,
}
DEFAULT_ROAD_WIDTH_METERS = 3
ROAD_MARGIN_METERS = 1.5
# Footways and paths run through parks under continuous canopy, so they do not exclude trees.
UNFILTERED_ROAD_CLASSES = {"footway", "path", "pedestrian", "cycleway", "steps", "bridleway", "track"}


def on_road(lons, lats, roads):
    """Boolean mask of the points within their road distance of any road line, measured in local meters."""
    lat0 = float(np.mean(lats))
    sx, sy = 111_320 * math.cos(math.radians(lat0)), 110_540

    def local(geoms):
        return shapely.transform(geoms, lambda xy: xy * [sx, sy])

    points = shapely.points(np.asarray(lons) * sx, np.asarray(lats) * sy)
    hit = np.zeros(len(points), dtype=bool)
    for width, lines in roads.items():
        tree = shapely.STRtree(local(lines))
        idx, _ = tree.query(points, predicate="dwithin", distance=width / 2 + ROAD_MARGIN_METERS)
        hit[np.unique(idx)] = True
    return hit


def load_roads(path):
    """Road centerlines and their carriageway widths, excluding paths."""
    table = pq.read_table(path, columns=["class", "geometry"]).to_pydict()
    keep = [cls not in UNFILTERED_ROAD_CLASSES for cls in table["class"]]
    geoms = shapely.from_wkb([g for g, k in zip(table["geometry"], keep) if k])
    widths = np.array(
        [ROAD_WIDTH_METERS.get(c, DEFAULT_ROAD_WIDTH_METERS) for c, k in zip(table["class"], keep) if k]
    )
    return geoms, widths


def roads_in(roads, bbox):
    """The roads inside a lon/lat box, grouped by carriageway width."""
    geoms, widths = roads
    inside = shapely.intersects(geoms, shapely.box(*bbox))
    return {int(w): geoms[inside & (widths == w)] for w in np.unique(widths[inside])}


def quadkeys(bbox, zoom=9):
    """Zoom-9 quadkeys of the Web Mercator tiles covering a lon/lat box."""

    def tile(lon, lat):
        n = 2**zoom
        x = int((lon + 180) / 360 * n)
        y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
        return x, y

    x0, y0 = tile(bbox[0], bbox[3])
    x1, y1 = tile(bbox[2], bbox[1])
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            key = ""
            for z in range(zoom, 0, -1):
                digit = 0
                mask = 1 << (z - 1)
                if x & mask:
                    digit += 1
                if y & mask:
                    digit += 2
                key += str(digit)
            yield key


def treetops_in(path, bbox):
    """Yields (lon, lat, height) for treetops of one canopy tile inside a lon/lat box."""
    with rasterio.open(path) as src:
        to_src = Transformer.from_crs("EPSG:4326", src.crs, always_xy=True)
        to_lonlat = Transformer.from_crs(src.crs, "EPSG:4326", always_xy=True)
        left, bottom = to_src.transform(bbox[0], bbox[1])
        right, top = to_src.transform(bbox[2], bbox[3])
        window = from_bounds(left, bottom, right, top, src.transform).round_offsets().round_lengths()
        window = window.intersection(Window(0, 0, src.width, src.height))
        pixel = abs(src.transform.a)
        size_max = max(3, int(round(MAX_WINDOW_METERS / pixel)) | 1)
        size_dense = max(3, int(round(DENSE_WINDOW_METERS / pixel)) | 1)
        size_dome = max(3, int(round(DOME_WINDOW_METERS / pixel)) | 1)
        for row in range(int(window.row_off), int(window.row_off + window.height), BLOCK):
            for col in range(int(window.col_off), int(window.col_off + window.width), BLOCK):
                block = Window(col - OVERLAP, row - OVERLAP, BLOCK + 2 * OVERLAP, BLOCK + 2 * OVERLAP)
                block = block.intersection(Window(0, 0, src.width, src.height))
                chm = src.read(1, window=block, masked=True).filled(0).astype(np.float32)
                if chm.max() < MIN_HEIGHT_METERS:
                    continue
                peaks = (chm == ndimage.maximum_filter(chm, size=size_max)) & (chm >= MIN_HEIGHT_METERS)
                cover = ndimage.uniform_filter((chm >= 2).astype(np.float32), size=size_dense)
                peaks &= cover < DENSE_COVER
                peaks &= chm - ndimage.uniform_filter(chm, size=size_dome) >= DOME_METERS
                # Keep only peaks in this block's own area, so overlapping blocks do not repeat a tree.
                inner_r0, inner_c0 = row - int(block.row_off), col - int(block.col_off)
                keep = np.zeros_like(peaks)
                keep[max(inner_r0, 0) : inner_r0 + BLOCK, max(inner_c0, 0) : inner_c0 + BLOCK] = True
                rows, cols = np.nonzero(peaks & keep)
                if rows.size == 0:
                    continue
                xs, ys = rasterio.transform.xy(
                    src.window_transform(block), rows, cols, offset="center"
                )
                lons, lats = to_lonlat.transform(np.asarray(xs), np.asarray(ys))
                inside = (lons >= bbox[0]) & (lons <= bbox[2]) & (lats >= bbox[1]) & (lats <= bbox[3])
                yield from zip(lons[inside], lats[inside], chm[rows, cols][inside])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output")
    parser.add_argument("--cities", default=",".join(CITIES))
    parser.add_argument("--roads")
    args = parser.parse_args()

    roads = load_roads(args.roads) if args.roads else None
    if roads is not None:
        print(f"{len(roads[0])} road centerlines loaded", flush=True)

    records = []
    for name in args.cities.split(","):
        bbox = CITIES[name]
        found = []
        for key in quadkeys(bbox):
            try:
                found.extend(treetops_in(f"{BUCKET}/{key}.tif", bbox))
            except rasterio.errors.RasterioIOError:
                print(f"{name}: no canopy tile {key}")
        dropped = 0
        if roads is not None and found:
            lons, lats, _ = zip(*found)
            hit = on_road(lons, lats, roads_in(roads, bbox))
            dropped = int(hit.sum())
            found = [record for record, drop in zip(found, hit) if not drop]
        records.extend(found)
        print(f"{name}: {len(found)} treetops kept, {dropped} dropped as on a road", flush=True)

    if len(records) > MAX_TREETOPS:
        raise SystemExit(f"{len(records)} treetops exceeds MAX_TREETOPS={MAX_TREETOPS}; not writing {args.output}")

    frame = gpd.GeoDataFrame(
        {
            "id": np.arange(len(records), dtype=np.int64),
            "height": [round(float(h), 1) for _, _, h in records],
        },
        geometry=[Point(lon, lat) for lon, lat, _ in records],
        crs="EPSG:4326",
    )
    frame.to_parquet(args.output)
    print(f"wrote {len(frame)} treetops to {args.output}")


if __name__ == "__main__":
    main()
