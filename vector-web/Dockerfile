# vector-web — composition root (Wave 26e/26f) as a Docker container,
# matching the system's other service containers.
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app/src:/app/vendor

WORKDIR /app

# Copy the package source, vendored deps, static viewer, and tests (tests are
# tiny; keeping them lets `docker exec` run a sanity check if needed).
COPY src/ ./src/
COPY vendor/ ./vendor/
COPY static/ ./static/
COPY tests/ ./tests/

EXPOSE 8080

# Default backend URLs point at the host via host.docker.internal; override in
# compose/env for a fully containerized stack. Port 8080 inside the container
# is published to a free host port (8080 is occupied by another container).
CMD ["python", "-m", "vector_web", "--port", "8080"]
