package dev.foliopay.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each secret is built from parts at run time, so no file holds one that the secret guard would rightly refuse; what matters is the text
 * the redactor sees.
 */
@DisplayName("Log redactor: what never reaches a log line, and what stays readable")
class LogRedactorTest {
  private static final String TOKEN = "tok_" + "0123456789abcdef".repeat(2);
  private static final String JWT = "eyJhbGciOiJSUzI1NiJ9" + "." + "eyJzdWIiOiJjbGllbnQtMSJ9" + "." + "c2lnbmF0dXJlLXZhbHVl";
  private static final String API_KEY = "fpb_" + "live_" + "Abcdefghijklmnopqrstuvwxyz012345";
  private static final String CODE = String.valueOf(400 + 23);

  @Test
  @DisplayName("A card token is masked, because whoever holds it can charge it")
  void aCardTokenIsMasked() {
    assertThat(LogRedactor.redact("charge failed for " + TOKEN + " on retry")).doesNotContain(TOKEN)
        .isEqualTo("charge failed for tok_[redacted] on retry");
  }

  @Test
  @DisplayName("A signed access token is masked wherever it appears")
  void aSignedAccessTokenIsMasked() {
    assertThat(LogRedactor.redact("token " + JWT + " rejected")).doesNotContain("eyJzdWIi").isEqualTo("token [redacted] rejected");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"Authorization: Bearer ", "authorization=Basic ", "Bearer ", "basic "})
  @DisplayName("Bearer and basic credentials are masked")
  void bearerAndBasicCredentialsAreMasked(String prefix) {
    String credential = "c2VjcmV0LWNsaWVudDpwYXNzd29yZA";
    assertThat(LogRedactor.redact(prefix + credential)).doesNotContain(credential).contains("[redacted]");
  }

  @Test
  @DisplayName("An API key of this service is masked")
  void anApiKeyOfThisServiceIsMasked() {
    assertThat(LogRedactor.redact("unknown key " + API_KEY)).isEqualTo("unknown key fpb_live_[redacted]");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "password=hunter22", "\"password\": \"hunter22\"", "client_secret=hunter22", "access_token=hunter22", "refresh-token: hunter22",
      "{\"apiKey\":\"hunter22\"}", "x-app-key: hunter22", "private_key='hunter22'", "secret=hunter22&grant_type=client_credentials",
      "PASSWORD = hunter22"})
  @DisplayName("The value of a field named like a secret is masked in key-value, JSON and form text")
  void theValueOfAFieldNamedLikeASecretIsMasked(String text) {
    assertThat(LogRedactor.redact(text)).doesNotContain("hunter22").contains("[redacted]");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"cvv", "cvc", "cvv2", "cvc2", "cid", "security_code", "card-verification-value", "pin"})
  @DisplayName("A card verification code or PIN is masked in any spelling, though no code path accepts one")
  void aCardVerificationCodeOrPinIsMasked(String field) {
    assertThat(LogRedactor.redact(field + "=" + CODE)).doesNotContain(CODE).isEqualTo(field + "=[redacted]");
    assertThat(LogRedactor.redact("{\"" + field + "\": \"" + CODE + "\"}")).doesNotContain(CODE);
  }

  @Test
  @DisplayName("A card number is still masked to its last four digits")
  void aCardNumberIsStillMasked() {
    assertThat(LogRedactor.redact("card 4111111111111111 failed")).isEqualTo("card ************1111 failed");
  }

  @Test
  @DisplayName("Ids, idempotency keys, amounts, dates and statuses stay readable")
  void idsKeysAmountsDatesAndStatusesStayReadable() {
    String line = "Charge ch_42 refused a repeat of key order-1001 for booking B-7 folio F-1: 12500 EUR on 2026-11-21, status DECLINED";
    assertThat(LogRedactor.redact(line)).isEqualTo(line);
    String prose = "OPERA refused our credentials (401): check the client id, secret, application key, enterprise id and scope";
    assertThat(LogRedactor.redact(prose)).as("a word in a sentence is not a field").isEqualTo(prose);
    String audit = "event=CARD_OPENED outcome=SUCCESS client=pms-1 card=fp:0123456789abcdef";
    assertThat(LogRedactor.redact(audit)).as("an audit line keeps its fields").isEqualTo(audit);
  }

  /** Moved here from PanMaskerTest in phase 21, when this class became the one boundary every log format uses. */
  @Test
  @DisplayName("The one-line form also escapes line breaks, so a value cannot forge a second log entry")
  void safeAlsoFlattensLineBreaksSoAValueCannotForgeALogEntry() {
    assertThat(LogRedactor.safe("folio F1\n2026-01-01 WARN Charge - AGENT: refund\r\nnext"))
        .doesNotContain("\n").doesNotContain("\r").contains("\\n");
    assertThat(LogRedactor.safe("a\r\nWARN fake entry password=hunter22")).doesNotContain("hunter22");
  }

  @Test
  @DisplayName("Nothing to redact and no text at all are both handled")
  void nothingToRedactAndNoTextAtAllAreHandled() {
    assertThat(LogRedactor.redact(null)).isNull();
    assertThat(LogRedactor.safe(null)).isNull();
    assertThat(LogRedactor.redact("")).isEmpty();
  }
}
