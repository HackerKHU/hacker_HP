package org.hackerkhu.hackerhp.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 명세. 구현된 계약을 프론트엔드가 문서로 확인하는 자리다.
 *
 * <p><b>이 문서는 로그인해야 볼 수 있다.</b> {@code SecurityConfig}의 {@code permitAll}에 문서 경로를 넣지 않았다 — 승인제 사이트라
 * 명세가 공개되면 엔드포인트·필드·검증 규칙이 전부 드러난다. 팀원은 로그인한 브라우저로 열면 되고, 로컬 개발에서는 각자 서버를 띄운다.
 */
@Configuration
public class OpenApiConfig {

  /** 신원 토큰. {@code httpOnly}라 스크립트가 읽지 못한다. */
  static final String ACCESS_TOKEN_SCHEME = "accessToken";

  /** 인가 상태(세션). 신원 토큰과 <b>함께</b> 있어야 인증이 성립한다 (spec 3-1 §3-1-5). */
  static final String SESSION_SCHEME = "session";

  /** 상태를 바꾸는 요청에 필요한 CSRF 토큰 (spec 3-2 §3-2-3). */
  public static final String CSRF_SCHEME = "csrfToken";

  @Bean
  public OpenAPI hackerHpOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("hacker_HP API")
                .version("v1")
                .description(
                    """
                    동아리 내부용 웹사이트 API.

                    **인증은 쿠키 두 개가 함께 있어야 성립한다** — 신원은 `ACCESS_TOKEN`(JWT),
                    인가 상태는 `SESSION`이 담당한다. 한쪽만으로는 통과하지 못한다.

                    로그인은 구글 OAuth 하나뿐이며 브라우저를 이동시키는 흐름이라 이 문서에 나오지
                    않는다. 시작 경로는 `GET /api/v1/oauth2/authorization/google`이고, 콜백은
                    `GET /api/v1/login/oauth2/code/google`이 받는다. 성공하면 `/`로, 실패하면
                    `/login?error={domain|unverified|suspended|failed}`로 되돌린다.

                    **Swagger UI의 "Try it out"은 조회만 된다.** 인증 쿠키가 `httpOnly`라 화면이
                    넣어줄 수 없고, 쓰기 요청에 필요한 `X-XSRF-TOKEN` 헤더도 UI가 채우지 못한다.
                    같은 브라우저에서 로그인한 상태면 조회는 그대로 동작한다.
                    """))
        .components(
            new Components()
                .addSecuritySchemes(ACCESS_TOKEN_SCHEME, cookie("ACCESS_TOKEN"))
                .addSecuritySchemes(SESSION_SCHEME, cookie("SESSION"))
                .addSecuritySchemes(CSRF_SCHEME, header("X-XSRF-TOKEN")))
        /*
         * 두 스킴을 한 요구사항에 담으면 AND다. 하나씩 따로 담으면 OR가 되어 "둘 중 하나만 있으면
         * 된다"로 읽히는데, 그것은 이 설계가 막으려는 바로 그 조합이다 (T-30·T-31).
         */
        .addSecurityItem(
            new SecurityRequirement().addList(ACCESS_TOKEN_SCHEME).addList(SESSION_SCHEME));
  }

  private static SecurityScheme cookie(String name) {
    return new SecurityScheme()
        .type(SecurityScheme.Type.APIKEY)
        .in(SecurityScheme.In.COOKIE)
        .name(name);
  }

  private static SecurityScheme header(String name) {
    return new SecurityScheme()
        .type(SecurityScheme.Type.APIKEY)
        .in(SecurityScheme.In.HEADER)
        .name(name);
  }
}
