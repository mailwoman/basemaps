#!/usr/bin/env bash
# Builds a regional basemap PMTiles on an EC2 instance and uploads it to R2.
#
# Runs as EC2 user data on Amazon Linux 2023 (arm64). The instance terminates when this script exits, whether the
# build succeeded or failed, and a timer terminates it after MAX_HOURS regardless.
#
# Inputs come from the instance tags and SSM Parameter Store:
#   SSM /mailwoman/basemaps/r2-access-key-id, r2-secret-access-key, r2-endpoint  R2 S3 credentials
#   Environment (set at the top of user data): AREA, BBOX, BRANCH, OUTPUT_KEY, MAX_HOURS
set -euo pipefail

: "${AREA:?Geofabrik area name, such as japan}"
: "${GEOFABRIK_PATH:?Geofabrik download path without the suffix, such as asia/japan}"
: "${BBOX:?xmin,ymin,xmax,ymax for the Overture buildings query}"
: "${BRANCH:=main}"
: "${OUTPUT_KEY:?R2 key for the PMTiles, such as protomaps/builds/japan.pmtiles}"
: "${MAX_HOURS:=8}"
: "${BUCKET:=nexus-public}"

LOG=/var/log/basemap-build.log
exec > >(tee -a "$LOG") 2>&1

# The log goes to R2 beside the output on every exit, because the instance and its disk are gone afterwards.
finish() {
  local status=$?
  echo "build exited with status $status"
  if command -v rclone > /dev/null && [[ -n "${RCLONE_S3_ENDPOINT:-}" ]]; then
    rclone copyto "$LOG" ":s3:${BUCKET}/${OUTPUT_KEY%.pmtiles}.log" || true
  fi
  shutdown -h now
}
trap finish EXIT
shutdown -h "+$((MAX_HOURS * 60))"

export AWS_REGION="${AWS_REGION:-us-west-2}"
# User data runs without HOME, and DuckDB needs one to install its httpfs extension.
export HOME="${HOME:-/root}"
WORK=/mnt/build
mkdir -p "$WORK"
cd "$WORK"

echo "== packages"
dnf install -y -q git java-21-amazon-corretto-devel maven-amazon-corretto21 unzip python3-pip
curl -fsSL -o duckdb.zip https://github.com/duckdb/duckdb/releases/download/v1.2.2/duckdb_cli-linux-aarch64.zip
unzip -o -q duckdb.zip && install duckdb /usr/local/bin/duckdb
curl -fsSL https://rclone.org/install.sh | bash > /dev/null

param() { aws ssm get-parameter --with-decryption --name "/mailwoman/basemaps/$1" --query Parameter.Value --output text; }
export RCLONE_S3_PROVIDER=Cloudflare RCLONE_S3_REGION=auto RCLONE_S3_NO_CHECK_BUCKET=true
RCLONE_S3_ACCESS_KEY_ID=$(param r2-access-key-id)
RCLONE_S3_SECRET_ACCESS_KEY=$(param r2-secret-access-key)
RCLONE_S3_ENDPOINT=$(param r2-endpoint)
export RCLONE_S3_ACCESS_KEY_ID RCLONE_S3_SECRET_ACCESS_KEY RCLONE_S3_ENDPOINT

echo "== source"
git clone --depth 1 --branch "$BRANCH" https://github.com/mailwoman/basemaps.git
cd basemaps/tiles
mvn -B -ntp clean package
mkdir -p data/sources

JAR=target/protomaps-basemap-HEAD-with-deps.jar

# The OSM extract and the other Planetiler inputs download while DuckDB pulls the Overture buildings.
# QRank ranks POIs by Wikidata popularity to choose their minimum zoom. Its host is often unreachable, and
# Planetiler's downloader then fails the whole build. curl fetches it first; when that fails, a header-only file
# lets the build continue with every POI at its kind's default zoom. Planetiler skips a file that already exists.
if ! curl -fsSL --retry 2 --connect-timeout 30 --max-time 900 -o data/sources/qrank.csv.gz \
  https://qrank.toolforge.org/download/qrank.csv.gz; then
  echo "QRank unavailable; building without POI popularity ranks"
  printf 'Entity,QRank\n' | gzip > data/sources/qrank.csv.gz
fi

echo "== planetiler inputs (background)"
# Planetiler's downloader fails the whole build when one size request times out, which has happened with
# Geofabrik, QRank and osmdata.openstreetmap.de. curl fetches every input with retries instead, and Planetiler
# runs without --download. Land and ocean polygons come from Overture below (--overture_base).
fetch() {
  curl -fsSL --retry 5 --retry-delay 15 --retry-all-errors --connect-timeout 60 -o "data/sources/$1" "$2"
}
(
  fetch "${AREA}.osm.pbf" "https://download.geofabrik.de/${GEOFABRIK_PATH}-latest.osm.pbf" &
  fetch natural_earth_vector.gpkg.zip https://naciscdn.org/naturalearth/packages/natural_earth_vector.gpkg.zip &
  fetch daylight-landcover.gpkg https://r2-public.protomaps.com/datasets/daylight-landcover.gpkg &
  fetch pgf-encoding.zip https://wipfli.github.io/pgf-encoding/pgf-encoding.zip &
  status=0
  for job in $(jobs -p); do wait "$job" || status=1; done
  exit "$status"
) > planetiler-download.log 2>&1 &
DOWNLOAD_PID=$!

echo "== canopy treetops (background)"
(
  python3 -m pip install -q geopandas pyarrow rasterio scipy pyproj shapely &&
    AWS_NO_SIGN_REQUEST=YES AWS_REGION=us-east-1 GDAL_DISABLE_READDIR_ON_OPEN=EMPTY_DIR \
      python3 aws/treetops.py "data/sources/${AREA}-treetops.parquet"
) > treetops.log 2>&1 &
TREETOPS_PID=$!

echo "== overture buildings"
RELEASE=$(duckdb -noheader -list -c "SELECT latest FROM read_json('https://stac.overturemaps.org/catalog.json')")
IFS=',' read -r XMIN YMIN XMAX YMAX <<< "$BBOX"
echo "release $RELEASE bbox $BBOX"
# The DuckDB CLI exits 0 after a failed statement, so each step checks its own result.
[[ -n "$RELEASE" ]] || { echo "no Overture release found"; exit 1; }
duckdb -c "
INSTALL httpfs; LOAD httpfs;
SET s3_region='us-west-2';
COPY (
  SELECT *
  FROM read_parquet(
    's3://overturemaps-us-west-2/release/${RELEASE}/theme=buildings/*/*.parquet',
    hive_partitioning=1, union_by_name=1
  )
  WHERE bbox.xmin <= ${XMAX} AND bbox.xmax >= ${XMIN}
    AND bbox.ymin <= ${YMAX} AND bbox.ymax >= ${YMIN}
) TO 'data/sources/${AREA}-buildings.parquet' (FORMAT PARQUET);"
[[ -s "data/sources/${AREA}-buildings.parquet" ]] || { echo "Overture buildings query wrote no file"; exit 1; }
duckdb -c "SELECT type, count(*) FROM 'data/sources/${AREA}-buildings.parquet' GROUP BY type"

echo "== overture land and ocean"
duckdb -c "
INSTALL httpfs; LOAD httpfs;
SET s3_region='us-west-2';
COPY (
  SELECT *
  FROM read_parquet(
    's3://overturemaps-us-west-2/release/${RELEASE}/theme=base/type=*/*.parquet',
    hive_partitioning=1, union_by_name=1
  )
  WHERE ((type = 'land' AND subtype = 'land') OR (type = 'water' AND subtype = 'ocean'))
    AND bbox.xmin <= ${XMAX} AND bbox.xmax >= ${XMIN}
    AND bbox.ymin <= ${YMAX} AND bbox.ymax >= ${YMIN}
) TO 'data/sources/${AREA}-base.parquet' (FORMAT PARQUET);"
duckdb -c "SELECT type, subtype, count(*) FROM 'data/sources/${AREA}-base.parquet' GROUP BY ALL" | tee base-counts.txt
grep -q " ocean " base-counts.txt || { echo "Overture base query returned no ocean polygons"; exit 1; }
grep -q " land " base-counts.txt || { echo "Overture base query returned no land polygons"; exit 1; }
echo "== waiting for planetiler inputs"
if ! wait "$DOWNLOAD_PID"; then
  tail -n 40 planetiler-download.log
  exit 1
fi
echo "== waiting for treetops"
TREETOP_ARGS=()
if wait "$TREETOPS_PID" && [[ -s "data/sources/${AREA}-treetops.parquet" ]]; then
  TREETOP_ARGS=(--treetops="data/sources/${AREA}-treetops.parquet")
else
  echo "treetop extraction failed; building with OSM trees only"
fi
tail -n 15 treetops.log
ls -la data/sources

echo "== planetiler"
java -Xmx24g -jar "$JAR" \
  --area="$AREA" \
  --overture_buildings="data/sources/${AREA}-buildings.parquet" \
  --overture_base="data/sources/${AREA}-base.parquet" \
  --download_osm_tile_weights=false \
  "${TREETOP_ARGS[@]}" \
  --storage=mmap --nodemap-type=array --force
ls -la "${AREA}.pmtiles"

echo "== upload"
rclone copyto --s3-chunk-size 64M --s3-upload-concurrency 8 "${AREA}.pmtiles" ":s3:${BUCKET}/${OUTPUT_KEY}"
echo "uploaded ${BUCKET}/${OUTPUT_KEY}"
