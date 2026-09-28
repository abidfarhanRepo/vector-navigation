FROM python:3.11-slim

WORKDIR /app
COPY src/vector_traffic /app/vector_traffic
COPY vendor/vector_geo /app/vector_geo
COPY vendor/vector_auth /app/vector_auth
COPY vendor/vector_bus_client /app/vector_bus_client
COPY traffic-data /app/traffic-data
COPY requirements.txt /app/requirements.txt

# PostGIS backend (W43) requires psycopg; install so the production store works
# when VECTOR_PG_DSN is set. Falls back to in-memory when unset.
RUN pip install --no-cache-dir -r /app/requirements.txt

ENV PYTHONPATH=/app
ENV TRAFFIC_DATA=/app/traffic-data
EXPOSE 8084
CMD ["python", "-m", "vector_traffic.serve", "--port", "8084"]
