// Coordinate — WGS84 lat/lon. Mirrors proto Coordinate / schemas/coordinate.schema.json.
import { deg2rad } from './units.js';

export const LAT_MIN = -90;
export const LAT_MAX = 90;
export const LON_MIN = -180;
export const LON_MAX = 180;
const EARTH_RADIUS_M = 6_371_000;

/**
 * A WGS84 geographic coordinate.
 * @typedef {Object} CoordinateLike
 * @property {number} lat
 * @property {number} lon
 */

export class Coordinate {
  /**
   * @param {number} lat latitude in degrees [-90, 90]
   * @param {number} lon longitude in degrees [-180, 180]
   */
  constructor(lat, lon) {
    /** @type {number} */
    this.lat = lat;
    /** @type {number} */
    this.lon = lon;
    this.#assert();
  }

  #assert() {
    if (typeof this.lat !== 'number' || !Number.isFinite(this.lat)) {
      throw new TypeError('lat must be a finite number');
    }
    if (typeof this.lon !== 'number' || !Number.isFinite(this.lon)) {
      throw new TypeError('lon must be a finite number');
    }
    if (this.lat < LAT_MIN || this.lat > LAT_MAX) {
      throw new RangeError(`lat ${this.lat} out of [${LAT_MIN}, ${LAT_MAX}]`);
    }
    if (this.lon < LON_MIN || this.lon > LON_MAX) {
      throw new RangeError(`lon ${this.lon} out of [${LON_MIN}, ${LON_MAX}]`);
    }
  }

  /** @returns {CoordinateLike} */
  toObject() {
    return { lat: this.lat, lon: this.lon };
  }

  /**
   * @param {CoordinateLike} o
   * @returns {Coordinate}
   */
  static fromObject(o) {
    if (!o || typeof o.lat !== 'number' || typeof o.lon !== 'number') {
      throw new TypeError('Coordinate.fromObject expects {lat, lon}');
    }
    return new Coordinate(o.lat, o.lon);
  }

  /**
   * Great-circle distance in meters (haversine).
   * @param {Coordinate} other
   * @returns {number}
   */
  distanceMeters(other) {
    const dLat = deg2rad(other.lat - this.lat);
    const dLon = deg2rad(other.lon - this.lon);
    const a =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(deg2rad(this.lat)) * Math.cos(deg2rad(other.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
  }
}
