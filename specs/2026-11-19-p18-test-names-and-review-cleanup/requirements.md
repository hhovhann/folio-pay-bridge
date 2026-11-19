# Phase 18 Requirements - Readable test names, and the review's smaller findings

## Goal
The owner asked for three things. First, every test should say, in a name a person reads, what it proves. Second, that should be an engineering rule the build enforces. Third, the rest of the OHIP review should be cleaned up: the six smaller findings phase 17 left.

## Scope
- **Readable test names.**
  - Every JUnit test (444) and every test class (68, now 69) has a `@DisplayName` that says in plain words what it proves.
  - Each name was drafted from the method name, which was already a sentence in camelCase, then reviewed by hand for punctuation, proper nouns and meaning.
  - The method name stays the stable id that `validation.md`, `docs/edge-cases.md` and `tools/check_specs.py` cite.
- **The rule, enforced** (`CodeConventionsTest`):
  - every test and test class has a display name that is a sentence, unique within its class, for any spelling of the annotations;
  - the number of lines over 150 characters may only go down;
  - every version the pom declares is a property.
- **`tools/check_specs.py`** reads the class from the report's test-suite name. A class display name replaces the class in the surefire report's `classname`.
- **The review's smaller findings:**
  - The token refusal's stored reason names every setting the token needs.
  - Basic credentials are sent in UTF-8. Oracle's description of the header (RFC 7617, plain `ClientID:ClientSecret`) rules out the URL encoding the review also suggested.
  - The schema validator's version is a property.
  - The 73 long lines phase 16 added or touched are wrapped.
  - Agent log 52 names its test.
  - The simulator gives the lowest free room and keeps a reservation it already holds.

## Out of scope
- A formatter (Spotless with google-java-format) for the remaining 221 long lines. It is a build plugin and needs the owner's approval (CLAUDE.md rule 7).
- The dashboard's Vitest tests. They are named by their `it(...)` sentences already.
- Finding 3 of the OHIP review (`includeFolioHistory`), a sandbox question.
