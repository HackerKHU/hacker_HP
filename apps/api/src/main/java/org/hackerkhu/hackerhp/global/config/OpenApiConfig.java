package org.hackerkhu.hackerhp.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.hackerkhu.hackerhp.global.auth.PublicApi;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * API 명세. 구현된 계약을 프론트엔드가 문서로 확인하는 자리다.
 *
 * <p><b>이 문서는 `ACTIVE` 회원만 볼 수 있다.</b> {@code SecurityConfig}가 문서 경로에 그 조건을 건다 — 승인제 사이트라 명세가 공개되면
 * 엔드포인트·필드·검증 규칙이 전부 드러나고, 승인을 기다리거나 정지된 계정은 인증 영역의 다른 것을 볼 수 없다 (spec 3-1 §3-1-2).
 */
@Configuration
public class OpenApiConfig {

  /** 신원 토큰. {@code httpOnly}라 스크립트가 읽지 못한다. */
  static final String ACCESS_TOKEN_SCHEME = "accessToken";

  /** 인가 상태(세션). 신원 토큰과 <b>함께</b> 있어야 인증이 성립한다 (spec 3-1 §3-1-5). */
  static final String SESSION_SCHEME = "session";

  /** 상태를 바꾸는 요청에 필요한 CSRF 토큰 (spec 3-2 §3-2-3). */
  static final String CSRF_SCHEME = "csrfToken";

  /** 설명 끝에 붙는 줄. 화면이 알아야 할 것은 "무엇을 실어 보내는가"가 아니라 "누가 부를 수 있는가"다. */
  private static final String ACCESS_HEADING = System.lineSeparator() + "**접근 권한** — ";

  private static final Set<PathItem.HttpMethod> WRITE_METHODS =
      EnumSet.of(
          PathItem.HttpMethod.POST,
          PathItem.HttpMethod.PUT,
          PathItem.HttpMethod.PATCH,
          PathItem.HttpMethod.DELETE);

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

  /**
   * 상태를 바꾸는 요청에 CSRF 토큰 요구를 더한다.
   *
   * <p><b>애너테이션으로는 AND를 만들 수 없다.</b> {@code @SecurityRequirement}는 하나가 곧 요구사항 객체 하나이고, 배열에 나열하면 OR가
   * 된다 — "셋 중 하나만 있으면 된다"로 읽혀 실제 서버 동작과 반대가 된다. 세 스킴을 <b>한 객체에</b> 담으려면 만들어진 명세를 여기서 고쳐야 한다.
   *
   * <p>경로마다 적지 않고 메서드로 판단하는 이유는, 앞으로 더할 쓰기 API가 <b>적는 것을 잊어도</b> 자동으로 붙게 하기 위해서다.
   */
  @Bean
  public OpenApiCustomizer csrfOnWriteOperations() {
    return openApi ->
        openApi
            .getPaths()
            .values()
            .forEach(
                pathItem ->
                    pathItem
                        .readOperationsMap()
                        .forEach(
                            (method, operation) -> {
                              if (WRITE_METHODS.contains(method)) {
                                requireCsrf(operation);
                              }
                            }));
  }

  private static void requireCsrf(Operation operation) {
    // 인증이 필요 없다고 명시한 경로(빈 목록)는 건드리지 않는다.
    if (operation.getSecurity() != null && operation.getSecurity().isEmpty()) {
      return;
    }
    operation.setSecurity(
        List.of(
            new SecurityRequirement()
                .addList(ACCESS_TOKEN_SCHEME)
                .addList(SESSION_SCHEME)
                .addList(CSRF_SCHEME)));
  }

  /**
   * 각 엔드포인트의 <b>접근 권한</b>을 설명에 적는다 (#28).
   *
   * <p>보안 스킴은 "무엇을 실어 보내야 하는가"만 말한다 — 쿠키 두 개와 CSRF 토큰. 그런데 화면이 알아야 할 것은 <b>누가 부를 수 있는가</b>이고, 그것은
   * 권한 매트릭스(spec 3-1 §3-1-3)에 있다.
   *
   * <p>{@code @PreAuthorize}의 식을 그대로 옮긴다. 사람이 다시 적으면 <b>코드와 문서가 갈라진다</b> — 표가 원본이라는 규칙을 지키려고 만든 문서가
   * 거짓이 되는 것이 가장 나쁘다.
   */
  @Bean
  public OperationCustomizer documentAccessRules() {
    return (operation, handlerMethod) -> {
      PublicApi open = handlerMethod.getMethodAnnotation(PublicApi.class);
      if (open != null) {
        /*
         * 설명만 고치면 명세가 자기 모순에 빠진다 — "인증 없이 호출한다"고 적어 놓고 전역
         * 요구사항(쿠키 두 개)을 그대로 상속하기 때문이다. 여는 것을 선언한 자리에서 요구사항도
         * 비운다. 빈 목록은 "인증이 필요 없다"는 뜻이고, 키가 없는 것은 "전역을 상속"이라 다르다.
         */
        operation.setSecurity(List.of());
        return describeAccess(operation, "인증 없이 호출한다. " + open.reason());
      }
      PreAuthorize rule = handlerMethod.getMethodAnnotation(PreAuthorize.class);
      return rule == null ? operation : describeAccess(operation, "`" + rule.value() + "`");
    };
  }

  private static Operation describeAccess(Operation operation, String access) {
    String description = operation.getDescription() == null ? "" : operation.getDescription();
    return operation.description(description + System.lineSeparator() + ACCESS_HEADING + access);
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
