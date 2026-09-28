"""Trivial health check for vector-tile-gen.

Runtime deferred to toolchain provisioning (adr-0006); committed so CI/repo is valid now.
"""


def health() -> dict:
    """Return a trivial health payload."""
    return {"status": "ok", "service": "vector-tile-gen"}


if __name__ == "__main__":
    print(health())
