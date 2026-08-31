package org.hackerkhu.hackerhp.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminSuspensionPolicy;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API 명세가 나오는지, 그리고 <b>아무나 볼 수는 없는지</b> 확인한다 (#23).
 *
 * <p>세션을 직접 붙이려고 Spring Session 자동 설정을 뺀다 — 이유는 {@code AuthControllerIntegrationTest}에 적어 두었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest extends AbstractIntegrationTest {

  private static final String API_DOCS = "/v3/api-docs";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User member;

  @BeforeEach
  void signIn() {
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(Accounts.approved("sub-doc", "doc@khu.ac.kr", "20240001"));
  }

  /**
   * 문서를 볼 수 있는 것은 승인된 회원뿐이다. 신청 → 승인이 실제 경로다 (§3-1-4).
   *
   * <p>학번을 인자로 받는다. 계정마다 달라야 한다 — UNIQUE 제약이 있다.
   */
  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
    return signedInAs(builder, member);
  }

  private MockHttpServletRequestBuilder signedInAs(
      MockHttpServletRequestBuilder builder, User user) {
    return sessions.as(user, builder);
  }

  /*
   * 문서는 로그인해야 볼 수 있다 (#23에서 정했다).
   *
   * 승인제 사이트라 명세가 공개되면 엔드포인트·필드·검증 규칙이 전부 드러난다. permitAll에
   * 문서 경로를 더하는 변경을 여기서 잡는다.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {API_DOCS, "/swagger-ui/index.html"})
  void documentationRequiresLogin(String path) throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  /*
   * 인증만 되면 통과시키면 안 된다.
   *
   * PENDING의 인증 영역은 신청·대기 화면뿐이고 SUSPENDED는 접근 범위가 없다 (spec §3-1-2).
   * anyRequest().authenticated()에 맡기면 그 둘이 내부 명세를 그대로 읽는다.
   *
   * 사유는 상태별로 갈린다 (#27). FORBIDDEN 하나로 뭉개면 화면이 승인 대기 안내와 정지 안내를
   * 고르지 못한다.
   */
  @Test
  void pendingCannotReadTheSpecification() throws Exception {
    User pending =
        userRepository.saveAndFlush(
            User.createFromGoogle("sub-pending", "pending@khu.ac.kr", "대기자"));

    mockMvc
        .perform(signedInAs(get(API_DOCS), pending))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  @Test
  void suspendedCannotReadTheSpecification() throws Exception {
    User suspended = Accounts.approved("sub-suspended", "suspended@khu.ac.kr", "20240002");
    suspended.suspend();
    userRepository.saveAndFlush(suspended);

    mockMvc
        .perform(signedInAs(get(API_DOCS), suspended))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  @Test
  void activeMemberCanReadTheSpecification() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").isString())
        .andExpect(jsonPath("$.info.title").value("hacker_HP API"));
  }

  @Test
  void noteTitleLimitsDocumentNewAndGrandfatheredValues() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(
            jsonPath("$.components.schemas.NoteCreateRequest.properties.title.maxLength")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.NoteUpdateRequest.properties.title.maxLength")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.NoteCreateRequest.properties.title.description")
                .value(org.hamcrest.Matchers.containsString("U+0000~U+0020")))
        .andExpect(
            jsonPath("$.components.schemas.NoteUpdateRequest.properties.title.description")
                .value(org.hamcrest.Matchers.containsString("U+0000~U+0020")))
        .andExpect(
            jsonPath("$.components.schemas.NoteUpdateRequest.properties.title.description")
                .value(org.hamcrest.Matchers.containsString("NBSP(U+00A0)")))
        .andExpect(
            jsonPath("$.components.schemas.NoteUpdateRequest.properties.title.description")
                .value(org.hamcrest.Matchers.containsString("maxLength를 선언하지 않음")));
  }

  /* 구현된 인증 API가 빠짐없이 실린다. 컨트롤러를 더하고 문서를 잊는 것을 잡는다. */
  @Test
  void everyImplementedAuthEndpointAppears() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath("$.paths['/api/v1/auth/csrf'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/auth/application'].post").exists())
        .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post").exists());
  }

  /*
   * 인증은 쿠키 두 개가 함께 있어야 성립한다 (3-1 §3-1-5). 명세도 그렇게 읽혀야 한다.
   *
   * 요구사항 하나에 두 스킴을 담으면 AND다. 따로 담으면 OR가 되어 "둘 중 하나면 된다"로 읽히는데,
   * 그것은 이 설계가 막으려는 조합이다 (T-30·T-31).
   */
  @Test
  void specificationSaysBothCookiesAreRequiredTogether() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath("$.security[0].accessToken").exists())
        .andExpect(jsonPath("$.security[0].session").exists())
        .andExpect(jsonPath("$.security[1]").doesNotExist())
        .andExpect(jsonPath("$.components.securitySchemes.accessToken.name").value("ACCESS_TOKEN"))
        .andExpect(jsonPath("$.components.securitySchemes.accessToken.in").value("cookie"))
        .andExpect(jsonPath("$.components.securitySchemes.session.name").value("SESSION"))
        .andExpect(jsonPath("$.components.securitySchemes.csrfToken.name").value("X-XSRF-TOKEN"))
        .andExpect(jsonPath("$.components.securitySchemes.csrfToken.in").value("header"));
  }

  /* 토큰 발급 경로는 세션도 토큰도 없는 최초 진입에 필요하다 — 명세에도 인증이 필요 없다고 적힌다. */
  @Test
  void csrfEndpointIsDocumentedAsOpen() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath("$.paths['/api/v1/auth/csrf'].get.security").isEmpty());
  }

  /*
   * 쓰기 요청은 CSRF 토큰 <b>과</b> 쿠키 두 개가 모두 필요하다.
   *
   * 세 스킴이 한 요구사항 객체에 들어가야 AND다. 애너테이션으로 나열하면 객체가 셋으로 갈라져
   * OR가 되고 — "셋 중 하나만 있으면 된다" — 명세가 실제 서버 동작과 반대를 말한다.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/api/v1/auth/application", "/api/v1/auth/logout"})
  void writeOperationsRequireCsrfAndBothCookiesTogether(String path) throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath("$.paths['" + path + "'].post.security[0].accessToken").exists())
        .andExpect(jsonPath("$.paths['" + path + "'].post.security[0].session").exists())
        .andExpect(jsonPath("$.paths['" + path + "'].post.security[0].csrfToken").exists())
        .andExpect(jsonPath("$.paths['" + path + "'].post.security[1]").doesNotExist());
  }

  /*
   * 각 엔드포인트에 접근 권한이 적힌다 (#28).
   *
   * 보안 스킴은 "무엇을 실어 보내는가"만 말한다. 화면이 알아야 할 것은 "누가 부를 수 있는가"이고
   * 그것은 권한 매트릭스에 있다. @PreAuthorize의 식을 그대로 옮기므로 코드와 갈라지지 않는다.
   */
  @Test
  void everyOperationDocumentsWhoMayCallIt() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/application'].post.description")
                .value(org.hamcrest.Matchers.containsString("hasAuthority('STATUS_PENDING')")))
        .andExpect(
            jsonPath("$.paths['/api/v1/notices'].get.description")
                .value(org.hamcrest.Matchers.containsString("isAuthenticated()")))
        // 공개 경로는 왜 열었는지까지 적는다 — 매트릭스에 없는 경로를 여는 것은 결정이다.
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/csrf'].get.description")
                .value(org.hamcrest.Matchers.containsString("인증 없이 호출한다")))
        /*
         * 세션 확인도 공개다 (#190). 열려 있다는 것과 왜 열었는지가 함께 적혀야 한다 —
         * "깜빡한 것"과 "일부러 연 것"을 문서에서도 구별할 수 있어야 한다.
         */
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/me'].get.description")
                .value(org.hamcrest.Matchers.containsString("인증 없이 호출한다")))
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/me'].get.description")
                .value(org.hamcrest.Matchers.containsString("204")));
  }

  /* 오류 응답도 본문 형태를 알려준다. 화면과 코드 생성기가 무엇을 받을지 알아야 한다. */
  @Test
  void errorResponsesDocumentTheContractBody() throws Exception {
    String schema =
        "$.paths['/api/v1/auth/application'].post.responses['409']"
            + ".content['application/json'].schema.$ref";

    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath(schema).value("#/components/schemas/ErrorResponse"))
        .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.code").exists())
        .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.message").exists());
  }

  /** #296. 상태 PATCH 명세도 관리자 권한 회수 선행과 정확한 거절 문구를 설명한다. */
  @Test
  void statusPatchDocumentsAdminRevocationBeforeSuspension() throws Exception {
    String operation = "$.paths['/api/v1/admin/users/{id}/status'].patch";

    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(
            jsonPath(operation + ".description")
                .value(org.hamcrest.Matchers.containsString(AdminSuspensionPolicy.MESSAGE)))
        .andExpect(
            jsonPath(operation + ".responses['403'].description")
                .value(org.hamcrest.Matchers.containsString("관리자 권한을 먼저 회수")));
  }

  /** #295. 선택 비활성화의 optional 본문·상한·부분 실패 reason이 생성된 계약에 드러난다. */
  @Test
  void selectedDeactivationContractAppearsInOpenApi() throws Exception {
    String operation = "$.paths['/api/v1/admin/users/deactivate'].post";

    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath(operation + ".requestBody.required").doesNotExist())
        .andExpect(
            jsonPath(operation + ".requestBody.content['application/json'].schema.$ref")
                .value("#/components/schemas/DeactivateRequest"))
        .andExpect(
            jsonPath("$.components.schemas.DeactivateRequest.properties.userIds.maxItems")
                .value(100))
        .andExpect(
            jsonPath("$.components.schemas.DeactivateResponse.properties.failed.items.$ref")
                .value("#/components/schemas/DeactivateFailure"))
        .andExpect(
            jsonPath("$.components.schemas.DeactivateFailure.properties.reason.enum")
                .value(org.hamcrest.Matchers.hasItems("NOT_FOUND", "NOT_ACTIVE_USER")));
  }

  /** #313. 일괄 활성화·정지의 required 본문·상한·응답·실패 reason을 생성 명세에서 검증한다. */
  @Test
  void bulkStatusChangeContractAppearsInOpenApi() throws Exception {
    String operation = "$.paths['/api/v1/admin/users/status'].patch";

    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath(operation + ".requestBody.required").value(true))
        .andExpect(
            jsonPath(operation + ".requestBody.content['application/json'].schema.$ref")
                .value("#/components/schemas/BulkStatusChangeRequest"))
        .andExpect(
            jsonPath("$.components.schemas.BulkStatusChangeRequest.required")
                .value(org.hamcrest.Matchers.hasItems("userIds", "status")))
        .andExpect(
            jsonPath("$.components.schemas.BulkStatusChangeRequest.properties.userIds.minItems")
                .value(1))
        .andExpect(
            jsonPath("$.components.schemas.BulkStatusChangeRequest.properties.userIds.maxItems")
                .value(100))
        .andExpect(
            jsonPath(
                    "$.components.schemas.BulkStatusChangeRequest.properties.userIds.items.minimum")
                .value(1))
        .andExpect(
            jsonPath("$.components.schemas.BulkStatusChangeRequest.properties.status.enum")
                .value(org.hamcrest.Matchers.contains("ACTIVE", "SUSPENDED")))
        .andExpect(
            jsonPath(operation + ".responses['200'].content['application/json'].schema.$ref")
                .value("#/components/schemas/BulkStatusChangeResponse"))
        .andExpect(
            jsonPath("$.components.schemas.BulkStatusChangeResponse.required")
                .value(org.hamcrest.Matchers.hasItems("targetStatus", "processed", "failed")))
        .andExpect(
            jsonPath("$.components.schemas.BulkStatusChangeFailure.properties.reason.enum")
                .value(
                    org.hamcrest.Matchers.hasItems(
                        "NOT_FOUND",
                        "NOT_APPLIED",
                        "PENDING_NOT_ALLOWED",
                        "ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION")))
        .andExpect(
            jsonPath(operation + ".responses['500'].description")
                .value(org.hamcrest.Matchers.containsString("커밋")));
  }
}
