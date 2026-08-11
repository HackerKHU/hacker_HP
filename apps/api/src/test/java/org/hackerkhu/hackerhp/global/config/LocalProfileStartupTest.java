package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * {@code local} 프로파일이 <b>구글 자격 없이도 뜨는지</b> 본다.
 *
 * <p>서버를 띄워 헬스체크나 다른 API를 보려는 사람에게까지 구글 클라이언트 시크릿을 요구할 이유가 없다. 그런데 {@code client-id}가 빈 문자열이면
 * Spring Boot가 {@code ClientRegistration}을 만들면서 거절하고 컨텍스트가 통째로 뜨지 않는다 — 자리표시자에 기본값을 비워두면 "없음"이 아니라
 * "빈 값"이 되기 때문이다.
 *
 * <p>실제 {@code application-local.yml}을 읽어서 검증한다. 값을 테스트에서 흉내 내면 정작 그 파일이 바뀌었을 때 아무것도 잡지 못한다.
 */
class LocalProfileStartupTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  // 실제 설정 파일을 읽되, 이 테스트가 도는 환경에 구글 자격이 있어도 없는 것처럼 둔다.
                  TestPropertyValues.of("spring.profiles.active=local")
                      .applyTo(context.getEnvironment()))
          .withInitializer(
              new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
          .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class));

  /* T-135 — 구글 자격이 없어도 local 프로파일 컨텍스트가 뜬다. */
  @Test
  void localProfileStartsWithoutGoogleCredentials() {
    runner.run(
        (AssertableApplicationContext context) -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
        });
  }
}
