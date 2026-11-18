package dev.foliopay.gateway.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/**
 * Oracle's OHIP contract, read from the Swagger 2.0 files in {@code src/test/resources/ohip} (Oracle's published specifications, trimmed by
 * {@code tools/ohip_specs.py}). It finds the operation a request was sent to and lists every way the request or its reply departs from it:
 * an unknown path, a missing required header or form field, an undeclared query parameter or a value outside its enum, a status the
 * operation does not declare, and a body that does not match the operation's schema.
 *
 * <p>Two readings, both stated here because they are ours, not Oracle's: an object may carry only the fields Oracle defines (Oracle's
 * definitions never say so, which would let any invented field through), and a date may be ten characters long (Oracle gives date fields
 * {@code format: date}, which is ISO {@code 2026-11-18}, together with {@code maxLength: 8}, which no ISO date fits).
 */
final class OhipSpec {
  private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final List<String> FILES = List.of("csh.json", "bof.json", "publishedoauth.json");
  private final List<Operation> operations = new ArrayList<>();
  private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4);
  private final Map<String, Schema> compiled = new LinkedHashMap<>();

  record Exchange(String method, URI uri, HttpHeaders requestHeaders, String requestBody, int status, String responseBody) {}

  record Operation(String id, String method, Pattern path, JsonNode spec, JsonNode op, List<JsonNode> parameters) {}

  OhipSpec() {
    for (String file : FILES) {
      JsonNode spec = read(file);
      strict(spec.path("definitions"));
      String base = spec.path("basePath").asText();
      spec.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(method -> {
        if (!method.getValue().has("operationId")) {
          return;
        }
        List<JsonNode> parameters = new ArrayList<>();
        path.getValue().path("parameters").forEach(p -> parameters.add(resolve(spec, p)));
        method.getValue().path("parameters").forEach(p -> parameters.add(resolve(spec, p)));
        Pattern template = Pattern.compile(Pattern.quote(base + path.getKey()).replaceAll("\\{[^}/]+}", "\\\\E[^/]+\\\\Q"));
        operations.add(new Operation(method.getValue().path("operationId").asText(), method.getKey().toUpperCase(), template, spec, method.getValue(), parameters));
      }));
    }
  }

  Optional<Operation> operation(String method, String path) {
    return operations.stream().filter(o -> o.method().equals(method) && o.path().matcher(path).matches()).findFirst();
  }

  /** Every departure from Oracle's contract in one exchange; empty when it conforms. */
  List<String> check(Exchange e) {
    List<String> problems = new ArrayList<>();
    String where = e.method() + " " + e.uri().getPath();
    Optional<Operation> found = operation(e.method(), e.uri().getPath());
    if (found.isEmpty()) {
      return List.of(where + ": Oracle publishes no such operation");
    }
    Operation op = found.get();
    Map<String, List<String>> query = pairs(e.uri().getRawQuery());
    Map<String, List<String>> form = isForm(e.requestHeaders()) ? pairs(e.requestBody()) : Map.of();
    for (JsonNode p : op.parameters()) {
      String name = p.path("name").asText();
      boolean required = p.path("required").asBoolean(false);
      switch (p.path("in").asText()) {
        case "header" -> {
          if (required && e.requestHeaders().getFirst(name) == null) {
            problems.add(where + ": required header " + name + " is missing");
          }
        }
        case "query" -> values(problems, where, "query parameter", p, query.remove(name), required);
        case "formData" -> values(problems, where, "form field", p, form.remove(name), required);
        case "body" -> problems.addAll(body(op, p.path("schema"), e.requestBody(), where + " request"));
        default -> { }
      }
    }
    query.keySet().forEach(name -> problems.add(where + ": query parameter " + name + " is not part of " + op.id()));
    form.keySet().forEach(name -> problems.add(where + ": form field " + name + " is not part of " + op.id()));
    JsonNode response = op.op().path("responses").path(String.valueOf(e.status()));
    if (response.isMissingNode()) {
      problems.add(where + ": " + op.id() + " does not declare status " + e.status());
    } else {
      JsonNode reply = resolveIn(op.spec(), "responses", response);
      if (reply.has("schema") && e.responseBody() != null && !e.responseBody().isBlank()) {
        problems.addAll(body(op, reply.path("schema"), e.responseBody(), where + " reply " + e.status()));
      } else if (reply.has("schema") && e.status() / 100 == 2) {
        problems.add(where + ": " + op.id() + " promises a body with " + e.status() + " and there was none");
      }
    }
    return problems;
  }

  private static void values(List<String> problems, String where, String kind, JsonNode p, List<String> sent, boolean required) {
    String name = p.path("name").asText();
    if (sent == null) {
      if (required) {
        problems.add(where + ": required " + kind + " " + name + " is missing");
      }
      return;
    }
    JsonNode allowed = p.has("enum") ? p.path("enum") : p.path("items").path("enum");
    for (String value : sent) {
      if (allowed.isArray() && !contains(allowed, value)) {
        problems.add(where + ": " + kind + " " + name + "=" + value + " is not one of " + allowed);
      }
    }
  }

  private List<String> body(Operation op, JsonNode schema, String text, String where) {
    JsonNode instance;
    try {
      instance = JSON.readTree(text == null ? "" : text);
    } catch (IOException notJson) {
      return List.of(where + ": body is not JSON");
    }
    if (instance == null || instance.isMissingNode()) {
      return List.of(where + ": body is empty");
    }
    Schema compiledSchema = compiled.computeIfAbsent(op.id() + "|" + schema, key -> {
      ObjectNode root = schema.deepCopy();
      root.set("definitions", op.spec().path("definitions"));
      return schemas.getSchema(root);
    });
    return compiledSchema.validate(instance).stream().map(error -> where + ": " + error.getInstanceLocation() + " " + error.getMessage()).toList();
  }

  /** Oracle's objects, read strictly: only the fields they define. Dates: ISO, not eight characters (see the class comment). */
  private static void strict(JsonNode node) {
    if (node instanceof ObjectNode object) {
      if (object.has("properties") && !object.has("additionalProperties")) {
        object.put("additionalProperties", false);
      }
      if ("date".equals(object.path("format").asText()) && object.path("maxLength").asInt(10) < 10) {
        object.remove("maxLength");
      }
    }
    node.forEach(OhipSpec::strict);
  }

  private static JsonNode resolve(JsonNode spec, JsonNode parameter) { return resolveIn(spec, "parameters", parameter); }

  private static JsonNode resolveIn(JsonNode spec, String section, JsonNode node) {
    String ref = node.path("$ref").asText();
    return ref.startsWith("#/" + section + "/") ? spec.path(section).path(ref.substring(section.length() + 3)) : node;
  }

  private static boolean contains(JsonNode array, String value) {
    for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
      if (it.next().asText().equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isForm(HttpHeaders headers) {
    return headers.getContentType() != null && headers.getContentType().toString().startsWith("application/x-www-form-urlencoded");
  }

  private static Map<String, List<String>> pairs(String encoded) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (encoded == null || encoded.isBlank()) {
      return out;
    }
    for (String pair : encoded.split("&")) {
      String[] kv = pair.split("=", 2);
      out.computeIfAbsent(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), k -> new ArrayList<>()).add(kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
    }
    return out;
  }

  private static JsonNode read(String file) {
    try (InputStream in = OhipSpec.class.getResourceAsStream("/ohip/" + file)) {
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
