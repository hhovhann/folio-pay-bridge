package dev.foliopay.integration;

import com.sun.net.httpserver.HttpServer;
import dev.foliopaysim.SimulatorApplication;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Test helpers: start a simulator on a random port, or a tiny stub server that answers with exactly the status the test needs. */
public final class SimulatorHarness {
  private SimulatorHarness() {}

  public record Sim(ConfigurableApplicationContext context, String url) implements AutoCloseable {
    @Override
    public void close() { context.close(); }
  }

  public static Sim start(String profile, String... extraArgs) {
    String[] args = new String[3 + extraArgs.length];
    args[0] = "--server.port=0";
    args[1] = "--spring.main.banner-mode=off";
    args[2] = "--sim.zone=Asia/Dubai";
    System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
    ConfigurableApplicationContext context = new SpringApplicationBuilder(SimulatorApplication.class).web(WebApplicationType.SERVLET).profiles(profile).run(args);
    return new Sim(context, "http://localhost:" + context.getEnvironment().getProperty("local.server.port"));
  }

  /** A server whose every response comes from the handler: (method + " " + path) to (status, body). */
  public record Stub(HttpServer server, String url) implements AutoCloseable {
    @Override
    public void close() { server.stop(0); }
  }

  public record Reply(int status, String body) {}

  public static Stub stub(BiFunction<String, String, Reply> handler) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        Reply reply = handler.apply(exchange.getRequestMethod(), exchange.getRequestURI().getPath());
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
      });
      server.start();
      return new Stub(server, "http://127.0.0.1:" + server.getAddress().getPort());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
