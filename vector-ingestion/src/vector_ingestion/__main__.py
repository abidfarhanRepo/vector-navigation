"""CLI entrypoint for vector-ingestion.

Subcommands:
  health            print a health string and exit 0
  osm-to-graph      convert a real .osm extract into a routing-graph GeoJSON
"""

import sys

from .health import health


def main(argv=None):
    """Run a vector-ingestion subcommand.

    Returns 0 on success (or None for the default health command, which the
    health test asserts), or a non-zero int on converter failure.
    """
    if not argv:
        argv = sys.argv[1:]
    if argv and argv[0] in ("health", "ping"):
        print(health())
        return 0
    if argv and argv[0] == "osm-to-graph":
        from .convert import main as convert_main

        return convert_main(argv[1:])
    # Default: health (backward compatible with the prior single-command CLI).
    print(health())


if __name__ == "__main__":
    raise SystemExit(main())
