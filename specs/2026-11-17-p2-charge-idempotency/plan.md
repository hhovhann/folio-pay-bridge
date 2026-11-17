# Phase 2 Plan - Idempotent virtual-card charging

1. Write `PspClient`, `FakePsp`, `Charge`, `ChargeService`, `Retry`
2. Concurrency test: 8 threads, same key
3. Property: captured total equals the sum of captured charges
