# vector-bus

> **Owner squad:** d1-platform (Communication & Event Backbone — Epic E2)
> **Purpose:** In-process async pub/sub with at-least-once delivery, idempotent consume, dead-lettering, and SLA-driven escalation over the standard Vector envelope (Blueprint §6).
> **Canonical standards:** vector-governance.

`vector-bus` is the **E2 Communication & Event Backbone** service (`bus-svc`). It is a zero-runtime-dependency
Node ESM library that lets agents talk asynchronously over the standard message envelope.

## Channel taxonomy (Blueprint §6)
| Channel | Purpose |
|---------|---------|
| `#tasks` | work assignment queue |
| `#events` | immutable fact stream |
| `#contracts` | interface / dependency declarations |
| `#escalations` | exceptions routed up |
| `#broadcast` | org-wide notices from COA/CTO |

## Envelope spec (Blueprint §6)
Every message carries:
```json
{
  "id": "uuid",
  "correlation_id": "uuid",
  "from": "agent://sla.auth-01",
  "to": "agent://worker.sec-12",
  "intent": "TASK | ASSIGN | REVIEW | ESCALATE | NOTIFY | CONTRACT",
  "priority": "P0 | P1 | P2 | P3",
  "payload": { },
  "sla_ms": 3600000,
  "timestamp": "iso8601"
}
```

## Install & run
```bash
npm install
npm run validate   # structure / hygiene checks
npm test           # node --test
```

## Usage examples

### Create a BusService and publish/subscribe
```js
import { BusService } from 'vector-bus';

const bus = new BusService();
const received = [];
const unsub = bus.subscribe('#tasks', 'worker-1', (msg, ack) => {
  received.push(msg);
  ack(); // mark processed (at-least-once + idempotency)
});

bus.publish('#tasks', {
  id: 'uuid', correlation_id: 'uuid', from: 'agent://sla', to: 'agent://worker',
  intent: 'TASK', priority: 'P1', payload: { x: 1 }, sla_ms: 5000, timestamp: new Date().toISOString(),
});
```

### Envelope validation at the boundary
```js
import { validate, createEnvelope } from 'vector-bus';

const env = createEnvelope({ intent: 'TASK', from: 'agent://sla', to: 'agent://worker' });
const { valid, errors } = validate(env); // valid === true
// bus.publish rejects invalid envelopes with: Invalid envelope rejected at boundary: ...
```

### Channel routing + SLA timers
```js
import { BusService, ChannelRouter } from 'vector-bus';

const bus = new BusService();
const router = new ChannelRouter(bus);

bus.subscribe('#escalations', 'coa', (esc) => console.log('escalation:', esc.intent, esc.payload.level));
router.onEscalation((esc) => console.log('SLA breach for', esc.correlation_id));

// Subscribe through the router so ack() cancels the SLA timer automatically.
router.subscribe('#tasks', 'worker-1', (msg, ack) => { /* ... */ ack(); });

const env = createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s', sla_ms: 50 });
router.publish(env); // starts an SLA timer; breach -> #escalations if not acked in time
```

## API summary
- `BusService` — `subscribe(channel, consumerId, handler) -> unsubscribe()`, `publish(channel, message)` (throws on unknown channel / invalid envelope; returns a Promise under backpressure), `onDeadLetter(cb)`.
- `ChannelRouter` — `route(envelope)` (pure matrix), `publish(envelope)` (routes + starts SLA timer), `subscribe(channel, consumerId, handler)` (ack cancels SLA timer), `ack(messageId)`, `onEscalation(cb)`.
- `validate(envelope) -> { valid, errors }`, `createEnvelope(partial)`, `stampEscalation(envelope, { level, reason })`.

## Networked bus service

The in-process `BusService` engine is also exposed over the network as a small HTTP +
Server-Sent-Events (SSE) service (`BusServer`) with a matching reference client
(`NetworkBusClient`). This lets a distributed fleet talk over the **same** at-least-once,
idempotent, dead-lettering engine — the delivery Promise the engine awaits is resolved by
an `/ack` arriving over the wire. Zero runtime dependencies (node stdlib only).

### Run the service
```bash
npm run bus-serve            # defaults to PORT/--port 8090, host 0.0.0.0
PORT=9000 npm run bus-serve  # or: node src/server-main.js --port 9000
```
Docker (context is the repo root, mirroring the other Vector services):
```bash
docker build -f docker/Dockerfile -t vector-bus .
docker run -p 8090:8090 vector-bus   # container listens on 8090
```

### Wire protocol (fixed contract)
| Method + path | Body | Response |
|---------------|------|----------|
| `GET /healthz` | — | `200 text/plain` `ok` |
| `POST /publish` | `{ channel?, message }` | `202 { accepted, id, channel }` (invalid envelope / unknown channel -> `400 { error }`) |
| `GET /subscribe?channel=#tasks&consumerId=worker-1` | — | `text/event-stream` (SSE) |
| `POST /ack` | `{ deliveryId, consumerId?, channel? }` | `200 { acked }` (unknown/late id -> `{ acked: false }`, idempotent) |

`POST /publish` resolves the channel via `ChannelRouter.route(message)` when `channel` is
omitted. The SSE stream emits `event: message` frames with
`data: { deliveryId, channel, message }`, and `event: deadletter` frames with
`data: { channel, message, error, attempts }`. Delivery is only marked complete once a
matching `POST /ack` (carrying the `deliveryId`) is received; otherwise the engine retries
and eventually dead-letters, exactly as in-process.

### NetworkBusClient usage
```js
import { createBusServer, NetworkBusClient, createEnvelope } from 'vector-bus';

const server = createBusServer({ deliveryTimeout: 200 });
const { port } = await server.listen(0, '127.0.0.1');

const client = new NetworkBusClient({ host: '127.0.0.1', port });
// or: new NetworkBusClient('http://127.0.0.1:8090')

client.onDeadLetter((record) => console.log('dead-letter:', record.channel, record.attempts));

const unsub = client.subscribe('#tasks', 'worker-1', (message, ack) => {
  console.log('got task', message.id);
  ack(); // POSTs /ack for this delivery (at-least-once + idempotency)
});
await unsub.ready; // resolves once the SSE stream is connected

await client.publish('#tasks', createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b' }));

// Always clean up (leaves zero open handles):
unsub();
await client.close();
await server.close();
```

`NetworkBusClient` mirrors the `BusService` surface: `publish(channel, message)`,
`subscribe(channel, consumerId, handler) -> unsubscribe()`, `onDeadLetter(cb)`, and
`close()`. The returned `unsubscribe` also exposes a `.ready` Promise for race-free tests.

See `docs/ARCHITECTURE.md`, `docs/RUNBOOK.md`, and `adr/` for design decisions.
