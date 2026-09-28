// BoundingBox + Point geometry primitives. Mirrors proto BoundingBox / schemas/bounding-box.schema.json.
import { Coordinate, LAT_MIN, LAT_MAX, LON_MIN, LON_MAX } from './coords.js';
import { deg2rad } from './units.js';

/**
 * Axis-aligned geographic bounding box.
 */
export class BoundingBox {
  /**
   * @param {number} minLat
   * @param {number} minLon
   * @param {number} maxLat
   * @param {number} maxLon
   */
  constructor(minLat, minLon, maxLat, maxLon) {
    this.minLat = minLat;
    this.minLon = minLon;
    this.maxLat = maxLat;
    this.maxLon = maxLon;
    this.#assert();
  }

  #assert() {
    for (const [name, v] of Object.entries(this)) {
      if (typeof v !== 'number' || !Number.isFinite(v)) {
        throw new TypeError(`${name} must be a finite number`);
      }
    }
    if (this.minLat < LAT_MIN || this.maxLat > LAT_MAX) {
      throw new RangeError('latitude out of range');
    }
    if (this.minLon < LON_MIN || this.maxLon > LON_MAX) {
      throw new RangeError('longitude out of range');
    }
    if (this.minLat > this.maxLat) throw new RangeError('minLat > maxLat');
    if (this.minLon > this.maxLon) throw new RangeError('minLon > maxLon');
  }

  /**
   * @param {Coordinate} c
   * @returns {boolean}
   */
  contains(c) {
    return (
      c.lat >= this.minLat &&
      c.lat <= this.maxLat &&
      c.lon >= this.minLon &&
      c.lon <= this.maxLon
    );
  }

  /**
   * @param {BoundingBox} other
   * @returns {boolean} true if boxes overlap (inclusive)
   */
  intersects(other) {
    return !(
      other.minLon > this.maxLon ||
      other.maxLon < this.minLon ||
      other.minLat > this.maxLat ||
      other.maxLat < this.minLat
    );
  }

  /** @returns {number} approximate area in square meters */
  areaMetersSquared() {
    const latMid = deg2rad((this.minLat + this.maxLat) / 2);
    const mPerDegLat = 111_320;
    const mPerDegLon = 111_320 * Math.cos(latMid);
    const h = (this.maxLat - this.minLat) * mPerDegLat;
    const w = (this.maxLon - this.minLon) * mPerDegLon;
    return Math.abs(h * w);
  }
}
