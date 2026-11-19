package dev.foliopay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The layer rules of D-14 and CLAUDE.md, checked on every build instead of only in review: which package may depend on which. It reads
 * the import lines of the main sources, so it needs no library; a class used without an import (same package) cannot cross a layer
 * anyway. Each rule names the decision it protects, and each failure lists the offending file and import.
 */
@DisplayName("Architecture: the layer rules of D-14, checked on every build")
class ArchitectureTest {
  private static final Path MAIN = Path.of("src/main/java");
  private static final Pattern IMPORT = Pattern.compile("^import (static )?([\\w.]+);", Pattern.MULTILINE);

  record Source(String path, String pkg, List<String> imports, String text) {}

  private static List<Source> sources() {
    try (Stream<Path> files = Files.walk(MAIN)) {
      List<Source> all = new ArrayList<>();
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String text = Files.readString(file);
        List<String> imports = new ArrayList<>();
        Matcher m = IMPORT.matcher(text);
        while (m.find()) {
          imports.add(m.group(2));
        }
        String pkg = MAIN.relativize(file.getParent()).toString().replace('/', '.').replace('\\', '.');
        all.add(new Source(MAIN.relativize(file).toString(), pkg, imports, text));
      }
      return all;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Every import, in a package starting with {@code from}, of a package or class named in {@code forbidden}, as "file -> import". */
  private static List<String> violations(String from, List<String> forbidden, List<String> allowedFiles) {
    List<String> found = new ArrayList<>();
    for (Source source : sources()) {
      if (!(source.pkg().equals(from) || source.pkg().startsWith(from + ".")) || allowedFiles.contains(source.path())) {
        continue;
      }
      for (String imported : source.imports()) {
        for (String bad : forbidden) {
          if (imported.equals(bad) || imported.startsWith(bad + ".")) {
            found.add(source.path() + " -> " + imported);
          }
        }
      }
    }
    return found;
  }

  @Test
  @DisplayName("Controllers never touch a repository, an implementation, an adapter or the vault's crypto")
  void controllersNeverTouchARepositoryAnImplementationAnAdapterOrTheVaultsCrypto() {
    // The error handler may name the ports' exceptions (a 503 for an outage); it may not reach a port, an adapter or a fake.
    assertThat(violations("dev.foliopay.api", List.of("dev.foliopay.repository", "dev.foliopay.service.impl", "dev.foliopay.gateway.http",
        "dev.foliopay.gateway.fake", "dev.foliopay.gateway.PspClient", "dev.foliopay.gateway.OperaClient", "dev.foliopay.gateway.WebhookSender",
        "dev.foliopay.crypto"), List.of())).as("D-14: api -> service interfaces only").isEmpty();
  }

  @Test
  @DisplayName("Services never touch HTTP or the API layer")
  void servicesNeverTouchHttpOrTheApiLayer() {
    assertThat(violations("dev.foliopay.service", List.of("dev.foliopay.api", "org.springframework.web", "jakarta.servlet", "dev.foliopay.gateway.http"), List.of()))
        .as("D-14: a service never touches HTTP").isEmpty();
  }

  /** The fault switches of the demo are the one service allowed to reach the in-memory fakes; nothing else may depend on them. */
  @Test
  @DisplayName("Only the demo service sees the fakes")
  void onlyTheDemoServiceSeesTheFakes() {
    assertThat(violations("dev.foliopay", List.of("dev.foliopay.gateway.fake"), List.of(
        "dev/foliopay/service/impl/DemoServiceImpl.java", "dev/foliopay/gateway/fake/FakeOpera.java", "dev/foliopay/gateway/fake/FakePsp.java")))
        .as("D-14: fakes are test and demo doubles").isEmpty();
  }

  @Test
  @DisplayName("The domain knows no Spring and no other layer")
  void theDomainKnowsNoSpringAndNoOtherLayer() {
    assertThat(violations("dev.foliopay.domain", List.of("org.springframework", "dev.foliopay.api", "dev.foliopay.service", "dev.foliopay.repository",
        "dev.foliopay.gateway", "dev.foliopay.config", "dev.foliopay.crypto", "dev.foliopay.logging"), List.of()))
        .as("D-14: the domain knows only JPA annotations").isEmpty();
  }

  @Test
  @DisplayName("Ports and adapters point inward: adapters never depend on the core that uses them")
  void portsAndAdaptersPointInward() {
    assertThat(violations("dev.foliopay.gateway", List.of("dev.foliopay.api", "dev.foliopay.service", "dev.foliopay.repository"), List.of()))
        .as("adapters depend on the domain and their port, never on the core that uses them").isEmpty();
    assertThat(violations("dev.foliopay.repository", List.of("dev.foliopay.api", "dev.foliopay.service", "dev.foliopay.gateway", "dev.foliopay.config"), List.of()))
        .isEmpty();
  }

  /** The OPERA and processor simulators share the jar but are separate programs (D-26): neither side may reach into the other. */
  @Test
  @DisplayName("The bridge and the simulators share no code")
  void theBridgeAndTheSimulatorsShareNoCode() {
    assertThat(violations("dev.foliopay", List.of("dev.foliopaysim"), List.of())).isEmpty();
    assertThat(violations("dev.foliopaysim", List.of("dev.foliopay"), List.of())).isEmpty();
  }

  /** CLAUDE.md: "interface first, impl package-private". A public implementation can be wired around its interface. */
  @Test
  @DisplayName("Implementations and controllers are package-private")
  void implementationsAndControllersArePackagePrivate() {
    List<String> publicOnes = sources().stream()
        .filter(s -> s.pkg().equals("dev.foliopay.service.impl") || s.pkg().equals("dev.foliopay.api.controller"))
        .filter(s -> Pattern.compile("^public (final )?class", Pattern.MULTILINE).matcher(s.text()).find())
        .map(Source::path).toList();
    assertThat(publicOnes).isEmpty();
  }

  /** Hard rule 2: money is long minor units. A double or BigDecimal anywhere in the main code is a review question, so it fails here first. */
  @Test
  @DisplayName("Money is never floating point or BigDecimal")
  void moneyIsNeverFloatingPointOrBigDecimal() {
    List<String> found = sources().stream()
        .filter(s -> Pattern.compile("\\b(double|float|Double|Float|BigDecimal)\\b").matcher(codeOnly(s.text())).find())
        .map(Source::path).toList();
    assertThat(found).as("files declaring floating-point or BigDecimal values").containsOnly(ALLOWED_FLOATING_POINT.toArray(String[]::new));
  }

  /** Not money: the retry backoff multiplier and jitter. Each was checked by reading the file. */
  private static final List<String> ALLOWED_FLOATING_POINT = List.of(
      "dev/foliopay/config/RetryProperties.java", "dev/foliopay/config/ResilienceConfig.java");

  /** The source without comments and string literals, so a word in a description ("a double-click") is not mistaken for a type. */
  private static String codeOnly(String text) {
    return text.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
  }
}
