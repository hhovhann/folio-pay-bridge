# Phase 17 Validation - Fixes from the OHIP code review

## Finding 1: a long folio stopped its guest being paid
- [x] `HttpAdapterRepliesTest.aLongFolioIsReadPageByPageSoItsGuestCanBePaid`: failed on the old code ("OPERA returned only part of the folio")
- [x] `OhipContractTest.aPaymentOnALongFolioIsFoundOnALaterPage`: every page, asked and answered, on Oracle's contract
- [x] `HttpAdapterRepliesTest.aFolioPageThatCannotProveOurPaymentAbsentIsUnavailableNeverNotFound`, `HttpAdapterRepliesTest.aWindowWithoutItsNumberCannotBeAskedForItsNextPage`, `HttpAdapterRepliesTest.aWindowThatNeverEndsIsReadForAtMostFiftyPages`: the fail-safe kept for each of these:
  - an empty page after a promise of more;
  - no folio;
  - the wrong window;
  - fewer postings read than the total;
  - no window number;
  - no end (bounded at 50 pages).
- [x] `HttpAdapterRepliesTest.aFolioReturnedOnlyInPartIsUnavailableNotAbsent` still passes

## Finding 2: a found payment with an unreadable amount or date
- [x] `HttpAdapterRepliesTest.aFoundPaymentIsFoundByItsTransactionNumberEvenWhenItsAmountOrDateCannotBeRead`: failed on the old code ("OPERA sent a payment we could not read"). It checks four cases: no currency, an unknown currency, a fraction of a cent and an unreadable date.
- [x] `HttpAdapterRepliesTest.aPaymentListedAsACreditIsFoundAndAChargeWithOurReferenceIsNot` and `OhipContractTest.aPaymentIsPostedAndFoundBackThroughOraclesOperationsInEachCurrency`: amounts still read exactly when they can be

## Finding 4: the hotel's OPERA settings defaulted to the simulator's
- [x] `FailClosedStartupTest.withoutEachOfTheHotelsOperaSettingsItDoesNotStart`: failed on the old code, because the app started without each setting. It now stops on each one, naming the property.
- [x] `FailClosedStartupTest.withTheDemoSettingsItStarts`

## Commands
- [x] Nine fixed bugs put back on purpose, nine caught, each named test confirmed in its surefire report (`var/mutate_p17.py`, not committed):
  - no paging;
  - an empty page read as the end;
  - the total not checked;
  - no bound on pages;
  - a window without a number;
  - any window taken as the next page;
  - an unreadable amount;
  - an unreadable date;
  - no validation of the settings.
- [x] `./mvnw clean verify`: 452 backend tests and 15 dashboard tests; 99.37% of lines and 91.61% of branches; the coverage gate met
- [x] `python3 tools/guard_secrets.py --repo`: 0 findings; `python3 tools/guard_commands.py --self-test`: 0 wrong
- [x] `DEMO_FAST=1 uv run --project demo python demo/make_fullflow_demo.py`: the Compose profile supplies the hotel's settings, and every narrated claim is asserted on the Docker stack (dry run OK, the image built from this tree)
- [x] `uv run --project demo python demo/check_dashboard.py`: the demo profile starts with the hotel's settings (dashboard OK, no browser errors)
- [ ] A real OHIP sandbox (manual): how `getFolio` pages, and whether billed postings are listed without `includeFolioHistory` (`specs/roadmap.md`, Next 4)
