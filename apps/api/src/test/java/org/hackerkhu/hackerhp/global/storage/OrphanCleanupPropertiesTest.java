package org.hackerkhu.hackerhp.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 안전 여유가 <b>음수면 기동에 실패해야 한다</b> (#342 리뷰).
 *
 * <p>{@code OrphanObjectCleanupJob}은 {@code Instant.now().minus(safetyMargin)}으로 기준 시각을 만든다 — 음수를
 * 넣으면 그 시각이 <b>미래로</b> 가고, 방금 올라와 아직 참조가 커밋되지 않은 최종 오브젝트까지 "오래된 고아"로 분류되어 지워진다. 되돌릴 수 없는 삭제라 <b>배포가
 * 실패하는 편이 낫다</b> ({@code AuthPropertiesTest}와 같은 판단).
 */
class OrphanCleanupPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class)
          .withPropertyValues("app.storage.orphan-cleanup.cron=0 0 4 * * *");

  @Configuration
  @EnableConfigurationProperties(OrphanCleanupProperties.class)
  static class TestConfig {}

  @ParameterizedTest(name = "값=[{0}]")
  @ValueSource(strings = {"-1h", "-1s", "-PT30M"})
  void contextFailsWhenSafetyMarginIsNegative(String value) {
    runner
        .withPropertyValues("app.storage.orphan-cleanup.safety-margin=" + value)
        .run(context -> assertThat(context).hasFailed());
  }

  /** {@code 0}은 허용한다 — 통합 테스트가 삭제 경로를 곧바로 확인할 때 쓰는 값이다. */
  @Test
  void contextStartsWhenSafetyMarginIsZero() {
    runner
        .withPropertyValues("app.storage.orphan-cleanup.safety-margin=0s")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OrphanCleanupProperties.class).safetyMargin())
                  .isEqualTo(Duration.ZERO);
            });
  }

  @Test
  void contextStartsWhenSafetyMarginIsPositive() {
    runner
        .withPropertyValues("app.storage.orphan-cleanup.safety-margin=1h")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OrphanCleanupProperties.class).safetyMargin())
                  .isEqualTo(Duration.ofHours(1));
            });
  }
}
