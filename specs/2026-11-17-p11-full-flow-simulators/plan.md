# Phase 11 Plan

1. Write `FullFlowHttpIntegrationTest` first: both simulators on real ports, the bridge pointed at them.
2. Simulators in `dev.foliopaysim` (a package the bridge never scans), selected by profile `opera` or `psp`.
3. `HttpOperaClient` and `HttpPspClient` in `gateway.http`, only with `foliopay.gateway=http`.
4. Demo service works without the in-memory fakes; shadow mode stays fake-only.
5. Front-desk and processor pages, a link from the console, `compose.yaml`, the `compose` profile.
6. Verify with `docker compose up`: booking event, guest pays, folio and processor ledger, no card number anywhere.
