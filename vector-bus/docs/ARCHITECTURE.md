# Architecture Overview — vector-bus

## Bounded context
`vector-bus` is the **Communication & Event Backbone** of Project Vector (Epic E2). It is an
in-process, zero-dependency async pub/sub layer over which agents exchange the standard message
envelope (Blueprint §6). It owns the channel taxonomy, envelope validation, and SLA-driven
escalation routing. As of the Wave 13 deliverable a networked transport now exists: `BusServer`
(HTTP + Server-Sent-Events, port 8090) wraps the in-process `BusService` + `ChannelRouter`/`route()`
behind the same API, and `NetworkBusClient` lets the E1/E3/E4/E5/E6/E7/E8 services talk to it. The
default remains the in-process `LocalBus` (so CI stays isolated) unless `VECTOR_BUS_URL` is set.

## Module map
| Module | File | Responsibility |
|--------|------|----------------|
| Producer / consumer | `src/bus.js` (`BusService`) | pub/sub, at-least-once, idempotency, dead-letter, backpressure |
| Envelope | `src/envelope.js` | validate / createEnvelope / stampEscalation |
| Channel router | `src/channel-router.js` (`ChannelRouter`) | intent->channel matrix, SLA timers, escalation |
| API surface | `src/index.js` | re-exports the above |

Channels (Blueprint §6): `#tasks #events #contracts #escalations #broadcast`.

## Data flow — publish / subscribe
```
producer --publish(channel, envelope)--> BusService
                                        |  validate envelope (throw if invalid)
                                        |  enforce channel + backpressure
                                        v
                                   subscribers(handler, ack)
                                        |  handler throws / no ack within deliveryTimeout
                                        v
                                   retry (exp backoff + jitter) up to maxRetries
                                        |  exhausted
                                        v
                                   onDeadLetter callback
```

## Data flow — SLA-triggered escalation
```
producer --router.publish(envelope)--> ChannelRouter.route() -> channel
                                        |  bus.publish(channel, envelope)
                                        |  start SLA timer (sla_ms)
consumer acks in time ---------------- ack() --> router.ack() clears timer (no escalation)
no ack within sla_ms ---------------- timer fires --> stampEscalation(level)
                                                       |  publish to #escalations
                                                       v  onEscalation(cb)
                                                  COA / CTO / source DMA
```

## Evolution
The Wave 13 `BusServer` (HTTP + SSE, port 8090) realizes the networked transport over these same
module boundaries without API changes to callers: it wraps `BusService` + `ChannelRouter` and
exposes `GET /healthz`, `POST /publish`, `GET /subscribe` (SSE), and `POST /ack`. Consumers reach it
through `NetworkBusClient`, defaulting to the in-process `LocalBus` unless `VECTOR_BUS_URL` is set,
which keeps CI isolated. A further broker (NATS/Kafka-style) could slot in behind the same API.
Envelope validation stays at the boundary regardless of transport.
