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
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Clean code and safe logging, read from the syntax tree of the main sources with the JDK's own parser (no library, and no regular
 * expression guessing where a method starts or ends). The limits are the engineering standards' (section 3); the code that broke them
 * before they were checked is named below, and the test fails when one of those is fixed too, so each list can only shrink.
 */
@DisplayName("Clean code and safe logging: what the syntax tree of the main code must show")
class CleanCodeTest {
  private static final Path MAIN = Path.of("src/main/java");
  private static final Path TEST = Path.of("src/test/java");
  /** Methods that make a list out of the values written in their arguments. */
  private static final Set<String> WRITTEN_OUT = Set.of("List.of", "Set.of", "Stream.of", "Arrays.asList", "Map.of");
  static final int MAX_PARAMETERS = 4;
  static final int MAX_METHOD_LINES = 30;
  static final int MAX_CLASS_LINES = 300;
  private static final Set<String> LOG_LEVELS = Set.of("trace", "debug", "info", "warn", "error");
  /** The last word of a name that holds a whole message rather than a fact about it: {@code body}, {@code httpRequest}, {@code command}. */
  private static final Set<String> WHOLE_MESSAGE = Set.of("body", "payload", "request", "response", "reply", "form", "json", "command", "dto");
  private static final Set<String> BODY_ACCESSORS = Set.of(
      "body", "getBody", "getPayload", "payload", "getContentAsString", "getContentAsByteArray", "getResponseBodyAsString", "toPrettyString");
  private static final Set<String> EXCEPTION_TEXT = Set.of("getMessage", "getLocalizedMessage", "printStackTrace");
  private static final Set<String> EXCEPTION_NAMES = Set.of("e", "ex", "exception", "failure", "cause", "t", "throwable", "error");

  /** Methods with more than four parameters before the limit was checked (2026-11-21). Constructors are not counted: they list collaborators. */
  private static final Set<String> TOO_MANY_PARAMETERS_TODAY = Set.of(
      "OperaSimulator.post", "OperaSimulator.postPayment", "PspSimulator.entry", "SecurityConfig.api", "OperaSimController.folio",
      "OperaSimController.postPayment", "OperaSimulator.book", "PaymentCommand.bridge", "BookingEventServiceImpl.offer",
      "PostingService.post", "PostingServiceImpl.post", "GlobalExceptionHandler.handleExceptionInternal", "Charge.matches", "PspSimulator.actingOn",
      "OperaSimController.token", "IdempotentStore.saveMerging");
  /** Method bodies over thirty lines before the limit was checked (2026-11-21). */
  private static final Set<String> TOO_LONG_TODAY = Set.of(
      "OperaSimController.postPayment", "SecurityConfig.api", "OperaSimController.folio", "HttpOperaClient.send",
      "ReconciliationServiceImpl.reconcile", "PostingServiceImpl.attempt", "PanMasker.maskRun", "HttpOperaClient.token");
  /** Classes over three hundred lines before the limit was checked (2026-11-21). */
  private static final Set<String> TOO_BIG_TODAY = Set.of("HttpOperaClient", "OperaSimController");
  /**
   * Log calls that may print an exception's message, each read: only the processor's and OPERA's transient exceptions are retried, and their
   * messages are fixed text around an error code that {@code ErrorBodies} has already cut to one line and redacted.
   */
  private static final Set<String> EXCEPTION_TEXT_ALLOWED = Set.of("ResilienceConfig.build");

  record Unit(CompilationUnitTree tree, SourcePositions positions) {
    long line(Tree node) {
      return tree.getLineMap().getLineNumber(positions.getStartPosition(tree, node));
    }

    long lines(Tree node) {
      return tree.getLineMap().getLineNumber(positions.getEndPosition(tree, node)) - line(node) + 1;
    }

    String path() {
      return tree.getSourceFile().getName().replaceAll(".*src/(main|test)/java/", "");
    }
  }

  private static List<Unit> parse(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
      StandardJavaFileManager fileManager = javac.getStandardFileManager(null, null, null);
      List<Path> sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
      JavacTask task =
          (JavacTask) javac.getTask(null, fileManager, null, List.of("-proc:none"), null, fileManager.getJavaFileObjectsFromPaths(sources));
      SourcePositions positions = Trees.instance(task).getSourcePositions();
      List<Unit> units = new ArrayList<>();
      for (CompilationUnitTree tree : task.parse()) {
        units.add(new Unit(tree, positions));
      }
      return units;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Calls {@code visit} with every method of the main code and the simple name of the class that declares it. */
  private static void eachMethod(List<Unit> units, MethodVisitor visit) {
    for (Unit unit : units) {
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
      }.scan(unit.tree(), "");
    }
  }

  interface MethodVisitor {
    void accept(Unit unit, String type, MethodTree method);
  }

  /** Every {@code log.level(...)} call in the main code, with the class and method it is in. */
  private static void eachLogCall(List<Unit> units, Consumer<LogCall> visit) {
    eachMethod(units, (unit, type, method) -> new TreeScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree call, Void nothing) {
        if (call.getMethodSelect() instanceof MemberSelectTree select && select.getExpression() instanceof IdentifierTree receiver
            && receiver.getName().contentEquals("log") && LOG_LEVELS.contains(select.getIdentifier().toString())) {
          visit.accept(new LogCall(unit.path() + ":" + unit.line(call), type + "." + method.getName(), call.getArguments()));
        }
        return super.visitMethodInvocation(call, nothing);
      }
    }.scan(method.getBody(), null));
  }

  record LogCall(String where, String method, List<? extends ExpressionTree> arguments) {}

  private static String name(ExpressionTree expression) {
    if (expression instanceof IdentifierTree identifier) {
      return identifier.getName().toString();
    }
    if (expression instanceof MemberSelectTree select) {
      return select.getIdentifier().toString();
    }
    return expression instanceof MethodInvocationTree call ? name(call.getMethodSelect()) : "";
  }

  /**
   * Fails if a log call is handed a whole message: a request or response body, a command, a DTO. Its fields are logged by name instead,
   * so a field added later (a card token, a secret) cannot reach a log line by being part of the whole (owner's rule, 2026-11-21).
   */
  @Test
  @DisplayName("No log call is handed a raw body, request, response or command, only the facts taken from it")
  void noLogCallIsHandedARawBodyRequestResponseOrCommand() {
    List<String> found = new ArrayList<>();
    eachLogCall(parse(MAIN), call -> call.arguments().stream().skip(1).forEach(argument -> {
      boolean whole = (argument instanceof IdentifierTree || argument instanceof MemberSelectTree) && holdsAWholeMessage(name(argument));
      boolean body = argument instanceof MethodInvocationTree && BODY_ACCESSORS.contains(name(argument));
      if (whole || body) {
        found.add(call.where() + " " + argument);
      }
    }));
    assertThat(found).isEmpty();
  }

  /** By its last word: {@code httpRequest} and {@code command} hold a whole message, {@code requested} (a date) and {@code bodyLength} do not. */
  static boolean holdsAWholeMessage(String name) {
    String[] words = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase().split("[\\s_]+");
    return WHOLE_MESSAGE.contains(words[words.length - 1]);
  }

  @Test
  @DisplayName("The body rule knows a whole message from a fact named after one")
  void theBodyRuleKnowsAWholeMessageFromAFactNamedAfterOne() {
    assertThat(List.of("body", "httpRequest", "command", "responseBody", "reply", "chargeDto", "raw_json"))
        .allMatch(CleanCodeTest::holdsAWholeMessage);
    assertThat(List.of("requested", "bodyLength", "requestId", "commandName", "replyCode", "formatted")).noneMatch(CleanCodeTest::holdsAWholeMessage);
  }

  /**
   * Fails if a log call prints an exception's message or hands over the exception itself: a message can carry what the request held (a card
   * number a parser echoed) or what a third party sent. {@code Redacted.of(e)} keeps the class and the stack, without any message.
   */
  @Test
  @DisplayName("Exceptions reach a log only through Redacted, never with their messages")
  void exceptionsReachALogOnlyThroughRedacted() {
    List<String> found = new ArrayList<>();
    eachLogCall(parse(MAIN), call -> call.arguments().forEach(argument -> {
      String name = name(argument);
      boolean message = argument instanceof MethodInvocationTree && EXCEPTION_TEXT.contains(name) && !EXCEPTION_TEXT_ALLOWED.contains(call.method());
      boolean bare = argument instanceof IdentifierTree && EXCEPTION_NAMES.contains(name);
      if (message || bare) {
        found.add(call.where() + " " + argument);
      }
    }));
    assertThat(found).isEmpty();
  }

  /**
   * Fails if a public method of a service implementation does not begin with a DEBUG line naming itself and its safe parameters,
   * {@code log.debug("charge(client={}, key={}, ...)", ...)}: then a DEBUG log of any request shows every service call it made, in order.
   */
  @Test
  @DisplayName("Every public service method begins by logging its name and its safe parameters at DEBUG")
  void everyPublicServiceMethodBeginsByLoggingItsNameAndItsSafeParameters() {
    List<String> silent = new ArrayList<>();
    int checked = 0;
    for (Unit unit : parse(MAIN.resolve("dev/foliopay/service/impl"))) {
      for (Tree member : ((ClassTree) unit.tree().getTypeDecls().get(0)).getMembers()) {
        if (!(member instanceof MethodTree method) || !method.getModifiers().getFlags().contains(Modifier.PUBLIC) || method.getBody() == null) {
          continue;
        }
        checked++;
        List<? extends StatementTree> body = method.getBody().getStatements();
        if (body.isEmpty() || !entryLine(body.get(0), method.getName().toString())) {
          silent.add(unit.path() + " " + method.getName());
        }
      }
    }
    assertThat(checked).as("public service methods were found").isGreaterThan(30);
    assertThat(silent).isEmpty();
  }

  private static boolean entryLine(StatementTree statement, String method) {
    return statement instanceof ExpressionStatementTree expression && expression.getExpression() instanceof MethodInvocationTree call
        && call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("debug")
        && !call.getArguments().isEmpty() && call.getArguments().get(0) instanceof LiteralTree text
        && String.valueOf(text.getValue()).startsWith(method + "(");
  }

  /**
   * Fails if a plain test loops over inputs it writes out (an array, a {@code List.of}) and asserts in the loop. Each input is then a case
   * of its own: a {@code @ParameterizedTest} reports each one and fails for each one, where a loop stops at the first failing input and
   * hides which it was. A loop over one outcome (every row a query returned, every log event of one flow) is one case and stays a loop.
   */
  @Test
  @DisplayName("A test with several written-out inputs is parameterized, so each input is reported on its own")
  void aTestWithSeveralWrittenOutInputsIsParameterized() {
    List<String> loops = new ArrayList<>();
    eachMethod(parse(TEST), (unit, type, method) -> {
      boolean plainTest = method.getModifiers().getAnnotations().stream()
          .anyMatch(a -> a.getAnnotationType().toString().equals("Test") || a.getAnnotationType().toString().endsWith(".Test"));
      if (!plainTest || method.getBody() == null) {
        return;
      }
      // A table of inputs is often written into a local first: a loop over that name is a loop over written-out inputs too.
      Set<String> tables = new HashSet<>();
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitVariable(VariableTree variable, Void nothing) {
          if (variable.getInitializer() != null && writtenOut(variable.getInitializer())) {
            tables.add(variable.getName().toString());
          }
          return super.visitVariable(variable, nothing);
        }
      }.scan(method.getBody(), null);
      new TreeScanner<Void, Void>() {
        boolean inputs(ExpressionTree values) {
          return writtenOut(values) || values instanceof IdentifierTree name && tables.contains(name.getName().toString());
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree loop, Void nothing) {
          if (inputs(loop.getExpression()) && asserts(loop.getStatement())) {
            loops.add(unit.path() + ":" + unit.line(loop) + " " + type + "." + method.getName());
          }
          return super.visitEnhancedForLoop(loop, nothing);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void nothing) {
          if (call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("forEach")
              && inputs(select.getExpression()) && call.getArguments().stream().anyMatch(CleanCodeTest::asserts)) {
            loops.add(unit.path() + ":" + unit.line(call) + " " + type + "." + method.getName());
          }
          return super.visitMethodInvocation(call, nothing);
        }
      }.scan(method.getBody(), null);
    });
    assertThat(loops).as("make each a @ParameterizedTest with its inputs as the source").isEmpty();
  }

  static boolean writtenOut(ExpressionTree values) {
    if (values instanceof NewArrayTree array) {
      return array.getInitializers() != null;
    }
    return values instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
        && WRITTEN_OUT.contains(select.getExpression() + "." + select.getIdentifier());
  }

  private static boolean asserts(Tree body) {
    return body != null && body.toString().contains("assert");
  }

  @Test
  @DisplayName("A method takes at most four parameters: more is a parameter object waiting to be named")
  void aMethodTakesAtMostFourParameters() {
    Set<String> over = new TreeSet<>();
    eachMethod(parse(MAIN), (unit, type, method) -> {
      if (!method.getName().contentEquals("<init>") && method.getParameters().size() > MAX_PARAMETERS) {
        over.add(type + "." + method.getName());
      }
    });
    assertThat(over).as("new ones: introduce a parameter object; fixed ones: remove them from TOO_MANY_PARAMETERS_TODAY")
        .isEqualTo(new TreeSet<>(TOO_MANY_PARAMETERS_TODAY));
  }

  @Test
  @DisplayName("A method body is at most thirty lines: more is a step waiting to be extracted and named")
  void aMethodBodyIsAtMostThirtyLines() {
    Set<String> over = new TreeSet<>();
    eachMethod(parse(MAIN), (unit, type, method) -> {
      if (method.getBody() != null && unit.lines(method.getBody()) > MAX_METHOD_LINES) {
        over.add(type + "." + method.getName());
      }
    });
    assertThat(over).as("new ones: extract a named step; fixed ones: remove them from TOO_LONG_TODAY").isEqualTo(new TreeSet<>(TOO_LONG_TODAY));
  }

  @Test
  @DisplayName("A class is at most three hundred lines: more is a second responsibility waiting for its own class")
  void aClassIsAtMostThreeHundredLines() {
    Set<String> over = new TreeSet<>();
    for (Unit unit : parse(MAIN)) {
      for (Tree type : unit.tree().getTypeDecls()) {
        if (type instanceof ClassTree declared && unit.lines(type) > MAX_CLASS_LINES) {
          over.add(declared.getSimpleName().toString());
        }
      }
    }
    assertThat(over).as("new ones: split the class; fixed ones: remove them from TOO_BIG_TODAY").isEqualTo(new TreeSet<>(TOO_BIG_TODAY));
  }
}
