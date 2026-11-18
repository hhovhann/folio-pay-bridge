package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.foliopay.TestBase;
import dev.foliopay.config.WebhookProperties;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Notification;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.NotificationRepository;
import dev.foliopay.service.NotificationService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.support.WebhookSignature;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Webhooks against the real database and a real HTTP receiver (D-35). The relay's schedule is pushed out of the way; the test calls
 * collect and deliver itself, so it sees each step.
 */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class WebhookIntegrationTest extends TestBase {
  private static final String SECRET = "whsec_test_0123456789abcdef0123456789";
  private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
  private static final AtomicInteger ANSWER = new AtomicInteger(204);
  private static final HttpServer RECEIVER = receiver();

  record Received(String id, String timestamp, String signature, String body) {}

  @Autowired private NotificationService notifications;
  @Autowired private NotificationRepository repository;
  @Autowired private PaymentService payments;
  @Autowired private ChargeRepository charges;
  @Autowired private org.springframework.test.web.servlet.MockMvc mvc;

  private static HttpServer receiver() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newFixedThreadPool(8));
      server.createContext("/hooks", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var h = exchange.getRequestHeaders();
        RECEIVED.add(new Received(h.getFirst("Webhook-Id"), h.getFirst("Webhook-Timestamp"), h.getFirst("Webhook-Signature"), body));
        exchange.sendResponseHeaders(ANSWER.get(), -1);
        exchange.close();
      });
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void webhook(DynamicPropertyRegistry registry) {
    registry.add("foliopay.webhook.url", () -> "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/hooks");
    registry.add("foliopay.webhook.secret", () -> SECRET);
    registry.add("foliopay.webhook.allow-plain-http", () -> "true");
    registry.add("foliopay.webhook.poll", () -> "1h"); // the test drives each step itself
  }

  @AfterAll
  static void stop() { RECEIVER.stop(0); }

  @BeforeEach
  void clean() {
    repository.deleteAll();
    RECEIVED.clear();
    ANSWER.set(204);
  }

  private PaymentService.Result pay(String booking) {
    return payments.pay(new PaymentCommand("k-" + UUID.randomUUID(), booking, "RES-" + booking, token, new Money(12_500, "USD"), null));
  }

  @Test
  void aCapturedPaymentAndItsFolioPostingAreEachAnnouncedOnceSignedAndWithoutCardData() {
    PaymentService.Result paid = pay("BK-7");

    assertThat(notifications.collect()).isEqualTo(2);
    assertThat(notifications.collect()).as("the same state is never queued twice").isZero();
    assertThat(notifications.deliverDue()).isEqualTo(2);

    assertThat(RECEIVED).extracting(Received::id)
        .containsExactlyInAnyOrder("payment.captured:" + paid.charge().getId(), "folio.posted:" + paid.posting().getId());
    for (Received r : RECEIVED) {
      assertThat(r.signature()).as("the receiver can verify it came from us").isEqualTo(WebhookSignature.sign(SECRET, Long.parseLong(r.timestamp()), r.body()));
      assertThat(r.body()).doesNotContain(PAN).doesNotContain(token).doesNotContain("4111");
    }
    assertThat(RECEIVED.stream().filter(r -> r.id().startsWith("payment.captured")).findFirst().orElseThrow().body())
        .contains("\"bookingRef\":\"BK-7\"", "\"amountMinor\":12500", "\"currency\":\"USD\"", "\"pspRef\"");
    assertThat(repository.findAll()).allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(Notification.Status.DELIVERED));
    assertThat(notifications.deliverDue()).as("a delivered event is not sent again").isZero();
  }

  /** Fails if announced rows are fetched again: once 100 were waiting, every run would see the same 100 and newer events never go out. */
  @Test
  void aBacklogLargerThanOneBatchDrainsInsteadOfStallingOnTheFirstPage() {
    for (int i = 0; i < 101; i++) {
      Charge c = new Charge("k-backlog-" + i, "BK-B" + i, "F-B" + i, dev.foliopay.Fixtures.DAY, token, new Money(100, "USD"));
      c.captured("cap_b" + i);
      charges.save(c);
    }

    assertThat(notifications.collect()).isEqualTo(100);
    assertThat(notifications.collect()).isEqualTo(1);
    assertThat(notifications.collect()).isZero();
  }

  @Test
  void aDeclineIsAnnouncedAsADecline() {
    psp.arm(FakePsp.Fault.DECLINE_NEXT);
    PaymentService.Result declined = pay("BK-8");

    notifications.collect();
    notifications.deliverDue();

    assertThat(RECEIVED).extracting(Received::id).containsExactly("payment.declined:" + declined.charge().getId());
  }

  /** Fails if a failed delivery is dropped, or retried at once in a tight loop instead of after a wait. */
  @Test
  void aReceiverThatFailsKeepsTheEventPendingForALaterAttempt() {
    ANSWER.set(500);
    pay("BK-9");
    notifications.collect();

    assertThat(notifications.deliverDue()).isZero();
    assertThat(RECEIVED).hasSize(2);
    assertThat(notifications.deliverDue()).as("not due again until its wait is over").isZero();
    assertThat(RECEIVED).hasSize(2);
    assertThat(repository.findAll()).allSatisfy(n -> {
      assertThat(n.getStatus()).isEqualTo(Notification.Status.PENDING);
      assertThat(n.getAttempts()).isEqualTo(1);
      assertThat(n.getLastError()).isEqualTo("The receiver answered 500");
    });
  }

  @Test
  void shadowRunsAreNotRevenueAndAreNeverAnnounced() {
    Charge shadow = new Charge("shadow-run-1", "BK-S", "F-S", dev.foliopay.Fixtures.DAY, token, new Money(100, "USD"));
    shadow.captured("cap_shadow");
    charges.saveAndFlush(shadow);

    assertThat(notifications.collect()).isZero();
  }

  /** Fails without the send lease: two instances running the relay at the same moment would both deliver every event. */
  @Test
  void twoRelaysRunningAtOnceDeliverEachEventOnce() throws Exception {
    for (int i = 0; i < 5; i++) pay("BK-R" + i);
    notifications.collect();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Integer> a = pool.submit(notifications::deliverDue);
    Future<Integer> b = pool.submit(notifications::deliverDue);

    assertThat(a.get() + b.get()).isEqualTo(10);
    assertThat(RECEIVED).hasSize(10);
    assertThat(RECEIVED.stream().map(Received::id).distinct().count()).isEqualTo(10);
    pool.shutdown();
  }

  @Test
  void aWeakSecretOrAPlainHttpAddressStopsStartUp() {
    assertThatThrownBy(() -> new WebhookProperties("https://hooks.example.test/x", "short", 12, Duration.ofSeconds(5), false, Duration.ofDays(30)).requireUsable())
        .hasMessageContaining("at least 32");
    assertThatThrownBy(() -> new WebhookProperties("http://hooks.example.test/x", SECRET, 12, Duration.ofSeconds(5), false, Duration.ofDays(30)).requireUsable())
        .hasMessageContaining("must be https");
  }

  @Test
  void anOperatorCanSeeEveryEventAndSendAFailedOneAgain() throws Exception {
    ANSWER.set(500);
    pay("BK-OPS");
    notifications.collect();
    Notification one = repository.findAll().get(0);
    ReflectionFailed.fail(one, repository);

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(2));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/notifications/" + one.getId() + "/redeliver"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("PENDING"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.attempts").value(0));
  }

  /** Puts an event in FAILED the way the last attempt would. */
  private static final class ReflectionFailed {
    static void fail(Notification n, NotificationRepository repository) {
      n.failed("The receiver answered 500", java.time.Instant.now(), 1);
      repository.save(n);
    }
  }

  /** The relay looks back a bounded window, so it does not rescan all of history every few seconds (review finding). */
  @Test
  void aPaymentOlderThanTheLookbackWindowIsNotRescanned() {
    Charge old = new Charge("k-old", "BK-OLD", "F-OLD", dev.foliopay.Fixtures.DAY, token, new Money(100, "USD"));
    old.captured("cap_old");
    org.springframework.test.util.ReflectionTestUtils.setField(old, "createdAt", java.time.Instant.now().minus(Duration.ofDays(31)));
    charges.saveAndFlush(old);

    assertThat(notifications.collect()).isZero();
  }
}
