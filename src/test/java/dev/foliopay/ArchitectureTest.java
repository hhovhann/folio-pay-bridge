package dev.foliopay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.WebhookSender;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * The architecture rules of D-14 and the engineering standards (section 2), checked by ArchUnit on the compiled main classes: it sees
 * every dependency a class really has (fields, calls, annotations), not only its import lines. Each rule names the decision it protects.
 */
@DisplayName("Architecture: the layer rules of D-14, checked on every build")
class ArchitectureTest {
  private static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("dev.foliopay", "dev.foliopaysim");
  /** The simulators' engines: application logic of the systems they stand in for, so @Service too. */
  private static final Set<String> SIMULATOR_ENGINES = Set.of("dev.foliopaysim.opera.OperaSimulator", "dev.foliopaysim.psp.PspSimulator");
  /** Not money: the retry back-off multiplier and jitter. Each was checked by reading the class. */
  private static final Set<String> ALLOWED_FLOATING_POINT = Set.of("dev.foliopay.config.RetryProperties", "dev.foliopay.config.ResilienceConfig");

  @Test
  @DisplayName("Controllers never touch a repository, an implementation, an adapter or the vault's crypto")
  void controllersNeverTouchARepositoryAnImplementationAnAdapterOrTheVaultsCrypto() {
    // The error handler may name the ports' exceptions (a 503 for an outage); it may not reach a port, an adapter or a fake.
    noClasses().that().resideInAPackage("dev.foliopay.api..")
        .should().dependOnClassesThat().resideInAnyPackage("dev.foliopay.repository..", "dev.foliopay.service.impl..",
            "dev.foliopay.gateway.http..", "dev.foliopay.gateway.fake..", "dev.foliopay.crypto..")
        .orShould().dependOnClassesThat().belongToAnyOf(PspClient.class, OperaClient.class, WebhookSender.class)
        .because("D-14: api -> service interfaces only").check(MAIN);
  }

  @Test
  @DisplayName("Services never touch HTTP or the API layer")
  void servicesNeverTouchHttpOrTheApiLayer() {
    noClasses().that().resideInAPackage("dev.foliopay.service..")
        .should().dependOnClassesThat().resideInAnyPackage("dev.foliopay.api..", "org.springframework.web..", "jakarta.servlet..",
            "dev.foliopay.gateway.http..")
        .because("D-14: a service never touches HTTP").check(MAIN);
  }

  /** The fault switches of the demo are the one service allowed to reach the in-memory fakes; nothing else may depend on them. */
  @Test
  @DisplayName("Only the demo service sees the fakes")
  void onlyTheDemoServiceSeesTheFakes() {
    noClasses().that().resideOutsideOfPackage("dev.foliopay.gateway.fake..")
        .and().doNotHaveFullyQualifiedName("dev.foliopay.service.impl.DemoServiceImpl")
        .should().dependOnClassesThat().resideInAPackage("dev.foliopay.gateway.fake..")
        .because("D-14: fakes are test and demo doubles").check(MAIN);
  }

  @Test
  @DisplayName("The domain knows no Spring and no other layer")
  void theDomainKnowsNoSpringAndNoOtherLayer() {
    noClasses().that().resideInAPackage("dev.foliopay.domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "dev.foliopay.api..", "dev.foliopay.service..",
            "dev.foliopay.repository..", "dev.foliopay.gateway..", "dev.foliopay.config..", "dev.foliopay.crypto..", "dev.foliopay.logging..")
        .because("D-14: the domain knows only JPA annotations").check(MAIN);
  }

  @Test
  @DisplayName("Ports and adapters point inward: adapters never depend on the core that uses them")
  void portsAndAdaptersPointInward() {
    noClasses().that().resideInAPackage("dev.foliopay.gateway..")
        .should().dependOnClassesThat().resideInAnyPackage("dev.foliopay.api..", "dev.foliopay.service..", "dev.foliopay.repository..")
        .because("adapters depend on the domain and their port, never on the core that uses them").check(MAIN);
    noClasses().that().resideInAPackage("dev.foliopay.repository..")
        .should().dependOnClassesThat().resideInAnyPackage("dev.foliopay.api..", "dev.foliopay.service..", "dev.foliopay.gateway..",
            "dev.foliopay.config..")
        .check(MAIN);
  }

  /** The OPERA and processor simulators share the jar but are separate programs (D-26): neither side may reach into the other. */
  @Test
  @DisplayName("The bridge and the simulators share no code")
  void theBridgeAndTheSimulatorsShareNoCode() {
    noClasses().that().resideInAPackage("dev.foliopay..").should().dependOnClassesThat().resideInAPackage("dev.foliopaysim..").check(MAIN);
    noClasses().that().resideInAPackage("dev.foliopaysim..").should().dependOnClassesThat().resideInAPackage("dev.foliopay..").check(MAIN);
  }

  /** CLAUDE.md: "interface first, impl package-private". A public implementation can be wired around its interface. */
  @Test
  @DisplayName("Implementations and controllers are package-private")
  void implementationsAndControllersArePackagePrivate() {
    classes().that().resideInAnyPackage("dev.foliopay.service.impl", "dev.foliopay.api.controller").and().areTopLevelClasses()
        .should().notBePublic().check(MAIN);
  }

  /**
   * One encryption service (PCI DSS v4.0.1 3.6, 3.7): a cipher, a key generator and the KMS client are used only in dev.foliopay.crypto, so
   * every key is made, wrapped and used in one reviewed place. Signing (an HMAC over a webhook) is not encryption and stays where it is used.
   */
  @Test
  @DisplayName("Encryption and key management happen only in the crypto package")
  void encryptionAndKeyManagementHappenOnlyInTheCryptoPackage() {
    noClasses().that().resideOutsideOfPackage("dev.foliopay.crypto..")
        .should().dependOnClassesThat().haveFullyQualifiedName("javax.crypto.Cipher")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.crypto.KeyGenerator")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.crypto.spec.GCMParameterSpec")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.crypto.spec.IvParameterSpec")
        .orShould().dependOnClassesThat().resideInAPackage("software.amazon.awssdk.services.kms..")
        .check(MAIN);
  }

  /**
   * @Service says "application logic", @Component says "infrastructure" (filters, adapters, guards, helpers); Spring treats them alike,
   * so only a rule keeps them meaningful. Services are the implementations in service.impl and the simulators' engines.
   */
  @Test
  @DisplayName("Services are marked @Service and nothing else is")
  void servicesAreMarkedServiceAndNothingElseIs() {
    classes().that().resideInAPackage("dev.foliopay.service.impl").and().areTopLevelClasses().and().areNotInterfaces()
        .should().beAnnotatedWith(Service.class).andShould().notBeAnnotatedWith(Component.class).check(MAIN);
    classes().that().haveFullyQualifiedName("dev.foliopaysim.opera.OperaSimulator").or().haveFullyQualifiedName("dev.foliopaysim.psp.PspSimulator")
        .should().beAnnotatedWith(Service.class).check(MAIN);
    classes().that().areAnnotatedWith(Service.class).should().resideInAPackage("dev.foliopay.service.impl")
        .orShould(new ArchCondition<>("be a simulator's engine") {
          @Override
          public void check(JavaClass type, ConditionEvents events) {
            if (!SIMULATOR_ENGINES.contains(type.getName())) {
              events.add(SimpleConditionEvent.violated(type, type.getName() + " is @Service but is not a service"));
            }
          }
        }).check(MAIN);
  }

  /** Hard rule 2: money is long minor units. A floating-point or BigDecimal field, parameter or result anywhere fails here first. */
  @Test
  @DisplayName("Money is never floating point or BigDecimal")
  void moneyIsNeverFloatingPointOrBigDecimal() {
    List<Class<?>> forbidden = List.of(double.class, float.class, Double.class, Float.class, BigDecimal.class);
    classes().that().resideInAPackage("dev.foliopay..").should(new ArchCondition<>("not hold floating-point or BigDecimal values") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        if (ALLOWED_FLOATING_POINT.contains(type.getName())) {
          return;
        }
        for (JavaField field : type.getFields()) {
          if (forbidden.stream().anyMatch(field.getRawType()::isEquivalentTo)) {
            events.add(SimpleConditionEvent.violated(field, field.getFullName()));
          }
        }
        for (JavaCodeUnit unit : type.getCodeUnits()) {
          boolean typed = forbidden.stream().anyMatch(f -> unit.getRawReturnType().isEquivalentTo(f)
              || unit.getRawParameterTypes().stream().anyMatch(p -> p.isEquivalentTo(f)));
          if (typed) {
            events.add(SimpleConditionEvent.violated(unit, unit.getFullName()));
          }
        }
      }
    }).check(MAIN);
  }

  /** Constructor injection only: a field-injected dependency cannot be final, and a unit test cannot pass a double without Spring. */
  @Test
  @DisplayName("Dependencies are injected through the constructor, never into fields")
  void dependenciesAreInjectedThroughTheConstructor() {
    GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(MAIN);
  }

  /** Output goes through the logger, where the request id is attached and secrets are redacted, never straight to the console. */
  @Test
  @DisplayName("Nothing prints to the console directly")
  void nothingPrintsToTheConsoleDirectly() {
    GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(MAIN);
  }
}
