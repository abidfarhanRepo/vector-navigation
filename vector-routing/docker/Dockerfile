FROM python:3.11-slim
WORKDIR /app
COPY src/vector_routing /app/vector_routing
COPY vendor/vector_geo /app/vector_geo
COPY vendor/vector_auth /app/vector_auth
COPY vendor/vector_bus_client /app/vector_bus_client
COPY routing-data /app/routing-data
ENV PYTHONPATH=/app
ENV ROUTING_GRAPH=/app/routing-data/sample_network.geojson
EXPOSE 8081
CMD ["python", "-m", "vector_routing.serve", "--port", "8081"]
