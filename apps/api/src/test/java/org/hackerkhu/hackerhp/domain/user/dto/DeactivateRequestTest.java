package org.hackerkhu.hackerhp.domain.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeactivateRequestTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  @Test
  void nullAndEmptyListsAreValidLegacyAllMemberRequests() {
    assertThat(validator.validate(new DeactivateRequest(null))).isEmpty();
    assertThat(validator.validate(new DeactivateRequest(List.of()))).isEmpty();
  }

  @Test
  void oneHundredIdsAreValidButOneHundredAndOneAreNot() {
    assertThat(validator.validate(new DeactivateRequest(Collections.nCopies(100, 1L)))).isEmpty();
    assertThat(validator.validate(new DeactivateRequest(Collections.nCopies(101, 1L)))).hasSize(1);
  }

  @Test
  void selectedIdsMustBePresentAndPositive() {
    assertThat(validator.validate(new DeactivateRequest(Collections.singletonList(null))))
        .hasSize(1);
    assertThat(validator.validate(new DeactivateRequest(List.of(0L)))).hasSize(1);
    assertThat(validator.validate(new DeactivateRequest(List.of(-1L)))).hasSize(1);
  }
}
