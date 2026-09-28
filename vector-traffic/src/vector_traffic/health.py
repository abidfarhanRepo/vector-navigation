"""Trivial health check for vector-traffic.

Runtime is available via the provisioned Python toolchain (adr-0006).
"""


def health() -> dict:
    """Return a trivial health payload."""
    return {"status": "ok", "service": "vector-traffic"}


if __name__ == "__main__":
    print(health())
