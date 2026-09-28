"""Trip-scoped pseudonyms for vector-privacy (ticket 13, adr-0065/adr-0068).

The k-anonymity floor counts **distinct trip pseudonyms**. That number is only
meaningful if a pseudonym corresponds to exactly one trip — no more, no less.
Both errors are real and they fail in opposite directions:

* **A pseudonym that spans several trips** links a person's journeys together,
  which defeats endpoint truncation: two truncated trips that share an id can be
  stitched back into a route through someone's day.
* **A pseudonym that covers only part of a trip** manufactures fake distinct
  trips, which defeats the K floor itself.

The second one was live. The web edge minted a fresh pseudonym on every
``POST /traces``, while the client uploads a batch every 30 fixes — so one
continuous drive arrived as five "distinct trips" and satisfied K=5 on its own.
Demonstrated before this module existed: 150 fixes, one journey, one segment,
one evidence row emitted claiming ``n_trips=5``.

So the trip boundary has to come from the only party that knows it — the client —
while the server still refuses to trust what the client sends. The resolution is
to accept the client's opaque trip token but never store it: the stored pseudonym
is a keyed hash of it, so

* it is **stable across every batch of one trip** (the property K needs),
* it is **unlinkable to the token** the client holds,
* one client cannot collide with or impersonate another's pseudonym, and
* rotating the server salt makes historical pseudonyms unlinkable to new ones.

**Threat model, stated plainly.** A client that invents a new token per batch can
still inflate its own apparent trip count. This is a self-hosted map where the
client is the user's own browser, so that "attack" costs the attacker their own
privacy floor and gains them nothing; it is not worth server-side heuristics that
would guess trip boundaries and get them wrong. What matters is that the *honest*
path is now correct, and that a client which sends no token at all is counted, not
silently accepted as a fresh trip.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import re
import secrets
from typing import Optional, Tuple

# A client trip token must look opaque and be long enough not to collide by
# accident, but we never parse or interpret it — only hash it. Rejecting odd
# shapes keeps anything identifying (an email, a device name) from being used as
# a token by a careless client build.
_TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{16,128}$")

SALT_ENV = "VECTOR_TRIP_SALT"
SALT_FILENAME = "trip_salt"

# Reported when a request carried no usable trip token, so the fallback is
# visible in the privacy counters rather than mistaken for a real trip.
REASON_NO_TRIP_TOKEN = "no_trip_token"


def mint_client_token() -> str:
    """A fresh opaque trip token. Clients mint this at trip START, not per batch."""
    return secrets.token_urlsafe(24)


def valid_client_token(token: object) -> bool:
    """Whether a client-supplied trip token is acceptably opaque."""
    return isinstance(token, str) and bool(_TOKEN_RE.match(token))


def load_or_create_salt(data_dir: str) -> bytes:
    """The server-side salt, from the environment or a file beside the store.

    Persisted rather than generated per process: a salt that changed on restart
    would split one trip's batches into different pseudonyms across a restart —
    reintroducing the very bug this module exists to fix, but only sometimes,
    which is worse.
    """
    env = os.environ.get(SALT_ENV)
    if env:
        return env.encode("utf-8")

    path = os.path.join(data_dir, SALT_FILENAME)
    try:
        with open(path, "rb") as fh:
            existing = fh.read().strip()
        if existing:
            return existing
    except (OSError, ValueError):
        # ValueError, not just OSError: a malformed path (embedded null) raises
        # that. Deriving a pseudonym must never fail on the ingest path.
        pass

    salt = secrets.token_hex(32).encode("ascii")
    try:
        os.makedirs(data_dir, exist_ok=True)
        # 0o600 where the platform honours it; Windows ignores the mode.
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            os.write(fd, salt)
        finally:
            os.close(fd)
    except (OSError, ValueError):
        # A salt we cannot persist is still better than no salt for this process;
        # the consequence is that a restart re-splits in-flight trips.
        pass
    return salt


def trip_pseudonym(client_token: Optional[str], salt: bytes) -> Tuple[str, Optional[str]]:
    """Derive the stored pseudonym from a client trip token.

    Returns ``(pseudonym, dropped_reason)``. The reason is non-None only when the
    client supplied nothing usable, in which case a fresh single-batch pseudonym
    is minted — the old behaviour — and the caller should count it so the gap is
    visible instead of silently inflating trip counts.

    The token itself is never returned and must never be stored: only this keyed
    digest of it goes to the quarantine store.
    """
    if not valid_client_token(client_token):
        return "trip_" + secrets.token_hex(16), REASON_NO_TRIP_TOKEN
    digest = hmac.new(salt, str(client_token).encode("utf-8"), hashlib.sha256).hexdigest()
    return "trip_" + digest[:32], None
