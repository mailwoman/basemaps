"""Extracts urban treetops from Meta and WRI's global canopy height map as GeoParquet points.

The canopy height map is 1 m GeoTIFF tiles named by zoom-9 quadkey in s3://dataforgood-fb-data. A treetop is a pixel
that is the tallest within MAX_WINDOW_METERS and at least MIN_HEIGHT_METERS tall. Pixels inside continuous canopy
are dropped: forests are drawn as land cover, and one point per crown there would add billions of trees.

Usage: python3 treetops.py OUTPUT.parquet [--cities NAME,...]
"""
import argparse
import math

import geopandas as gpd
import numpy as np
import rasterio
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

MIN_HEIGHT_METERS = 4
MAX_WINDOW_METERS = 5
# A pixel whose surrounding DENSE_WINDOW_METERS square is at least DENSE_COVER canopy belongs to a forest.
DENSE_WINDOW_METERS = 41
DENSE_COVER = 0.85
BLOCK = 4096
OVERLAP = DENSE_WINDOW_METERS


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
    args = parser.parse_args()

    records = []
    for name in args.cities.split(","):
        bbox = CITIES[name]
        before = len(records)
        for key in quadkeys(bbox):
            try:
                records.extend(treetops_in(f"{BUCKET}/{key}.tif", bbox))
            except rasterio.errors.RasterioIOError:
                print(f"{name}: no canopy tile {key}")
        print(f"{name}: {len(records) - before} treetops", flush=True)

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
