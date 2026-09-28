// Public API surface for vector-bus (Epic E2 — Communication & Event Backbone).
export { BusService, BackpressureError, CHANNELS } from './bus.js';
export { ChannelRouter, route } from './channel-router.js';
export {
  validate,
  createEnvelope,
  stampEscalation,
  INTENTS,
  PRIORITIES,
  ESCALATION_LEVELS,
} from './envelope.js';
export { BusServer, createBusServer } from './server.js';
export { NetworkBusClient } from './client.js';
