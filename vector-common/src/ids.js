// EntityId — typed, content-addressable identifier. Format: "<type>:<uuid>".
import { randomUUID } from 'crypto';

/**
 * @typedef {Object} EntityIdLike
 * @property {string} type
 * @property {string} id
 */

export class EntityId {
  /**
   * @param {string} type domain type, e.g. "tile" | "repo" | "svc"
   * @param {string} id unique id (uuid by default)
   */
  constructor(type, id = randomUUID()) {
    if (!/^[a-z][a-z0-9_-]*$/.test(type)) {
      throw new TypeError(`invalid EntityId type: ${type}`);
    }
    if (!/^[A-Za-z0-9._-]+$/.test(id)) {
      throw new TypeError(`invalid EntityId id: ${id}`);
    }
    this.type = type;
    this.id = id;
  }

  /** @returns {string} "<type>:<id>" */
  toString() {
    return `${this.type}:${this.id}`;
  }

  /** @returns {EntityIdLike} */
  toObject() {
    return { type: this.type, id: this.id };
  }

  /**
   * @param {string} s
   * @returns {EntityId}
   */
  static fromString(s) {
    const i = s.indexOf(':');
    if (i <= 0) throw new TypeError(`invalid EntityId string: ${s}`);
    return new EntityId(s.slice(0, i), s.slice(i + 1));
  }

  /**
   * Convenience factory.
   * @param {string} type
   * @param {string} [id]
   * @returns {EntityId}
   */
  static create(type, id) {
    return new EntityId(type, id);
  }
}
