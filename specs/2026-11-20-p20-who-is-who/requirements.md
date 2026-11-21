# Phase 20 Requirements - Who is who: every party in a card payment, for someone new

## Goal
The owner asked whether the chain behind a payment, the issuer and the acquirer, is part of this workflow. If it is, the architecture and the demo video should show it, interactively. Someone who knows nothing about IT should be able to understand every party quickly, and see how the real systems work. Onboarding as a whole should get faster.

The answer: yes. Every card payment passes through the processor, the acquirer, the card network and the issuer, but the bridge talks only to the processor. In the demo one simulator played all four, and nothing on screen said so.

## Scope
- **An interactive page, `/who-is-who.html`, served by the bridge.**
  - Eleven parties: seven in the chain (guest, hotel and OPERA, the bridge, processor, acquirer, card network, issuer), four beside it (the hotel's systems, finance, the key service, the hotel's bank account).
  - Each party is marked built here, simulator, played by the processor simulator, you, or not in the demo.
  - Nine steps (book, ask, card, authorise, capture, post, tell, settle, reconcile), with what passes between the parties moving on the board: messages in seconds, money in days.
  - A click on a party shows who it is in real life, what it sees of the card, and what plays it in the demo.
  - Its state is one attribute, `body[data-step]`, which the buttons and the narrated video both set.
- **The processor simulator shows the parties it plays.**
  - Its ledger names the card network from the card's first digits, and the issuer's approval code; a capture or a void carries both from its authorisation.
  - Its page has a "Behind the processor" strip that replays the route when a new authorisation arrives.
  - The card number is still not kept.
- **The video gets a "Who is who" chapter**, after the problem and before the architecture.
  - Each party is lit as the voice names it, and the dry run checks each one against the page's own panel.
  - The processor scene also points at the network and the approval code.
- **Onboarding.**
  - `docs/who-is-who.md`: the parties, one payment as a diagram GitHub draws, the two speeds, what can go wrong, and what is real here.
  - The README opens with a path for each reader: new to payments, an engineer joining, a reviewer.
  - `docs/onboarding.md` gets an hour 0 with no code, and a fast first hour for engineers.
  - The architecture page, `docs/architecture.md` and `docs/product-map.md` show the chain behind the processor.
  - `demo/make_who_gif.py` makes the README's GIF from the page itself, and checks every scene.
- **Cleanup.** Compiled Python files committed by mistake are removed and ignored. The long lines of the processor simulator's controller are wrapped (202 lines over 150 characters, down from 207).

## Out of scope
- Real acquirer, network or issuer integrations: they come with an acquirer's test environment (roadmap, Next 4).
- 3-D Secure, refunds and chargebacks: named on the page as what can go wrong, not built.
- Publishing the page outside the running app (GitHub Pages): the owner decides.
