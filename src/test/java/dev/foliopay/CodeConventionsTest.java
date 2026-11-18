package dev.foliopay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Java conventions of docs/engineering-standards.md section 3 that a few lines of code can check, on every build, so they do not
 * depend on someone remembering them (agent log 48 is what happens when style is fixed by hand). Line length is left to a formatter.
 */
class CodeConventionsTest {
  private static final Path MAIN = Path.of("src/main/java");

  record Source(String path, String text) {
    /** The code without string literals, comments, package and import lines: what a convention about code should look at. */
    String code() {
      return text.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "")
          .replaceAll("(?m)^(package|import) [^\\n]*", "");
    }
  }

  private static List<Source> sources() {
    try (Stream<Path> files = Files.walk(MAIN)) {
      List<Source> all = new ArrayList<>();
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        all.add(new Source(MAIN.relativize(file).toString(), Files.readString(file)));
      }
      return all;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> matching(Pattern pattern, boolean inCodeOnly) {
    List<String> found = new ArrayList<>();
    for (Source source : sources()) {
      Matcher m = pattern.matcher(inCodeOnly ? source.code() : source.text());
      while (m.find()) {
        found.add(source.path() + ": " + m.group().strip());
      }
    }
    return found;
  }

  @Test
  void importsAreSortedStaticFirstWithoutWildcards() {
    List<String> wrong = new ArrayList<>();
    Comparator<String> byName = Comparator.comparing(line -> line.replaceFirst(";$", ""));
    for (Source source : sources()) {
      List<String> imports = source.text().lines().filter(l -> l.startsWith("import ")).toList();
      List<String> expected = Stream.concat(
          imports.stream().filter(l -> l.startsWith("import static ")).sorted(byName),
          imports.stream().filter(l -> !l.startsWith("import static ")).sorted(byName)).toList();
      if (!imports.equals(expected)) {
        wrong.add(source.path() + ": imports out of order");
      }
      imports.stream().filter(l -> l.endsWith(".*;")).forEach(l -> wrong.add(source.path() + ": wildcard " + l));
    }
    assertThat(wrong).isEmpty();
  }

  /** Fails on `if (x) return y;`: a body without braces is where the second statement added later silently escapes the condition. */
  @Test
  void everyIfHasBraces() {
    assertThat(matching(Pattern.compile("(?m)^\\s*(\\} else )?(if|for|while) \\(.*\\)\\s+(return|throw|continue|break)\\b[^\\n]*;\\s*$"), true)).isEmpty();
    assertThat(matching(Pattern.compile("(?m)\\belse\\s+(return|throw)\\b[^\\n]*;"), true)).isEmpty();
  }

  /** A class used by its full name in the middle of code hides a dependency from the import list (and from ArchitectureTest). */
  @Test
  void classesAreImportedNotWrittenOutInFull() {
    assertThat(matching(Pattern.compile("(?<![\\w.])(java|javax|jakarta|org|com|dev|io|software)\\.[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*\\.[A-Z]\\w*"), true))
        .isEmpty();
  }

  /** Constructor injection only: a field-injected dependency cannot be final, and a unit test cannot pass a double without Spring. */
  @Test
  void dependenciesAreInjectedThroughTheConstructor() {
    // Any spelling: a fully qualified @org.springframework...Autowired once slipped past a check for the short name (agent log 49).
    assertThat(matching(Pattern.compile("@([\\w.]+\\.)?(Autowired|Inject)\\b"), true)).isEmpty();
  }

  /** Output goes through the logger, where the request id is attached and card numbers are masked, never straight to the console. */
  @Test
  void nothingPrintsToTheConsoleDirectly() {
    assertThat(matching(Pattern.compile("System\\.(out|err)\\.|\\.printStackTrace\\("), true)).isEmpty();
  }
}
