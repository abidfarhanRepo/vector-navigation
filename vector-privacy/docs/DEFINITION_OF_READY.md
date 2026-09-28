# Definition of Ready — vector-privacy

A ticket is ready when:
- It names the rule(s) affected and the reason a payload must be dropped.
- It specifies the threshold as an adr-0065 decision (or a proposed change to
  that ADR), not a magic number.
- The privacy rationale is stated ("learn from aggregates, never from
  individual traces").
- Edge cases are enumerated: malformed input, out-of-range coordinates,
  accuracy just above/below the floor, sub-400 m tracks.
