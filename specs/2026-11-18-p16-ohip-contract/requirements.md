# Phase 16 Requirements - OPERA on Oracle's published contract

## Goal
The owner asked for an environment as close to a real OPERA front desk as can be had without being hired. OPERA cannot be installed or run locally (OPERA Cloud is Oracle's hosted service; OPERA 5 is licensed to hotels; real access is Oracle's partner sandbox or a hotel's test environment). The closest thing available is Oracle's own published specification of the OHIP APIs. So: the bridge talks to OPERA the way Oracle documents, the simulator answers the way Oracle documents, a test proves both on every exchange, and the simulator becomes a front desk a person can work.

## Scope
- The adapter (`HttpOperaClient`) on Oracle's operations and shapes (D-42):
  - `getToken`: client credentials, the client id and secret as HTTP Basic, `x-app-key` and `enterpriseId` headers, a scope.
  - `getBusinessDate` (`/bof/v1`): the business date, from a list of hotels.
  - `postBillingPayment` (`/csh/v1`): a `criteria` body with no business date; OPERA posts on its own.
  - `getFolio` (`/csh/v1`): read back to find our posting; the port's `find` now names the reservation.
  - Errors: Oracle's problem details, read from `o:errorCode`.
- Settings: the enterprise id and the scope are required in HTTP mode. The cashier is a number, and the hotel's payment method code is configuration.
- The simulator on the same operations, and a front desk:
  - reservations with folio windows;
  - room charges posted at night audit, payments, the balance;
  - check-in, and check-out only when the folio is settled;
  - unknown cashiers and payment methods refused.
- `OhipContractTest`: every exchange between the adapter and the simulator, success and failure, checked against Oracle's Swagger files, which are trimmed to these operations at a pinned commit by `tools/ohip_specs.py`. Objects are read strictly.
- Correct what the old simulator taught the documents:
  - D-33's account of OPERA rejecting a stale date: real OPERA takes no date with a payment.
  - Shadow mode compares the bridge's recorded date, not OPERA's.
- One test dependency, the JSON schema validator, approved by the owner (tech-stack).

## Out of scope
- A real OHIP sandbox: it needs Oracle partner access through the employer. Its open questions are listed in `specs/roadmap.md`.
- Deposits before arrival, storing OPERA's posting date, OPERA 5 adapters (OXI, OWS), Oracle's Postman collections against the simulator (it implements four operations, not hundreds).
