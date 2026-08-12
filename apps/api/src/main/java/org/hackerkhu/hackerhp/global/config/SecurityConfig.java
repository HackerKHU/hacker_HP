package org.hackerkhu.hackerhp.global.config;

import org.hackerkhu.hackerhp.global.auth.AccessTokenCookie;
import org.hackerkhu.hackerhp.global.auth.JwtProperties;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.hackerhp.global.auth.JwtSessionAuthenticationFilter;
import org.hackerkhu.hackerhp.global.auth.LoginSuccessHandler;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.error.ErrorResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * 인증 기반 설정. 로그인 수단은 구글 OAuth 하나다 (spec 3-1 §3-1-5 MUST).
 *
 * <p>여기서 하는 것은 <b>기반</b>까지다. 콜백 처리와 계정 생성은 #25, 세션 발급은 #26, 상태별 접근 통제는 #27, CSRF 토큰 발급·검증은 #83이
 * 맡는다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({AuthProperties.class, JwtProperties.class})
public class SecurityConfig {

  /** 인증을 요청 사이에 보관하지 않는다. 매 요청 토큰과 세션을 대조해 새로 세운다. */
  private static final SecurityContextRepository NO_CONTEXT_STORE =
      new NullSecurityContextRepository();

  /** OAuth 경로의 base URI. 프레임워크 기본값 앞에 {@code /api/v1}을 붙인 것이다. */
  private static final String OAUTH_AUTHORIZATION_BASE_URI = "/api/v1/oauth2/authorization";

  private static final String OAUTH_REDIRECTION_BASE_URI = "/api/v1/login/oauth2/code/*";

  /**
   * 인증 없이 열리는 경로. <b>여기 없는 것은 전부 로그인이 필요하다.</b>
   *
   * <p>{@code /actuator/health}가 빠지면 ALB 헬스체크가 401로 실패해 태스크가 무한 재시작한다.
   *
   * <p><b>springdoc 경로는 넣지 않는다</b> (#23에서 정했다). 승인제 사이트라 명세가 공개되면 엔드포인트·필드·검증 규칙이 전부 드러난다. 문서는 아래
   * {@link #API_DOCS_PATHS}가 {@code ACTIVE}에게만 연다.
   */
  private static final String[] PUBLIC_PATHS = {
    "/actuator/health",
    "/actuator/health/**",
    OAUTH_AUTHORIZATION_BASE_URI + "/**",
    "/api/v1/login/oauth2/code/**"
  };

  /**
   * API 문서. <b>{@code ACTIVE} 회원만 볼 수 있다</b> (#23).
   *
   * <p>{@code anyRequest().authenticated()}에 맡기면 <b>인증만 되면 누구나 읽는다</b> — 승인을 기다리는 계정도, 이용 중 정지된 세션도
   * 통과한다. 그러나 {@code PENDING}의 인증 영역은 신청·대기 화면뿐이고 {@code SUSPENDED}는 접근 범위가 없다 (spec 3-1 §3-1-2).
   * 내부 명세는 그 둘에게 열 것이 아니다.
   */
  private static final String[] API_DOCS_PATHS = {
    "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
  };

  private final ErrorResponseWriter errorResponseWriter;
  private final GoogleOidcUserService googleOidcUserService;
  private final LoginSuccessHandler loginSuccessHandler;
  private final LoginFailureHandler loginFailureHandler;
  private final JwtProvider jwtProvider;
  private final AccessTokenCookie accessTokenCookie;
  private final CsrfTokenRepository csrfTokenRepository;

  public SecurityConfig(
      ErrorResponseWriter errorResponseWriter,
      GoogleOidcUserService googleOidcUserService,
      LoginSuccessHandler loginSuccessHandler,
      LoginFailureHandler loginFailureHandler,
      JwtProvider jwtProvider,
      AccessTokenCookie accessTokenCookie,
      CsrfTokenRepository csrfTokenRepository) {
    this.errorResponseWriter = errorResponseWriter;
    this.googleOidcUserService = googleOidcUserService;
    this.loginSuccessHandler = loginSuccessHandler;
    this.loginFailureHandler = loginFailureHandler;
    this.jwtProvider = jwtProvider;
    this.accessTokenCookie = accessTokenCookie;
    this.csrfTokenRepository = csrfTokenRepository;
  }

  /**
   * CSRF 토큰 저장소. 발급 경로({@code GET /auth/csrf})도 같은 것을 써야 하므로 빈으로 노출한다.
   *
   * <p>쿠키 {@code XSRF-TOKEN}은 <b>{@code httpOnly}가 아니다</b> — 화면이 읽어 헤더에 실어야 한다 (spec 3-2 §3-2-3).
   */
  @Bean
  public static CsrfTokenRepository csrfTokenRepository() {
    return CookieCsrfTokenRepository.withHttpOnlyFalse();
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
                    /*
                     * 신청서 제출은 PENDING만이다 (권한 매트릭스 §3-1-3). 컨트롤러의 @PreAuthorize와
                     * 겹쳐 보이지만 둘 다 필요하다 — MVC는 메서드를 부르기 전에 본문을 역직렬화하고
                     * @Valid를 돌리므로, ACTIVE가 깨진 본문을 보내면 @PreAuthorize에 닿기도 전에
                     * 400이 나간다. 그러면 "ACTIVE는 403"이라는 계약(T-50)이 본문에 따라 달라진다.
                     */
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/application")
                    .hasAuthority("STATUS_PENDING")
                    .requestMatchers(API_DOCS_PATHS)
                    .hasAuthority("STATUS_ACTIVE")
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
                    .successHandler(loginSuccessHandler)
                    .failureHandler(loginFailureHandler))
        /*
         * SecurityContext를 세션에 저장하지 않는다. 저장하면 세션만으로 인증이 성립해 T-31(세션만 있고
         * 토큰이 없다)이 새고, 아래 필터의 대조가 무의미해진다. 인증은 매 요청 그 필터가 세운다.
         */
        .securityContext(context -> context.securityContextRepository(NO_CONTEXT_STORE))
        .addFilterBefore(
            new JwtSessionAuthenticationFilter(jwtProvider, accessTokenCookie),
            UsernamePasswordAuthenticationFilter.class)
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

    /*
     * 쿠키에 토큰을 두는 이중 제출 방식으로 바꾼다 (spec 3-2 §3-2-3). 쿠키 XSRF-TOKEN은 httpOnly가
     * 아니어야 화면이 읽어 헤더에 실을 수 있다.
     *
     * 기본 XorCsrfTokenRequestAttributeHandler는 요청마다 값을 섞어, 쿠키 값과 헤더 값이 같아야 한다는
     * 계약을 깨뜨린다. 발급 경로 GET /auth/csrf와 검증 테스트는 #83이 얹는다.
     */
    http.csrf(
        csrf ->
            csrf.csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));

    return http.build();
  }
}
