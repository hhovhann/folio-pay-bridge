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
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests run the whole application on H2 with the fake processor and PMS, configured by the "test" profile
 * (src/test/resources/application-test.properties). Each test starts from an empty world.
 */
@SpringBootTest
@ActiveProfiles("test")
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
