"""vector-auth — shared service-layer HTTP auth for Vector engines.

Stdlib-only, zero sibling-repo imports (adr-0003). Provides a single source of
truth for bearer-token enforcement and scoped CORS used by every Python engine
HTTP service. Consuming engines vendor this package (see scripts/sync_vendor.py)
so each stays self-contained for its per-repo CI gate (adr-0007).
"""

from .auth import Auth

__all__ = ["Auth"]
