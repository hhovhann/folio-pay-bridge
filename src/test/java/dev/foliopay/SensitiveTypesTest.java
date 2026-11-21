package dev.foliopay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Types that hold a secret cannot leak it through a log line (D-41). A record prints every component from its generated
 * {@code toString()}, so one that holds a card token, a card number or a credential as text must print a redacted form; and no type or
 * table may hold sensitive authentication data at all (PCI DSS v4.0.1 3.3.1: a card verification code is never stored, not even encrypted).
 */
@DisplayName("Sensitive types: nothing secret is printed by a type, and no card verification code is held anywhere")
class SensitiveTypesTest {
  /** Words that make a name sensitive, alone or as a pair, in camelCase or snake_case. */
  private static final Set<String> SENSITIVE_WORDS = Set.of(
      "pan", "token", "secret", "password", "passwd", "credential", "credentials", "sealed", "wrapped", "plaintext", "cvv", "cvc");
  private static final Set<String> SENSITIVE_PAIRS = Set.of("api key", "app key", "private key", "data key", "key hash");
  /** Sensitive authentication data (PCI DSS 3.3.1): the card verification code in any spelling, the PIN, the magnetic stripe or chip data. */
  private static final Pattern AUTHENTICATION_DATA = Pattern.compile(
      "(?i)^(cvv2?|cvc2?|cav2|cid|cvn|security_?code|card_?verification_?(code|value)|pin|pin_?block|track_?[12]?(_?data)?)$");

  private static List<Class<?>> mainTypes() {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
      @Override
      protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) { return true; }
    };
    scanner.addIncludeFilter((reader, factory) -> true);
    List<Class<?>> types = new ArrayList<>();
    for (String base : List.of("dev.foliopay", "dev.foliopaysim")) {
      for (BeanDefinition definition : scanner.findCandidateComponents(base)) {
        try {
          Class<?> type = Class.forName(definition.getBeanClassName());
          if (!type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) {
            types.add(type);
          }
        } catch (ClassNotFoundException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return types;
  }

  static boolean isSensitive(String name) {
    List<String> words = Arrays.asList(name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase().split("\\s+"));
    for (int i = 0; i < words.size(); i++) {
      if (SENSITIVE_WORDS.contains(words.get(i)) || i > 0 && SENSITIVE_PAIRS.contains(words.get(i - 1) + " " + words.get(i))) {
        return true;
      }
    }
    return false;
  }

  /** A record component the generated toString would print as readable text: a string, or a type that prints its value. */
  private static boolean printsAsText(RecordComponent component) {
    Class<?> type = component.getType();
    return type == String.class || type == CharSequence.class || type == Object.class;
  }

  /**
   * Fails if a record holding a card token, a card number or a credential as text prints it: logging the object whole would print the
   * secret. Each such record is built from sample values and its toString is run; one that cannot be built fails until it can.
   */
  @Test
  @DisplayName("Every record that holds a secret as text prints it redacted")
  void everyRecordThatHoldsASecretAsTextPrintsItRedacted() throws Exception {
    List<String> leaking = new ArrayList<>();
    int checked = 0;
    for (Class<?> type : mainTypes()) {
      if (!type.isRecord()) {
        continue;
      }
      List<RecordComponent> secrets = Arrays.stream(type.getRecordComponents()).filter(c -> isSensitive(c.getName()) && printsAsText(c)).toList();
      if (secrets.isEmpty()) {
        continue;
      }
      checked++;
      // Run it: reflection cannot tell a written toString from the generated one (both are declared), so only its output can.
      Object instance = build(type);
      if (instance == null) {
        leaking.add(type.getName() + ": cannot be built here to check its toString; add a sample for its component types");
        continue;
      }
      String printed = instance.toString();
      for (RecordComponent secret : secrets) {
        secret.getAccessor().setAccessible(true);
        Object value = secret.getAccessor().invoke(instance);
        if (value != null && printed.contains(value.toString())) {
          leaking.add(type.getName() + ": toString prints " + secret.getName());
        }
      }
    }
    assertThat(checked).as("records holding a secret were found, so the check is not vacuous").isGreaterThanOrEqualTo(5);
    assertThat(leaking).isEmpty();
  }

  /** Fails if a field, a record component or a column is named for a card verification code, a PIN or track data. */
  @Test
  @DisplayName("No type and no table holds a card verification code, a PIN or track data")
  void noTypeAndNoTableHoldsACardVerificationCodeAPinOrTrackData() throws IOException {
    List<String> found = new ArrayList<>();
    for (Class<?> type : mainTypes()) {
      for (Field field : type.getDeclaredFields()) {
        if (AUTHENTICATION_DATA.matcher(field.getName()).matches()) {
          found.add(type.getName() + "." + field.getName());
        }
      }
    }
    Pattern column = Pattern.compile(
        "(?im)^\\s*(?:alter table \\w+ add column\\s+)?(\\w+)\\s+(?:varchar|char|text|bytea|bigint|int|integer|numeric|timestamp|date)\\b");
    try (Stream<Path> migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
      for (Path migration : migrations.sorted().toList()) {
        var columns = column.matcher(Files.readString(migration));
        while (columns.find()) {
          if (AUTHENTICATION_DATA.matcher(columns.group(1)).matches()) {
            found.add(migration.getFileName() + ": column " + columns.group(1));
          }
        }
      }
    }
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("The name check knows a secret from an ordinary id or key")
  void theNameCheckKnowsASecretFromAnOrdinaryIdOrKey() {
    assertThat(List.of("cardToken", "token", "operaClientSecret", "pspApiKey", "sealedPanHex", "wrappedKeyHex", "plaintext", "keyHash", "pan"))
        .allMatch(SensitiveTypesTest::isSensitive);
    assertThat(List.of("idempotencyKey", "keyId", "bookingRef", "company", "expand", "span", "panel", "processorKey"))
        .noneMatch(SensitiveTypesTest::isSensitive);
  }

  /** The record built from sample values, a distinct text for each string component; null when a component cannot be made here. */
  private static Object build(Class<?> type) throws ReflectiveOperationException {
    RecordComponent[] components = type.getRecordComponents();
    Object[] values = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      values[i] = sample(components[i].getType(), i);
      if (values[i] == null && components[i].getType().isPrimitive()) {
        return null;
      }
    }
    Constructor<?> canonical = type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
    canonical.setAccessible(true);
    try {
      return canonical.newInstance(values);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static Object sample(Class<?> type, int index) {
    Map<Class<?>, Object> fixed = Map.of(
        long.class, 1L, int.class, 1, boolean.class, false, LocalDate.class, LocalDate.of(2026, 1, 1), Instant.class, Instant.EPOCH,
        Duration.class, Duration.ofSeconds(1), Money.class, new Money(100, "EUR"), Pan.class, new Pan("4111111111111111"));
    if (type == String.class || type == CharSequence.class || type == Object.class) {
      return "sample-value-" + index + "-" + "x".repeat(8);
    }
    if (type.isEnum()) {
      return type.getEnumConstants()[0];
    }
    return fixed.get(type);
  }
}
