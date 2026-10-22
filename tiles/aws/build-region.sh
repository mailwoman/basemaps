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
: "${BBOX:?xmin,ymin,xmax,ymax for the Overture buildings query}"
: "${BRANCH:=main}"
: "${OUTPUT_KEY:?R2 key for the PMTiles, such as protomaps/builds/japan.pmtiles}"
: "${MAX_HOURS:=8}"
: "${BUCKET:=nexus-public}"

exec > >(tee -a /var/log/basemap-build.log) 2>&1
trap 'echo "build exited with status $?"; shutdown -h now' EXIT
shutdown -h "+$((MAX_HOURS * 60))"

export AWS_REGION="${AWS_REGION:-us-west-2}"
WORK=/mnt/build
mkdir -p "$WORK"
cd "$WORK"

echo "== packages"
dnf install -y -q git java-21-amazon-corretto-devel maven-amazon-corretto21 unzip
curl -fsSL -o duckdb.zip https://github.com/duckdb/duckdb/releases/download/v1.2.2/duckdb_cli-linux-aarch64.zip
unzip -o -q duckdb.zip && install duckdb /usr/local/bin/duckdb
curl -fsSL https://rclone.org/install.sh | bash

echo "== source"
git clone --depth 1 --branch "$BRANCH" https://github.com/mailwoman/basemaps.git
cd basemaps/tiles
mvn -q clean package
mkdir -p data/sources

echo "== overture buildings"
RELEASE=$(duckdb -noheader -list -c "SELECT latest FROM read_json('https://stac.overturemaps.org/catalog.json')")
IFS=',' read -r XMIN YMIN XMAX YMAX <<< "$BBOX"
echo "release $RELEASE bbox $BBOX"
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
ls -la data/sources

echo "== planetiler"
java -Xmx24g -jar target/protomaps-basemap-HEAD-with-deps.jar \
  --area="$AREA" --download \
  --overture_buildings="data/sources/${AREA}-buildings.parquet" \
  --storage=mmap --nodemap-type=array --force
ls -la "${AREA}.pmtiles"

echo "== upload"
param() { aws ssm get-parameter --with-decryption --name "/mailwoman/basemaps/$1" --query Parameter.Value --output text; }
export RCLONE_S3_PROVIDER=Cloudflare RCLONE_S3_REGION=auto RCLONE_S3_NO_CHECK_BUCKET=true
RCLONE_S3_ACCESS_KEY_ID=$(param r2-access-key-id)
RCLONE_S3_SECRET_ACCESS_KEY=$(param r2-secret-access-key)
RCLONE_S3_ENDPOINT=$(param r2-endpoint)
export RCLONE_S3_ACCESS_KEY_ID RCLONE_S3_SECRET_ACCESS_KEY RCLONE_S3_ENDPOINT
rclone copyto --s3-chunk-size 64M --s3-upload-concurrency 8 "${AREA}.pmtiles" ":s3:${BUCKET}/${OUTPUT_KEY}"
echo "uploaded ${BUCKET}/${OUTPUT_KEY}"
