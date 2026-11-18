# The operator dashboard (frontend)

React 18 + TypeScript (strict), built with Vite, tested with Vitest and Testing Library, built and tested by Maven with a pinned Node (D-38). It is a client of the public API like any other: it can do only what its token's scopes allow. The repository-wide rules in `../CLAUDE.md` and `../docs/engineering-standards.md` apply here too.

## Rules
- **One way to the bridge:** `src/api.ts`. Paths are versioned (`/api/v1/...`); errors are problem details, and the dashboard acts on the `code`, never on the `detail` text. Retryable codes in `ApiError.retryable` match `ErrorCode.retryable()` on the server (D-29).
- **Tokens live in memory only** (`src/auth.ts`, OpenID Connect with PKCE). Never in `localStorage`, `sessionStorage` or a cookie; `localStorage` holds UI preferences (`fpd.*`) and nothing else.
- **No HTML from data:** no `dangerouslySetInnerHTML`, no inline scripts or styles that need `'unsafe-inline'` for scripts (the content security policy allows scripts from this origin only). React's escaping is the defence; do not route around it.
- **Money arrives as integer minor units with a currency.** Format it with `src/format.ts` (the currency's own decimals: JPY 0, KWD 3); never add, subtract or round amounts in floating point, and never sum across currencies.
- **No UI kit and no state library** (D-38): one stylesheet with light and dark tokens; state in the page or a hook. A new dependency needs the owner's approval (`../CLAUDE.md` rule 7), pinned to an exact version with the lock file updated.
- Hash routing (`#/...`), so the server needs no fallback route.

## Proving a change
- `npm run typecheck` and `npm test` (Vitest; `src/testing.ts` fakes the API per `METHOD path`). A test asserts what the operator sees and what was sent, not component internals.
- `../mvnw test` runs the same tests in the Maven build. Then the real browser: `uv run --project demo python demo/check_dashboard.py` from the repository root drives every screen against the running app and fails on any browser error, including a content security policy violation.
- `npm run dev` serves it with hot reload against a bridge on `:8081`.
