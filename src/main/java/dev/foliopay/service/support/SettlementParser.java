package dev.foliopay.service.support;

import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.SettlementLine;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads an acquirer statement exported as CSV: header psp_ref,amount_minor,currency. A bad row fails loudly instead of being skipped, a
 * reference must be plain (so a spreadsheet cannot run it as a formula), and a negative amount is not accepted as a settlement.
 */
public final class SettlementParser {
  private static final String HEADER = "psp_ref,amount_minor,currency";
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  private SettlementParser() {}

  public static List<SettlementLine> parse(String csv) {
    String[] rows = csv.strip().split("\\R");
    if (!rows[0].trim().equalsIgnoreCase(HEADER)) {
      throw new InvalidRequestException("Header must be " + HEADER);
    }
    List<SettlementLine> lines = new ArrayList<>();
    for (int i = 1; i < rows.length; i++) {
      if (!rows[i].isBlank()) {
        lines.add(parseRow(rows[i], i + 1));
      }
    }
    return lines;
  }

  private static SettlementLine parseRow(String row, int number) {
    String[] cells = row.split(",", -1);
    if (cells.length != 3 || !REFERENCE.matcher(cells[0].trim()).matches()) {
      throw new InvalidRequestException("Row " + number + " is malformed");
    }
    try {
      long amount = Long.parseLong(cells[1].trim());
      Money checked = new Money(amount, cells[2].trim()); // range and currency checks
      return new SettlementLine(cells[0].trim(), checked.minor(), checked.currency());
    } catch (NumberFormatException e) {
      throw new InvalidRequestException("Row " + number + " has a bad amount");
    } catch (InvalidRequestException e) {
      throw new InvalidRequestException("Row " + number + ": " + e.getMessage());
    }
  }
}
