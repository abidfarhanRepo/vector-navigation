// Validation helpers tying shared types to their rules (vector-contracts/validation/validation-rules.yaml).
import { Coordinate, LAT_MIN, LAT_MAX, LON_MIN, LON_MAX } from './coords.js';
import { BoundingBox } from './geometry.js';
import { VectorError, errorFromCode } from './errors.js';

/**
 * Assert a value is a valid Coordinate; throws VectorError(VEC-0001) otherwise.
 * @param {*} v
 * @returns {Coordinate}
 */
export function assertCoordinate(v) {
  if (v instanceof Coordinate) return v;
  try {
    return Coordinate.fromObject(v);
  } catch (e) {
    throw errorFromCode('VEC-0001', { detail: `coordinate: ${e.message}`, cause: e });
  }
}

/**
 * Assert a value is a valid BoundingBox; throws VectorError(VEC-0001) otherwise.
 * @param {*} v object {minLat,minLon,maxLat,maxLon}
 * @returns {BoundingBox}
 */
export function assertBoundingBox(v) {
  if (v instanceof BoundingBox) return v;
  try {
    return new BoundingBox(v.minLat, v.minLon, v.maxLat, v.maxLon);
  } catch (e) {
    throw errorFromCode('VEC-0001', { detail: `boundingBox: ${e.message}`, cause: e });
  }
}

/**
 * Generic range assertion used by callers needing custom bounds.
 * @param {number} value
 * @param {number} min
 * @param {number} max
 * @param {string} label
 */
export function assertRange(value, min, max, label) {
  if (typeof value !== 'number' || value < min || value > max) {
    throw errorFromCode('VEC-0001', { detail: `${label} ${value} out of [${min}, ${max}]` });
  }
}
