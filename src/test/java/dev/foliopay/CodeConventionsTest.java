package dev.foliopay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conventions of docs/engineering-standards.md that a few lines of code can check, on every build, so they do not depend on someone
 * remembering them (agent log 48 is what happens when style is fixed by hand): Java style (section 3), test names (section 10) and
 * dependency versions (section 13). Line length is held by a count that may only go down, until a formatter takes it over.
 */
@DisplayName("Code conventions: the rules of the engineering standards that code can check")
class CodeConventionsTest {
  private static final Path MAIN = Path.of("src/main/java");
  private static final Path TEST = Path.of("src/test/java");
  /** Lines over 150 characters in src/main and src/test today. New code stays under 150 (section 3), so this may only go down. */
  private static final int LINES_OVER_150_TODAY = 216;
  private static final Pattern TEST_ANNOTATION = Pattern.compile("@(?:[\\w.]+\\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory)(?![\\w.])");
  private static final Pattern DISPLAY_NAME = Pattern.compile("@(?:[\\w.]+\\.)?DisplayName\\(\"((?:\\\\.|[^\"\\\\])*)\"\\)");
  private static final Pattern MEMBER_NAME = Pattern.compile("(?<![@\\w.])(\\w+)\\s*\\(");
  private static final Pattern TOP_CLASS = Pattern.compile("(?m)^(?:public |abstract |final )*class (\\w+)");
  /** A comment, a character literal or a string literal: blanked, length kept, so positions in the code match positions in the text. */
  private static final Pattern NOT_CODE = Pattern.compile("(?s)//[^\\n]*|/\\*.*?\\*/|'(?:\\\\.|[^'\\\\])'|\"(?:\\\\.|[^\"\\\\])*\"");

  record Source(String path, String text) {
    /** The code without string literals, comments, package and import lines: what a convention about code should look at. */
    String code() {
      return text.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "")
          .replaceAll("(?m)^(package|import) [^\\n]*", "");
    }
  }

  private static List<Source> sources() {
    return sources(MAIN);
  }

  private static List<Source> sources(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      List<Source> all = new ArrayList<>();
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        all.add(new Source(root.relativize(file).toString(), Files.readString(file)));
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
  @DisplayName("Imports are sorted, static first, without wildcards")
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
  @DisplayName("Every if has braces")
  void everyIfHasBraces() {
    assertThat(matching(Pattern.compile("(?m)^\\s*(\\} else )?(if|for|while) \\(.*\\)\\s+(return|throw|continue|break)\\b[^\\n]*;\\s*$"), true)).isEmpty();
    assertThat(matching(Pattern.compile("(?m)\\belse\\s+(return|throw)\\b[^\\n]*;"), true)).isEmpty();
  }

  /** A class used by its full name in the middle of code hides a dependency from the import list (and from ArchitectureTest). */
  @Test
  @DisplayName("Classes are imported, not written out in full")
  void classesAreImportedNotWrittenOutInFull() {
    assertThat(matching(Pattern.compile("(?<![\\w.])(java|javax|jakarta|org|com|dev|io|software)\\.[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*\\.[A-Z]\\w*"), true))
        .isEmpty();
  }

  /** Constructor injection only: a field-injected dependency cannot be final, and a unit test cannot pass a double without Spring. */
  @Test
  @DisplayName("Dependencies are injected through the constructor, never into fields")
  void dependenciesAreInjectedThroughTheConstructor() {
    // Any spelling: a fully qualified @org.springframework...Autowired once slipped past a check for the short name (agent log 49).
    assertThat(matching(Pattern.compile("@([\\w.]+\\.)?(Autowired|Inject)\\b"), true)).isEmpty();
  }

  /** Output goes through the logger, where the request id is attached and card numbers are masked, never straight to the console. */
  @Test
  @DisplayName("Nothing prints to the console directly")
  void nothingPrintsToTheConsoleDirectly() {
    assertThat(matching(Pattern.compile("System\\.(out|err)\\.|\\.printStackTrace\\("), true)).isEmpty();
  }

  /**
   * Fails on a test or a test class without a display name that says, as a sentence, what it proves. Reports and the IDE show the display
   * name; the method name stays the stable id that validation.md and tools/check_specs.py cite. Any spelling of the annotations counts
   * (agent log 49), and two tests of one class may not share a name.
   */
  @Test
  @DisplayName("Every test and test class says in a display name, as a sentence, what it proves")
  void everyTestAndTestClassSaysWhatItProvesInADisplayName() {
    List<String> wrong = new ArrayList<>();
    for (Source source : sources(TEST)) {
      String code = NOT_CODE.matcher(source.text()).replaceAll(m -> m.group().replaceAll("[^\\n]", " "));
      Matcher test = TEST_ANNOTATION.matcher(code);
      Set<String> names = new HashSet<>();
      boolean hasTests = false;
      while (test.find()) {
        hasTests = true;
        Matcher member = MEMBER_NAME.matcher(code);
        int to = member.find(test.end()) ? member.start() : test.end();
        String method = to == test.end() ? "?" : member.group(1);
        int before = test.start();
        int from = 1 + Math.max(code.lastIndexOf(';', before), Math.max(code.lastIndexOf('}', before), code.lastIndexOf('{', before)));
        String where = source.path() + " " + method;
        sentence(source.text().substring(from, to), method).ifPresentOrElse(name -> {
          if (!names.add(name)) {
            wrong.add(where + ": the same display name as another test of its class");
          }
        }, () -> wrong.add(where + ": no display name that is a sentence"));
      }
      Matcher type = TOP_CLASS.matcher(code);
      if (hasTests && type.find()) {
        String head = source.text().substring(code.lastIndexOf(';', type.start()) + 1, type.start());
        if (sentence(head, type.group(1)).isEmpty()) {
          wrong.add(source.path() + ": the class has no display name that is a sentence");
        }
      }
    }
    assertThat(wrong).isEmpty();
  }

  /** The display name in this stretch of annotations, if it is a sentence: three words at least, and not just the method's name. */
  private static Optional<String> sentence(String annotations, String name) {
    Matcher displayName = DISPLAY_NAME.matcher(annotations);
    return displayName.find() ? Optional.of(displayName.group(1).strip()).filter(text -> text.split("\\s+").length >= 3 && !text.equals(name))
        : Optional.empty();
  }

  /** Fails when a line over 150 characters is added; when old ones are wrapped, the count is lowered here, so it can only go down. */
  @Test
  @DisplayName("No line over 150 characters is added: the number of long lines only goes down")
  void theNumberOfLinesOver150CharactersOnlyGoesDown() {
    long over = Stream.concat(sources(MAIN).stream(), sources(TEST).stream()).flatMap(s -> s.text().lines()).filter(l -> l.length() > 150).count();
    assertThat(over).as("lines over 150 characters: wrap the new ones; if old ones were wrapped, set LINES_OVER_150_TODAY to %d", over)
        .isEqualTo(LINES_OVER_150_TODAY);
  }

  /** Spring Boot manages versions; one it does not manage is declared once, as a property, never inline in a dependency or plugin (section 13). */
  @Test
  @DisplayName("Every version the build declares for a dependency or a plugin is a property")
  void everyVersionTheBuildDeclaresForADependencyOrAPluginIsAProperty() throws IOException {
    String pom = Files.readString(Path.of("pom.xml")).replaceAll("(?s)<configuration>.*?</configuration>", "");
    Matcher declared = Pattern.compile("(?s)<(dependency|plugin)>((?:(?!</\\1>).)*?)</\\1>").matcher(pom);
    List<String> inline = new ArrayList<>();
    while (declared.find()) {
      Matcher version = Pattern.compile("<version>([^<]*)</version>").matcher(declared.group(2));
      if (version.find() && !version.group(1).startsWith("${")) {
        inline.add(declared.group().replaceAll("\\s+", " "));
      }
    }
    assertThat(inline).isEmpty();
  }
}
