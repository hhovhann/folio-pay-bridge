package dev.foliopay;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The agreed rules no standard tool checks (phase 22): Checkstyle holds style and size, ArchUnit the architecture, and this test the rest,
 * read from the sources with the JDK's own parser and from the compiled tests with ArchUnit's importer. Each rule cites the agent-log
 * entry that made it.
 */
@DisplayName("Project rules: what Checkstyle and ArchUnit cannot check")
class ProjectRulesTest {
  private static final Set<String> LOG_LEVELS = Set.of("trace", "debug", "info", "warn", "error");
  /** The last word of a name that holds a whole message rather than a fact about it: {@code body}, {@code httpRequest}, {@code command}. */
  private static final Set<String> WHOLE_MESSAGE = Set.of("body", "payload", "request", "response", "reply", "form", "json", "command", "dto");
  private static final Set<String> BODY_ACCESSORS = Set.of(
      "body", "getBody", "getPayload", "payload", "getContentAsString", "getContentAsByteArray", "getResponseBodyAsString", "toPrettyString");
  private static final Set<String> EXCEPTION_TEXT = Set.of("getMessage", "getLocalizedMessage", "printStackTrace");
  private static final Set<String> EXCEPTION_NAMES = Set.of("e", "ex", "exception", "failure", "cause", "t", "throwable", "error");
  /** Log calls that may print an exception's message, each read: only our own transient exceptions are retried (fixed text, redacted codes). */
  private static final Set<String> EXCEPTION_TEXT_ALLOWED = Set.of("ResilienceConfig.build");
  private static final Set<String> WRITTEN_OUT = Set.of("List.of", "Set.of", "Stream.of", "Arrays.asList", "Map.of");
  private static final Set<String> TEST_ANNOTATIONS = Set.of("org.junit.jupiter.api.Test", "org.junit.jupiter.params.ParameterizedTest",
      "org.junit.jupiter.api.RepeatedTest", "org.junit.jupiter.api.TestFactory");

  interface MethodVisitor {
    void accept(CompilationUnitTree unit, String type, MethodTree method);
  }

  record LogCall(String where, String method, List<? extends ExpressionTree> arguments) {}

  /** Calls {@code visit} with every method under {@code root} and the simple name of the class that declares it. */
  private static void eachMethod(Path root, MethodVisitor visit) {
    try (Stream<Path> files = Files.walk(root)) {
      JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
      StandardJavaFileManager fileManager = javac.getStandardFileManager(null, null, null);
      List<Path> sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
      JavacTask task =
          (JavacTask) javac.getTask(null, fileManager, null, List.of("-proc:none"), null, fileManager.getJavaFileObjectsFromPaths(sources));
      for (CompilationUnitTree unit : task.parse()) {
        new TreeScanner<Void, String>() {
          @Override
          public Void visitClass(ClassTree type, String outer) {
            return super.visitClass(type, type.getSimpleName().toString());
          }

          @Override
          public Void visitMethod(MethodTree method, String type) {
            visit.accept(unit, type, method);
            return super.visitMethod(method, type);
          }
        }.scan(unit, "");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String where(CompilationUnitTree unit, String type, MethodTree method) {
    return unit.getSourceFile().getName().replaceAll(".*src/(main|test)/java/", "") + " " + type + "." + method.getName();
  }

  /** Every {@code log.level(...)} call in the main code, with the class and method it is in. */
  private static List<LogCall> logCalls() {
    List<LogCall> calls = new ArrayList<>();
    eachMethod(Path.of("src/main/java"), (unit, type, method) -> new TreeScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree call, Void nothing) {
        if (call.getMethodSelect() instanceof MemberSelectTree select && select.getExpression() instanceof IdentifierTree receiver
            && receiver.getName().contentEquals("log") && LOG_LEVELS.contains(select.getIdentifier().toString())) {
          calls.add(new LogCall(where(unit, type, method), type + "." + method.getName(), call.getArguments()));
        }
        return super.visitMethodInvocation(call, nothing);
      }
    }.scan(method.getBody(), null));
    return calls;
  }

  private static String name(ExpressionTree expression) {
    if (expression instanceof IdentifierTree identifier) {
      return identifier.getName().toString();
    }
    if (expression instanceof MemberSelectTree select) {
      return select.getIdentifier().toString();
    }
    return expression instanceof MethodInvocationTree call ? name(call.getMethodSelect()) : "";
  }

  /** By its last word: {@code httpRequest} and {@code command} hold a whole message, {@code requested} (a date) and {@code bodyLength} do not. */
  static boolean holdsAWholeMessage(String name) {
    String[] words = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase().split("[\\s_]+");
    return WHOLE_MESSAGE.contains(words[words.length - 1]);
  }

  /** Fails if a log call is handed a whole message: its fields are logged by name, so a field added later cannot ride along (log 62). */
  @Test
  @DisplayName("No log call is handed a raw body, request, response or command, only the facts taken from it")
  void noLogCallIsHandedARawBodyRequestResponseOrCommand() {
    List<String> found = new ArrayList<>();
    for (LogCall call : logCalls()) {
      call.arguments().stream().skip(1).filter(a -> (a instanceof IdentifierTree || a instanceof MemberSelectTree) && holdsAWholeMessage(name(a))
          || a instanceof MethodInvocationTree && BODY_ACCESSORS.contains(name(a))).forEach(a -> found.add(call.where() + " " + a));
    }
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("The body rule knows a whole message from a fact named after one")
  void theBodyRuleKnowsAWholeMessageFromAFactNamedAfterOne() {
    assertThat(List.of("body", "httpRequest", "command", "responseBody", "reply", "chargeDto", "raw_json"))
        .allMatch(ProjectRulesTest::holdsAWholeMessage);
    assertThat(List.of("requested", "bodyLength", "requestId", "commandName", "replyCode", "formatted"))
        .noneMatch(ProjectRulesTest::holdsAWholeMessage);
  }

  /** Fails if a log call prints an exception's message or the bare exception: {@code Redacted.of(e)} keeps the stack, no message (log 62). */
  @Test
  @DisplayName("Exceptions reach a log only through Redacted, never with their messages")
  void exceptionsReachALogOnlyThroughRedacted() {
    List<String> found = new ArrayList<>();
    for (LogCall call : logCalls()) {
      call.arguments().stream().filter(a -> a instanceof MethodInvocationTree && EXCEPTION_TEXT.contains(name(a))
          && !EXCEPTION_TEXT_ALLOWED.contains(call.method()) || a instanceof IdentifierTree && EXCEPTION_NAMES.contains(name(a)))
          .forEach(a -> found.add(call.where() + " " + a));
    }
    assertThat(found).isEmpty();
  }

  /** Fails if a public service method does not open with {@code log.debug("method(name=value, ...)", ...)} (log 62). */
  @Test
  @DisplayName("Every public service method begins by logging its name and its safe parameters at DEBUG")
  void everyPublicServiceMethodBeginsByLoggingItsNameAndItsSafeParameters() {
    List<String> silent = new ArrayList<>();
    List<String> checked = new ArrayList<>();
    eachMethod(Path.of("src/main/java/dev/foliopay/service/impl"), (unit, type, method) -> {
      if (method.getModifiers().getFlags().contains(Modifier.PUBLIC) && method.getBody() != null) {
        checked.add(type + "." + method.getName());
        List<? extends StatementTree> body = method.getBody().getStatements();
        if (body.isEmpty() || !entryLine(body.get(0), method.getName().toString())) {
          silent.add(where(unit, type, method));
        }
      }
    });
    assertThat(checked).as("public service methods were found").hasSizeGreaterThan(30);
    assertThat(silent).isEmpty();
  }

  private static boolean entryLine(StatementTree statement, String method) {
    return statement instanceof ExpressionStatementTree expression && expression.getExpression() instanceof MethodInvocationTree call
        && call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("debug")
        && !call.getArguments().isEmpty() && call.getArguments().get(0) instanceof LiteralTree text
        && String.valueOf(text.getValue()).startsWith(method + "(");
  }

  /**
   * Fails if a plain test loops over inputs it writes out (an array, a {@code List.of}, directly or through a local) and asserts in the
   * loop: each input is a case of its own, a {@code @ParameterizedTest} that reports it alone. A loop over one outcome stays a loop (log 67).
   */
  @Test
  @DisplayName("A test with several written-out inputs is parameterized, so each input is reported on its own")
  void aTestWithSeveralWrittenOutInputsIsParameterized() {
    List<String> loops = new ArrayList<>();
    eachMethod(Path.of("src/test/java"), (unit, type, method) -> {
      boolean plainTest = method.getModifiers().getAnnotations().stream()
          .anyMatch(a -> a.getAnnotationType().toString().equals("Test") || a.getAnnotationType().toString().endsWith(".Test"));
      if (plainTest && method.getBody() != null) {
        inputLoops(method).forEach(loop -> loops.add(where(unit, type, method)));
      }
    });
    assertThat(loops).as("make each a @ParameterizedTest with its inputs as the source").isEmpty();
  }

  private static List<Tree> inputLoops(MethodTree method) {
    Set<String> tables = new HashSet<>();
    List<Tree> loops = new ArrayList<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitVariable(VariableTree variable, Void nothing) {
        if (variable.getInitializer() != null && writtenOut(variable.getInitializer())) {
          tables.add(variable.getName().toString());
        }
        return super.visitVariable(variable, nothing);
      }

      @Override
      public Void visitEnhancedForLoop(EnhancedForLoopTree loop, Void nothing) {
        if (inputs(loop.getExpression(), tables) && loop.getStatement().toString().contains("assert")) {
          loops.add(loop);
        }
        return super.visitEnhancedForLoop(loop, nothing);
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree call, Void nothing) {
        if (call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("forEach")
            && inputs(select.getExpression(), tables) && call.getArguments().stream().anyMatch(a -> a.toString().contains("assert"))) {
          loops.add(call);
        }
        return super.visitMethodInvocation(call, nothing);
      }
    }.scan(method.getBody(), null);
    return loops;
  }

  private static boolean inputs(ExpressionTree values, Set<String> tables) {
    return writtenOut(values) || values instanceof IdentifierTree name && tables.contains(name.getName().toString());
  }

  private static boolean writtenOut(ExpressionTree values) {
    if (values instanceof NewArrayTree array) {
      return array.getInitializers() != null;
    }
    return values instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
        && WRITTEN_OUT.contains(select.getExpression() + "." + select.getIdentifier());
  }

  /**
   * Fails on a test or a test class without a display name that says, as a sentence, what it proves: three words at least, not the method's
   * name, unique within its class. Read from the compiled tests, so any spelling of the annotations counts (log 49, 56).
   */
  @Test
  @DisplayName("Every test and test class says in a display name, as a sentence, what it proves")
  void everyTestAndTestClassSaysWhatItProvesInADisplayName() {
    List<String> wrong = new ArrayList<>();
    for (JavaClass type : new ClassFileImporter().withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS).importPackages("dev.foliopay",
        "dev.foliopaysim")) {
      List<JavaMethod> tests = type.getMethods().stream()
          .filter(m -> m.getAnnotations().stream().anyMatch(a -> TEST_ANNOTATIONS.contains(a.getRawType().getName()))).toList();
      if (tests.isEmpty()) {
        continue;
      }
      if (!sentence(type.tryGetAnnotationOfType(DisplayName.class).map(DisplayName::value).orElse(""), type.getSimpleName())) {
        wrong.add(type.getName() + ": the class has no display name that is a sentence");
      }
      Set<String> names = new HashSet<>();
      for (JavaMethod test : tests) {
        String name = test.tryGetAnnotationOfType(DisplayName.class).map(DisplayName::value).orElse("");
        if (!sentence(name, test.getName())) {
          wrong.add(type.getName() + "." + test.getName() + ": no display name that is a sentence");
        } else if (!names.add(name)) {
          wrong.add(type.getName() + "." + test.getName() + ": the same display name as another test of its class");
        }
      }
    }
    assertThat(wrong).isEmpty();
  }

  private static boolean sentence(String text, String name) {
    return text.strip().split("\\s+").length >= 3 && !text.strip().equals(name);
  }

  /** Spring Boot manages versions; one it does not manage is declared once, as a property, never inline (standards section 13, log 55). */
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
