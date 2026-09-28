FROM python:3.11-slim

WORKDIR /app
COPY src/vector_geocoder /app/vector_geocoder
COPY requirements.txt /app/requirements.txt
ENV PYTHONPATH=/app

EXPOSE 8085
# Index the mounted basemap at startup and serve /search.
# The basemap GeoJSON is mounted read-only from the host. Alongside it, if
# present, <region>_places.geojson (the Overture POI layer produced by
# scripts/fetch_overture_places.py) is picked up automatically.
#
# CMD, not ENTRYPOINT: docker-compose overrides `command` to point --index at
# the real region file. With an exec-form ENTRYPOINT that override was APPENDED
# instead of replacing, handing argparse a second copy of the whole command
# line — the container could never start.
CMD ["python", "-m", "vector_geocoder.serve", "--port", "8085", "--index", "/app/basemap.geojson"]
