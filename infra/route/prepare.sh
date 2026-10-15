#!/usr/bin/env bash
# Downloads the South Korea road map (Geofabrik, about 250 MB) and builds the OSRM files (MLD) in ./data.
# Needs Docker with about 5 GB of memory. The files stay local: data/ is ignored by git.
set -euo pipefail
cd "$(dirname "$0")"
image="${OSRM_IMAGE:-ghcr.io/project-osrm/osrm-backend:v5.27.1}"
mkdir -p data
pbf=data/south-korea-latest.osm.pbf
if [ ! -f "$pbf" ]; then
  curl -fL --retry 3 -o "$pbf.part" https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
  mv "$pbf.part" "$pbf"
fi
run() { docker run --rm -v "$PWD/data:/data" "$image" "$@"; }
run osrm-extract -p /opt/car.lua /data/south-korea-latest.osm.pbf
run osrm-partition /data/south-korea-latest.osrm
run osrm-customize /data/south-korea-latest.osrm
echo "OSRM files are ready in $(pwd)/data. Start with: docker compose up -d"
