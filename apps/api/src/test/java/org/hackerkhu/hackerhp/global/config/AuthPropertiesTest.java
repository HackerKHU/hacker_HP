package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 허용 도메인 설정이 비면 <b>기동에 실패해야 한다</b> (spec 3-1 §3-1-4, docs/ops/infra.md).
 *
 * <p>기본값을 코드에 심어두면 설정 누락이 조용히 지나가고, 아무 구글 계정이나 가입할 수 있는 상태로 서비스가 떠 있게 된다. 배포가 실패하는 편이 낫다.
 */
class AuthPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Configuration
  @EnableConfigurationProperties(AuthProperties.class)
  static class TestConfig {}

  /* T-133 — 키가 없거나 비어 있으면 컨텍스트가 뜨지 않는다. */
  @Test
  void contextFailsWhenDomainIsMissing() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest(name = "값=[{0}]")
  @ValueSource(strings = {"", "   "})
  void contextFailsWhenDomainIsBlank(String value) {
    runner
        .withPropertyValues("app.auth.allowed-email-domain=" + value)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void contextStartsWhenDomainIsSet() {
    runner
        .withPropertyValues("app.auth.allowed-email-domain=khu.ac.kr")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AuthProperties.class).allowedEmailDomain())
                  .isEqualTo("khu.ac.kr");
            });
  }
}
