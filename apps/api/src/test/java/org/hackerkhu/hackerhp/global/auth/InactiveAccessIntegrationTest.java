package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 비활동 부원({@code INACTIVE})은 <b>자료만</b> 막힌다 (spec 3-1 §3-1-2, T-334 ~ T-338, #228 #229).
 *
 * <p><b>막히지 않아야 할 것이 열려 있는가</b>가 이 묶음의 어려운 절반이다. {@code INACTIVE}를 {@code SUSPENDED} 옆에 끼워 넣는 구현 —
 * 필터 맨 위에서 상태만 보고 막는 것 — 은 <b>막히는 사례를 전부 통과시키면서</b> 공지도 게시판도 함께 막는다. 막히는 쪽만 재면 그 구현이 정답으로 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InactiveAccessIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;

  private User inactive;
  private User active;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    inactive = userRepository.saveAndFlush(Accounts.inactive("sub-i", "i@khu.ac.kr", "20250001"));
    active = userRepository.saveAndFlush(Accounts.approved("sub-a", "a@khu.ac.kr", "20250002"));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /* --------------------------------------------------------------- 상태 자체 */

  /** T-334. <b>로그인은 된다</b> — {@code SUSPENDED}와 다른 점이 이것이다. */
  @Test
  void anInactiveMemberStaysSignedIn() throws Exception {
    mockMvc
        .perform(sessions.signIn(inactive).on(get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"));
  }

  /** 저장된다 — {@code users_status_check}가 네 값을 받는다. */
  @Test
  void theInactiveStatusIsPersisted() {
    assertThat(userRepository.findById(inactive.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.INACTIVE);
  }

  /* ------------------------------------------------------- 막히지 않아야 할 것 */

  /**
   * T-335. <b>이 묶음의 절반이다.</b> 공지·활동사진·게시판을 그대로 쓴다.
   *
   * <p>여기가 깨지면 <b>로그인은 되는데 갈 곳이 없는 사람</b>이 생기고, 이 상태의 뜻이 통째로 사라진다.
   */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("pathsThatStayOpen")
  void nonNoteFeaturesStayOpenForInactiveMembers(HttpMethod method, String path) throws Exception {
    mockMvc
        .perform(sessions.signIn(inactive).on(MockMvcRequestBuilders.request(method, path)))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
  }

  private static List<Object[]> pathsThatStayOpen() {
    return List.of(
        new Object[] {HttpMethod.GET, "/api/v1/notices"},
        new Object[] {HttpMethod.GET, "/api/v1/photos"},
        new Object[] {HttpMethod.GET, "/api/v1/posts"},
        new Object[] {HttpMethod.GET, "/api/v1/auth/me"},
        new Object[] {HttpMethod.GET, "/api/v1/auth/me/content-summary"});
  }

  /** 게시판 <b>쓰기</b>도 열려 있다 — 읽기만 열어 두면 이 상태의 뜻이 반쪽이 된다. */
  @Test
  void anInactiveMemberCanStillWriteOnTheBoard() throws Exception {
    mockMvc
        .perform(
            Csrf.with(
                sessions
                    .signIn(inactive)
                    .on(
                        post("/api/v1/posts")
                            .contentType("application/json")
                            .content("{\"title\":\"비활동 부원의 글\",\"content\":\"본문\"}"))))
        .andExpect(status().isCreated());
  }

  /** API 문서도 그대로 본다 — 자료가 아니므로 막을 이유가 없다 (#229). */
  @Test
  void anInactiveMemberCanStillReadTheApiDocs() throws Exception {
    mockMvc.perform(sessions.signIn(inactive).on(get("/v3/api-docs"))).andExpect(status().isOk());
  }

  /* --------------------------------------------------------------- 막히는 것 */

  /**
   * T-336 ~ T-338. <b>§3-2-4 표의 열한 경로 전부</b>를 한 번씩 두드린다.
   *
   * <p><b>목록을 손으로 옮겨 적었다.</b> 필터가 접두사로 막으므로, 그 접두사를 기대값으로 쓰면 아무것도 재지 않는 것과 같다 — 계약이 세는 경로가 실제로 그
   * 접두사 아래에 있는지가 이 사례가 보는 전부다.
   *
   * <p>인가·검증보다 <b>앞에서</b> 막히므로 본문이나 존재하지 않는 id와 무관하게 {@code 403 INACTIVE}다.
   */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("noteAndBookmarkPaths")
  void everyNotePathIsBlockedForInactiveMembers(HttpMethod method, String path) throws Exception {
    MockHttpServletRequestBuilder request =
        Csrf.with(sessions.signIn(inactive).on(MockMvcRequestBuilders.request(method, path)));
    mockMvc
        .perform(request)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INACTIVE"));
  }

  /** 출처: `spec/3-2-DESIGN-CONTRACT.md` §3-2-4 API 표의 열한 행. */
  private static List<Object[]> noteAndBookmarkPaths() {
    return List.of(
        new Object[] {HttpMethod.GET, "/api/v1/notes"},
        new Object[] {HttpMethod.GET, "/api/v1/notes/filters"},
        new Object[] {HttpMethod.GET, "/api/v1/notes/1"},
        new Object[] {HttpMethod.GET, "/api/v1/notes/1/files/1"},
        new Object[] {HttpMethod.POST, "/api/v1/notes/upload-url"},
        new Object[] {HttpMethod.POST, "/api/v1/notes"},
        new Object[] {HttpMethod.PATCH, "/api/v1/notes/1"},
        new Object[] {HttpMethod.DELETE, "/api/v1/notes/1"},
        new Object[] {HttpMethod.POST, "/api/v1/notes/1/bookmark"},
        new Object[] {HttpMethod.DELETE, "/api/v1/notes/1/bookmark"},
        new Object[] {HttpMethod.GET, "/api/v1/bookmarks"});
  }

  /** 같은 경로가 {@code ACTIVE}에게는 열려 있다 — 위가 "전부 막는다"로 고쳐지면 여기가 깨진다. */
  @Test
  void theSamePathIsOpenForActiveMembers() throws Exception {
    mockMvc.perform(sessions.signIn(active).on(get("/api/v1/notes"))).andExpect(status().isOk());
  }

  /** 코드가 {@code SUSPENDED}·{@code FORBIDDEN}과 갈린다 — 화면이 가르는 근거가 코드뿐이다. */
  @Test
  void theCodeIsDistinctFromSuspendedAndForbidden() throws Exception {
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250003"));

    mockMvc
        .perform(sessions.signIn(suspended).on(get("/api/v1/notes")))
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
    mockMvc
        .perform(sessions.signIn(inactive).on(get("/api/v1/admin/users")))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /* ------------------------------------------------------ 관리자 경로의 INACTIVE */

  /** T-346. 상태 변경 본문에 {@code INACTIVE}를 실으면 역직렬화 단계에서 끊긴다. */
  @Test
  void theStatusPathDoesNotAcceptInactive() throws Exception {
    User admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));

    mockMvc
        .perform(
            Csrf.with(
                sessions
                    .signIn(admin)
                    .on(
                        patch("/api/v1/admin/users/" + active.getId() + "/status")
                            .contentType("application/json")
                            .content("{\"status\":\"INACTIVE\"}"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** T-347. 비활동 부원도 <b>곧바로</b> 정지할 수 있다 — 안전 조치가 두 단계가 되면 안 된다. */
  @Test
  void anInactiveMemberCanBeSuspendedDirectly() throws Exception {
    User admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));

    mockMvc
        .perform(
            Csrf.with(
                sessions
                    .signIn(admin)
                    .on(
                        patch("/api/v1/admin/users/" + inactive.getId() + "/status")
                            .contentType("application/json")
                            .content("{\"status\":\"SUSPENDED\"}"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
  }

  /**
   * T-348. 그 뒤 해제하면 <b>{@code ACTIVE}로 돌아온다.</b>
   *
   * <p>이상해 보이는 것이 맞고 <b>의도된 손실이다</b> — 이전 상태를 기억하는 열을 두지 않기로 했다. 사례로 못 박아 두지 않으면 나중에 "버그"로 고쳐진다.
   */
  @Test
  void liftingThatSuspensionLandsOnActiveNotInactive() throws Exception {
    User admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));
    String path = "/api/v1/admin/users/" + inactive.getId() + "/status";

    mockMvc.perform(
        Csrf.with(
            sessions
                .signIn(admin)
                .on(
                    patch(path)
                        .contentType("application/json")
                        .content("{\"status\":\"SUSPENDED\"}"))));
    mockMvc
        .perform(
            Csrf.with(
                sessions
                    .signIn(admin)
                    .on(
                        patch(path)
                            .contentType("application/json")
                            .content("{\"status\":\"ACTIVE\"}"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  /** T-361. 비활동 대상을 이 경로로 되살리지 않는다 — 복구 경로가 하나여야 한다. */
  @Test
  void theStatusPathCannotRestoreAnInactiveMember() throws Exception {
    User admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));

    mockMvc
        .perform(
            Csrf.with(
                sessions
                    .signIn(admin)
                    .on(
                        patch("/api/v1/admin/users/" + inactive.getId() + "/status")
                            .contentType("application/json")
                            .content("{\"status\":\"ACTIVE\"}"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(userRepository.findById(inactive.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.INACTIVE);
  }

  /** T-349. 비활동 부원을 관리자로 만들지 않는다 — <b>자료를 못 보는 관리자</b>가 생긴다. */
  @Test
  void anInactiveMemberCannotBePromoted() throws Exception {
    User admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));

    mockMvc
        .perform(
            Csrf.with(
                sessions
                    .signIn(admin)
                    .on(
                        patch("/api/v1/admin/users/" + inactive.getId() + "/role")
                            .contentType("application/json")
                            .content("{\"role\":\"ADMIN\"}"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** T-352. 목록을 상태로 추릴 수 있다 — 복구할 사람을 고를 근거다. */
  @Test
  void theMemberListCanBeFilteredByInactive() throws Exception {
    User admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));

    mockMvc
        .perform(sessions.signIn(admin).on(get("/api/v1/admin/users?status=INACTIVE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(inactive.getId()));
  }

  /** 비활동 부원이 탈퇴하는 것은 막지 않는다 — 나가는 문을 자료와 함께 잠그면 안 된다 (#225). */
  @Test
  void anInactiveMemberCanStillWithdraw() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.signIn(inactive).on(delete("/api/v1/auth/me"))))
        .andExpect(status().isNoContent());

    assertThat(userRepository.existsById(inactive.getId())).isFalse();
  }
}
