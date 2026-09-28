"""CLI entrypoint: print health and exit 0."""

from .health import health


def main() -> None:
    print(health())


if __name__ == "__main__":
    main()
