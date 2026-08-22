package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.hackerkhu.hackerhp.global.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

  /**
   * T-297 — <b>S3 버킷 없이도 뜬다</b> (#207 리뷰).
   *
   * <p>공통 설정의 {@code ${S3_BUCKET}}은 값이 없으면 기동을 막는다 — 설정 누락이 조용히 지나가면 업로드가 엉뚱한 버킷을 가리키기 때문이다. 그 엄격함이
   * <b>로컬에서는 반대로 작동한다</b>: 저장소가 안내하는 실행 명령에는 그 값이 없어, 업로드 API를 부르기도 전에 서버가 뜨지 않는다.
   *
   * <p>구글 자격 자리표시자와 같은 이유이고 같은 함정의 다른 자리다.
   */
  @Test
  void localProfileStartsWithoutAnS3Bucket() {
    new ApplicationContextRunner()
        .withInitializer(
            context ->
                TestPropertyValues.of("spring.profiles.active=local")
                    .applyTo(context.getEnvironment()))
        .withInitializer(
            new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
        .withUserConfiguration(StorageBinding.class)
        .run(
            (AssertableApplicationContext context) -> {
              assertThat(context).hasNotFailed();
              /*
               * "비어 있지 않다"로는 부족하다. 이 러너는 자리표시자를 풀어 주지 않아, 값이 없으면
               * "${S3_BUCKET}"이라는 글자가 그대로 바인딩되고 그 검사는 통과한다 — 정작 진짜
               * 애플리케이션은 기동에 실패하는데도 초록불이 뜬다.
               *
               * 그래서 "로컬이 스스로 값을 채웠는가"를 본다.
               */
              assertThat(context.getBean(StorageProperties.class).bucket())
                  .as("local 프로파일이 버킷 기본값을 채워야 한다")
                  .doesNotContain("${");
            });
  }

  @EnableConfigurationProperties(StorageProperties.class)
  static class StorageBinding {}

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
