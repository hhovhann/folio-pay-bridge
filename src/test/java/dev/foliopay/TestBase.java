package dev.foliopay;

import dev.foliopay.domain.model.Pan;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.PaymentLinkRepository;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.CardVault;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Integration tests run the whole application on H2 with the fake processor and PMS. Each test starts from an empty world. */
@SpringBootTest(properties = {
    "foliopay.opera.hotel-id=DEMO1", "foliopay.opera.payment-method=CC", "foliopay.opera.cashier-id=9001", // the simulated hotel
    "foliopay.keys=local", "foliopay.gateway=fake", "foliopay.demo-controls=true", "foliopay.retry.initial-backoff=10ms"})
public abstract class TestBase {
  protected static final String PAN = "4111111111111111";
  @Autowired protected CardVault vault;
  @Autowired protected FakePsp psp;
  @Autowired protected FakeOpera opera;
  @Autowired private ChargeRepository chargeRepository;
  @Autowired private PostingRepository postingRepository;
  @Autowired private PaymentLinkRepository linkRepository;
  protected String token;

  @BeforeEach
  void freshWorld() {
    postingRepository.deleteAll();
    chargeRepository.deleteAll();
    linkRepository.deleteAll();
    psp.reset();
    opera.reset();
    token = vault.tokenise(new Pan(PAN), "12/29").getToken();
  }
}
