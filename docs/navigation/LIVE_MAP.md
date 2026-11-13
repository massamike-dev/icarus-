# Live phone map and route line

A separate non-exported Android activity loads only bundled map scripts and exposes a three-method map bridge, not the assistant bridge. The offline assistant's network policy is unchanged. Launch via Vehicle → Open live map & route line. Start explains online disclosure, then requests existing foreground location permissions. Choose a destination by tapping a road while parked and press Calculate route.

Features: real OSM street tiles, foreground GPS with accuracy circle and measured speed, full OSRM road geometry rendered as a cyan polyline, route overview/recenter, approximate remaining geometry distance, static no-traffic duration estimate, off-route indication, cancel/clear, stale-fix dimming and stop-on-background. The line is geographic road geometry, not lane alignment, racing/braking advice or an AR overlay. This is not a full turn-by-turn navigation engine. No invented road data is used when providers fail.

Providers in this limited test integration:
- https://tile.openstreetmap.org/{z}/{x}/{y}.png — HTTP caching retained, viewport tiles only, no offline prefetch, visible OSM attribution, app-identifying WebView user agent.
- https://routing.openstreetmap.de/routed-car/route/v1/driving/ — HTTPS, explicit user requests only, a five-second client throttle, no polling or automatic reroute. Only current origin and selected destination are transmitted, with no ICARUS account token. Requests may be logged by the provider.
- Vendored Leaflet 1.9.4 from its npm package, with BSD license retained. No remote JavaScript.

Review policies before broad rollout: https://operations.osmfoundation.org/policies/tiles/ and https://routing.openstreetmap.de/about.html . Community services provide no availability guarantee; the routing limit is one request/second with no heavy use. This is intended for limited testing; a managed/self-hosted routing service with deployment-wide throttling and configurable endpoints is a release gate before scaling beyond a few testers. No public Nominatim geocoding is used. Address search remains unimplemented; destination selection is by map tap.

Verification: deterministic tests cover coordinate/route validation, stale/poor/future GPS rejection, nearest-segment/off-route calculations, start consent, cancellation, late response suppression, route rendering and foreground stop. Browser QA uses explicitly simulated tiles, routing and GPS; it is not hardware validation. A one-request live-provider smoke test used public Seattle coordinates (not a user's location) and received a GeoJSON route with 84 points and permissive CORS.

Device gates: permission allow/deny/approximate-only, permission revocation, device location off, lock/background/return, network loss and slow provider, route deviation, service denial, GPS accuracy/freshness, portrait/landscape/system-bar layout. No background location permission or additional Android permissions are added. XREAL remains unchanged and stationary-only. Signed release requires native tests/lint and physical-device checks; do not mark this production-ready based on mocked browser results.
