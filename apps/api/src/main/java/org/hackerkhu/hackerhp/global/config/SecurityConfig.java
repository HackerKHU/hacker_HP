package org.hackerkhu.hackerhp.global.config;

import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.error.ErrorResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * 인증 기반 설정. 로그인 수단은 구글 OAuth 하나다 (spec 3-1 §3-1-5 MUST).
 *
 * <p>여기서 하는 것은 <b>기반</b>까지다. 콜백 처리와 계정 생성은 #25, 세션 발급은 #26, 상태별 접근 통제는 #27, CSRF 토큰 발급·검증은 #83이
 * 맡는다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

  /** OAuth 경로의 base URI. 프레임워크 기본값 앞에 {@code /api/v1}을 붙인 것이다. */
  private static final String OAUTH_AUTHORIZATION_BASE_URI = "/api/v1/oauth2/authorization";

  private static final String OAUTH_REDIRECTION_BASE_URI = "/api/v1/login/oauth2/code/*";

  /** SPA의 로그인 화면. 서버 경로가 아니라 프론트엔드 라우트다. */
  private static final String LOGIN_PAGE_PATH = "/login";

  /**
   * 인증 없이 열리는 경로. <b>여기 없는 것은 전부 로그인이 필요하다.</b>
   *
   * <p>{@code /actuator/health}가 빠지면 ALB 헬스체크가 401로 실패해 태스크가 무한 재시작한다.
   *
   * <p>springdoc 경로는 넣지 않았다. 지금 열어 두면 #23에서 의존성을 더하는 순간 <b>API 명세와 Swagger UI가 비로그인에게 공개된다</b> —
   * 아무도 그것을 결정한 적이 없는데 설정만 먼저 가 있는 셈이다. 그 이슈의 작업 항목에 "Security permitAll 경로에 문서 경로 포함 확인"이 있으므로, 공개
   * 여부는 거기서 정한다.
   */
  private static final String[] PUBLIC_PATHS = {
    "/actuator/health",
    "/actuator/health/**",
    OAUTH_AUTHORIZATION_BASE_URI + "/**",
    "/api/v1/login/oauth2/code/**"
  };

  private final ErrorResponseWriter errorResponseWriter;
  private final GoogleOidcUserService googleOidcUserService;

  public SecurityConfig(
      ErrorResponseWriter errorResponseWriter, GoogleOidcUserService googleOidcUserService) {
    this.errorResponseWriter = errorResponseWriter;
    this.googleOidcUserService = googleOidcUserService;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    // 세션도 토큰도 없는 최초 진입에 필요하다 (3-2 §3-2-3 MUST).
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            oauth2 ->
                oauth2
                    // 프레임워크 기본 경로(/oauth2/...)에 두면 Vercel rewrites가 /api/*만 프록시하므로
                    // 브라우저 요청이 ALB에 닿지 않는다 (3-2 §3-2-3 MUST, 3-3 결정 5).
                    .authorizationEndpoint(
                        endpoint -> endpoint.baseUri(OAUTH_AUTHORIZATION_BASE_URI))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(OAUTH_REDIRECTION_BASE_URI))
                    // 허용 도메인·이메일 인증을 여기서 거른다. 걸지 않으면 콜백이 성공한
                    // 모든 구글 계정이 인증된다 (3-1 §3-1-5 MUST).
                    .userInfoEndpoint(endpoint -> endpoint.oidcUserService(googleOidcUserService))
                    .failureHandler(loginFailureHandler()))
        // 기본 HttpSessionRequestCache는 401로 돌려보내기 전에 그 요청을 세션에 저장한다.
        // 화면은 랜딩을 포함해 최초 렌더마다 GET /auth/me를 부르므로(apps/web/src/auth/session.tsx),
        // 그대로 두면 비로그인 방문자마다 세션 행이 RDS에 쌓인다. 로그인 후 돌아갈 곳도
        // 저장된 /api/v1/auth/me가 되어, SPA 대신 JSON 응답에 멈춰 선다.
        //
        // 저장하지 않아도 잃는 것이 없다. 콜백은 항상 "/"로 되돌리고 화면이 GET /auth/me로
        // 갈 곳을 정한다 (3-2 §3-2-3 MUST).
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .exceptionHandling(
            handling ->
                handling
                    // 필터에서 거절되는 요청은 @RestControllerAdvice에 도달하지 않는다.
                    // 같은 본문 형식을 내보내려고 #22가 만든 작성기를 쓴다 (5-TESTING §5-4).
                    .authenticationEntryPoint(
                        (request, response, authException) ->
                            errorResponseWriter.write(response, ErrorCode.UNAUTHENTICATED))
                    .accessDeniedHandler(
                        (request, response, deniedException) ->
                            errorResponseWriter.write(response, ErrorCode.FORBIDDEN)));

    // CSRF는 끄지 않는다. 토큰 발급 경로와 쿠키 이름 설정은 #83이 얹는다.
    // 지금 끄면 그 이슈가 "다시 켜기"부터 시작해야 하고, 그 사이 상태 변경 API가 열린다.
    return http.build();
  }

  /**
   * 콜백 실패를 SPA로 되돌린다 (3-2 §3-2-3 MUST).
   *
   * <p>기본 처리는 콜백 경로에 오류 응답을 남긴다. 브라우저 전체가 이동한 흐름이라 <b>사용자가 SPA 밖의 빈 화면에 갇힌다</b> — 프론트엔드의 공통 오류 처리도
   * {@code request()}를 거치지 않아 동작하지 않는다.
   *
   * <p>사유는 {@link LoginErrorCode}에 있는 것만 싣는다. Spring의 내부 코드를 그대로 쓰면 주소창·브라우저 기록·리퍼러에 남고, 이용자가 스스로
   * 고칠 수 있는 정보도 아니다.
   */
  private AuthenticationFailureHandler loginFailureHandler() {
    return (request, response, exception) -> {
      LoginErrorCode code =
          exception instanceof OAuth2AuthenticationException oauthException
              ? LoginErrorCode.from(oauthException.getError().getErrorCode())
              : LoginErrorCode.FAILED;
      response.sendRedirect(LOGIN_PAGE_PATH + "?error=" + code.value());
    };
  }
}
