# Phase 20 Validation - Who is who

## The simulator shows the parties it plays
- [x] The ledger names the card network and the issuer's approval code, a capture carries them, and no card number is kept: `SimulatorEndpointsTest.theLedgerNamesTheCardNetworkAndTheIssuersApprovalCode` (failed first)
- [x] The network is named from the first digits, and a number that matches none gets none: `PspSimulatorTest.theCardNetworkIsNamedFromTheCardsFirstDigits`
- [x] The rest of the processor's contract is unchanged: `SimulatorEndpointsTest.*` and `FullFlowHttpIntegrationTest.*`

## The page, the video and the docs
- [x] The page renders every party and every step with no script error, in light and dark and at phone width without a page that scrolls sideways (screenshots reviewed)
- [x] `demo/make_who_gif.py` checks each scene against the page's panel and writes `demo/out/who-is-who.gif`
- [x] The full-flow dry run (`DEMO_FAST=1 demo/make_fullflow_demo.py`) checks every party the new chapter names against the page, and the network and approval code on the processor page
- [ ] (manual) The video re-recorded with the new chapter and attached to the release: the owner publishes it

## The whole build
- [x] `./mvnw clean verify` (the coverage gate), `python3 tools/check_specs.py`, `python3 tools/guard_secrets.py --repo`, `python3 tools/guard_commands.py --self-test`
- [x] `CodeConventionsTest.theNumberOfLinesOver150CharactersOnlyGoesDown` at 202
