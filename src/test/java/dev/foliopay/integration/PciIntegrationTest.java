package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dev.foliopay.TestBase;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.Money;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.PaymentCommand;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.LoggerFactory;

/** After a full flow, looks for the card number in every column of every table and in everything logged. */
class PciIntegrationTest extends TestBase {
  @Autowired PaymentService payments;
  @Autowired DataSource dataSource;

  @Test
  void theCardNumberIsNowhereInTheDatabaseAfterAFullFlow() {
    payments.pay(new PaymentCommand("pci-1", "BK-1", "F-1", token, new Money(10_000, "USD"), LocalDate.of(2026, 11, 17)));
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    List<Map<String, Object>> columns = jdbc.queryForList("select table_name, column_name from information_schema.columns where table_schema = 'PUBLIC'");
    assertThat(columns).as("the schema was created").isNotEmpty();
    for (Map<String, Object> column : columns) {
      String sql = "select \"" + column.get("COLUMN_NAME") + "\" from \"" + column.get("TABLE_NAME") + "\"";
      for (Object value : jdbc.queryForList(sql, Object.class)) {
        String stored = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.ISO_8859_1) : String.valueOf(value);
        assertThat(stored).as("%s.%s", column.get("TABLE_NAME"), column.get("COLUMN_NAME")).doesNotContain(PAN);
      }
    }
  }

  /**
   * Captures what the code hands to the logger, before any masking: the formatted message, the raw arguments and every stack trace. (The
   * earlier version read the console's buffer, which is masked before it is stored, so it could not fail; review finding M6.)
   */
  @Test
  void theCardNumberIsNeverLoggedAcrossTokenisationChargeAndPosting() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> raw = new ListAppender<>();
    raw.start();
    root.addAppender(raw);
    try {
      String fresh = vault.tokenise(new Pan(PAN), "12/29").getToken();
      payments.pay(new PaymentCommand("pci-2", "BK-2", "F-2", fresh, new Money(10_000, "USD"), LocalDate.of(2026, 11, 17)));
      opera.arm(dev.foliopay.gateway.fake.FakeOpera.Fault.DOWN); // the failure paths log too
      payments.pay(new PaymentCommand("pci-3", "BK-3", "F-3", fresh, new Money(10_000, "USD"), LocalDate.of(2026, 11, 17)));
    } finally {
      root.detachAppender(raw);
    }
    assertThat(raw.list).as("the flow logged something, so the check below is not vacuous").hasSizeGreaterThan(2);
    for (ILoggingEvent event : raw.list) {
      StringBuilder everything = new StringBuilder(event.getFormattedMessage());
      if (event.getArgumentArray() != null) {
        for (Object arg : event.getArgumentArray()) everything.append(' ').append(arg);
      }
      for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) everything.append(' ').append(t.getMessage());
      assertThat(everything.toString()).as(event.getLoggerName()).doesNotContain(PAN).doesNotContain("4111 1111 1111");
    }
  }
}
