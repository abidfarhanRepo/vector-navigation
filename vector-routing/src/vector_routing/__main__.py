"""CLI entrypoint: build a tiny sample graph, run one route, print a summary."""

from .graph import RoutingGraph
from .service import RoutingService


def main() -> None:
    g = RoutingGraph()
    g.add_way(
        [(0.0, 0.0), (0.001, 0.0), (0.002, 0.0)],
        {"highway": "primary", "maxspeed_kmh": 50},
    )
    g.add_way(
        [(0.0, 0.0), (0.0, 0.001), (0.0, 0.002)],
        {"highway": "residential", "maxspeed_kmh": 30},
    )
    g.add_way(
        [(0.002, 0.0), (0.002, 0.001), (0.002, 0.002)],
        {"highway": "residential", "maxspeed_kmh": 30},
    )
    g.add_way(
        [(0.0, 0.002), (0.001, 0.002), (0.002, 0.002)],
        {"highway": "primary", "maxspeed_kmh": 50},
    )
    g.add_way([(0.001, 0.0), (0.001, 0.001)], {"highway": "street", "maxspeed_kmh": 30})
    g.add_way([(0.001, 0.001), (0.001, 0.002)], {"highway": "street", "maxspeed_kmh": 30})
    g.add_way([(0.0, 0.001), (0.001, 0.001)], {"highway": "street", "maxspeed_kmh": 30})
    g.add_way([(0.001, 0.001), (0.002, 0.001)], {"highway": "street", "maxspeed_kmh": 30})

    svc = RoutingService(g)
    route = svc.route((0.0, 0.0), (0.002, 0.002))
    summary = {
        "status": "ok",
        "service": "vector-routing",
        "nodes": len(g.nodes()),
        "edges": g.edge_count(),
        "sample_distance_m": round(route.distance_m, 1),
        "sample_duration_s": round(route.duration_s, 1),
        "sample_waypoints": route.waypoint_count,
    }
    print(summary)


if __name__ == "__main__":
    main()
