package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.TestBase;
import dev.foliopay.domain.model.Money;
import dev.foliopay.logging.RecentLogs;
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

/** After a full flow, looks for the card number in every column of every table and in everything logged. */
class PciIntegrationTest extends TestBase {
  @Autowired PaymentService payments;
  @Autowired DataSource dataSource;
  @Autowired RecentLogs recentLogs;

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

  @Test
  void theCardNumberIsNeverLoggedAcrossTokenisationChargeAndPosting() {
    payments.pay(new PaymentCommand("pci-2", "BK-2", "F-2", token, new Money(10_000, "USD"), LocalDate.of(2026, 11, 17)));
    List<String> lines = recentLogs.tail(200);
    assertThat(lines).isNotEmpty();
    assertThat(String.join("\n", lines)).doesNotContain(PAN).doesNotContain("4111 1111");
  }
}
