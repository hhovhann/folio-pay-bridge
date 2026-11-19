package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.foliopay.Fixtures;
import dev.foliopay.TestBase;
import dev.foliopay.config.WebhookProperties;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Notification;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.NotificationRepository;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.NotificationService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.support.WebhookSignature;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Webhooks against the real database and a real HTTP receiver (D-35). The relay's schedule is pushed out of the way; the test calls
 * collect and deliver itself, so it sees each step.
 */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@DisplayName("Webhooks: against the real database and a real HTTP receiver (D-35)")
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
  @Autowired private PostingRepository postings;
  @Autowired private DataSource dataSource;
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
    return payments.pay(PaymentCommand.bridge("k-" + UUID.randomUUID(), booking, "RES-" + booking, token, new Money(12_500, "USD"), null));
  }

  @Test
  @DisplayName("A captured payment and its folio posting are each announced once, signed and without card data")
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
  @DisplayName("A backlog larger than one batch drains instead of stalling on the first page")
  void aBacklogLargerThanOneBatchDrainsInsteadOfStallingOnTheFirstPage() {
    for (int i = 0; i < 101; i++) {
      Charge c = new Charge(Charge.BRIDGE_CLIENT, "k-backlog-" + i, "BK-B" + i, "F-B" + i, dev.foliopay.Fixtures.DAY, token, new Money(100, "USD"));
      c.markCaptured("cap_b" + i);
      charges.save(c);
    }

    assertThat(notifications.collect()).isEqualTo(100);
    assertThat(notifications.collect()).isEqualTo(1);
    assertThat(notifications.collect()).isZero();
  }

  @Test
  @DisplayName("A decline is announced as a decline")
  void aDeclineIsAnnouncedAsADecline() {
    psp.arm(FakePsp.Fault.DECLINE_NEXT);
    PaymentService.Result declined = pay("BK-8");

    notifications.collect();
    notifications.deliverDue();

    assertThat(RECEIVED).extracting(Received::id).containsExactly("payment.declined:" + declined.charge().getId());
  }

  /** Fails if a failed delivery is dropped, or retried at once in a tight loop instead of after a wait. */
  @Test
  @DisplayName("A receiver that fails keeps the event pending for a later attempt")
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
  @DisplayName("Shadow runs are not revenue and are never announced")
  void shadowRunsAreNotRevenueAndAreNeverAnnounced() {
    Charge shadow = new Charge(Charge.BRIDGE_CLIENT, "shadow-run-1", "BK-S", "F-S", dev.foliopay.Fixtures.DAY, token, new Money(100, "USD"));
    shadow.markCaptured("cap_shadow");
    charges.saveAndFlush(shadow);

    assertThat(notifications.collect()).isZero();
  }

  /** Fails without the send lease: two instances running the relay at the same moment would both deliver every event. */
  @Test
  @DisplayName("Two relays running at once deliver each event once")
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
  @DisplayName("A weak secret or a plain HTTP address stops start-up")
  void aWeakSecretOrAPlainHttpAddressStopsStartUp() {
    assertThatThrownBy(() -> new WebhookProperties("https://hooks.example.test/x", "short", 12, Duration.ofSeconds(5), false, Duration.ofDays(30)).requireUsable())
        .hasMessageContaining("at least 32");
    assertThatThrownBy(() -> new WebhookProperties("http://hooks.example.test/x", SECRET, 12, Duration.ofSeconds(5), false, Duration.ofDays(30)).requireUsable())
        .hasMessageContaining("must be https");
  }

  @Test
  @DisplayName("An operator can see every event and send a failed one again")
  void anOperatorCanSeeEveryEventAndSendAFailedOneAgain() throws Exception {
    ANSWER.set(500);
    pay("BK-OPS");
    notifications.collect();
    Notification one = repository.findAll().get(0);
    ReflectionFailed.fail(one, repository);

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/notifications"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(2));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/notifications/" + one.getId() + "/redeliver"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("PENDING"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.attempts").value(0));
  }

  /** Puts an event in FAILED the way the last attempt would. */
  private static final class ReflectionFailed {
    static void fail(Notification n, NotificationRepository repository) {
      n.recordFailure("The receiver answered 500", java.time.Instant.now(), 1);
      repository.save(n);
    }
  }

  /** The relay looks back a bounded window, so it does not rescan all of history every few seconds (review finding). */
  @Test
  @DisplayName("A payment that last changed before the lookback window is not rescanned")
  void aPaymentThatLastChangedBeforeTheLookbackWindowIsNotRescanned() {
    Charge old = new Charge(Charge.BRIDGE_CLIENT, "k-old", "BK-OLD", "F-OLD", Fixtures.DAY, token, new Money(100, "USD"));
    old.markCaptured("cap_old");
    charges.saveAndFlush(old);
    age("charge", old.getId(), Duration.ofDays(31));

    assertThat(notifications.collect()).isZero();
  }

  /**
   * Fails if the window is on creation instead of the last change: a posting dead-lettered long ago and replayed today would never be
   * announced as posted, and the integrator would believe the folio was never paid (review finding).
   */
  @Test
  @DisplayName("A posting replayed long after it was created is still announced")
  void aPostingReplayedLongAfterItWasCreatedIsStillAnnounced() {
    Charge old = new Charge(Charge.BRIDGE_CLIENT, "k-replayed", "BK-R", "F-R", Fixtures.DAY, token, new Money(100, "USD"));
    old.markCaptured("cap_replayed");
    charges.saveAndFlush(old);
    Posting waiting = Fixtures.posting("post:" + old.getId(), old.getId(), "F-R", new Money(100, "USD"), Fixtures.DAY);
    waiting.moveToDeadLetter(Posting.FailureCategory.OPERA_UNAVAILABLE, "OPERA answered 503");
    postings.saveAndFlush(waiting);
    age("charge", old.getId(), Duration.ofDays(40));
    age("posting", waiting.getId(), Duration.ofDays(40));
    assertThat(notifications.collect()).as("everything is older than the window").isZero();

    Posting replayed = postings.findById(waiting.getId()).orElseThrow();
    replayed.markPosted("TX-LATE");
    postings.saveAndFlush(replayed);

    assertThat(notifications.collect()).isEqualTo(1);
    assertThat(repository.findAll()).extracting(Notification::getEventId).containsExactly("folio.posted:" + waiting.getId());
  }

  /** Moves a row's creation and last change into the past, the way time would: the entity sets its change time itself on every save. */
  private void age(String table, String id, Duration by) {
    Timestamp then = Timestamp.from(Instant.now().minus(by));
    new JdbcTemplate(dataSource).update("update " + table + " set created_at = ?, updated_at = ? where id = ?", then, then, id);
  }
}
