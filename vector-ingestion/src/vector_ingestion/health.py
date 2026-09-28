"""Trivial health check for vector-ingestion.

Runtime deferred to toolchain provisioning (adr-0006); committed so CI/repo is valid now.
"""


def health() -> dict:
    """Return a trivial health payload."""
    return {"status": "ok", "service": "vector-ingestion"}


if __name__ == "__main__":
    print(health())
