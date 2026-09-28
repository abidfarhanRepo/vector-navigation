// vector-common — shared domain models for the Node/TS tier (ADR-0007).
// Plain ESM JavaScript + JSDoc (no build step). Cross-language truth lives in vector-contracts.

export const EARTH_RADIUS_M = 6_371_000;

/** Clamp a number into [min, max]. */
export function clamp(value, min, max) {
  if (value < min) return min;
  if (value > max) return max;
  return value;
}

/** Degrees -> radians. */
export function deg2rad(deg) {
  return (deg * Math.PI) / 180;
}
