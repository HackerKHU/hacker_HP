package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자가 바꾼 값이 <b>그 사람의 기존 세션에</b> 반영되는지 (spec 3-1 §3-1-5 MUST, T-32~T-34).
 *
 * <p><b>실제 세션 저장소로 확인한다.</b> {@code MockHttpSession}으로는 이 이슈를 검증할 수 없다 — 저장소에 없는 세션은 애초에 찾을 수 없고,
 * {@code PRINCIPAL_NAME} 색인이 실제로 채워지는지도 드러나지 않는다.
 *
 * <p>DB만 바꾸고 세션을 그대로 두는 구현을 잡아내는 것이 이 사례들의 목적이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionSynchronizationIntegrationTest extends AbstractIntegrationTest {

  private static final String ME = "/api/v1/auth/me";
  private static final String DOCS = "/v3/api-docs";
  private static final String ADMIN_USERS = "/api/v1/admin/users";
  private static final String APPROVE = ADMIN_USERS + "/approve";
  private static final String SESSION_COOKIE = "SESSION";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private SessionRepository<? extends Session> sessionRepository;
  @Autowired private FindByIndexNameSessionRepository<? extends Session> indexedSessions;
  @Autowired private DefaultCookieSerializer cookieSerializer;
  @Autowired private SessionSynchronizer sessionSynchronizer;
  @Autowired private TransactionTemplate transactionTemplate;

  private User admin;
  private User member;
  private User applicant;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();

    User toPromote = approved("sub-admin", "admin@khu.ac.kr", "20200001", "관리자");
    toPromote.promoteToAdmin();
    admin = userRepository.saveAndFlush(toPromote);

    member = userRepository.saveAndFlush(approved("sub-user", "user@khu.ac.kr", "20240101", "회원"));

    User pending = User.createFromGoogle("sub-pending", "pending@khu.ac.kr", "구글이름");
    pending.submitApplication("20240102", "신청자");
    applicant = userRepository.saveAndFlush(pending);
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  private static User approved(String googleSub, String email, String studentNo, String name) {
    User user = User.createFromGoogle(googleSub, email, "구글이름");
    user.submitApplication(studentNo, name);
    user.approve();
    return user;
  }

  /** 로그인한 것과 같은 상태의 세션을 저장소에 만들고, 브라우저가 받았을 쿠키를 돌려준다. */
  private SignedIn signIn(User user) {
    Session session = sessionRepository.createSession();
    AuthSession.store(session, user);
    save(session);

    MockHttpServletResponse carrier = new MockHttpServletResponse();
    cookieSerializer.writeCookieValue(
        new CookieSerializer.CookieValue(new MockHttpServletRequest(), carrier, session.getId()));
    return new SignedIn(
        session.getId(),
        carrier.getCookie(SESSION_COOKIE),
        new Cookie("ACCESS_TOKEN", jwtProvider.issue(user.getId())));
  }

  private record SignedIn(String id, Cookie session, Cookie token) {
    Cookie[] cookies() {
      return new Cookie[] {session, token};
    }
  }

  @SuppressWarnings("unchecked")
  private void save(Session session) {
    ((SessionRepository<Session>) sessionRepository).save(session);
  }

  private MockHttpServletRequestBuilder as(SignedIn signedIn, MockHttpServletRequestBuilder call) {
    return call.cookie(signedIn.cookies());
  }

  /* ------------------------------------------------------------------ 색인 */

  /**
   * 색인이 실제로 채워지는지.
   *
   * <p>이것이 없으면 아래 사례가 전부 <b>조용히</b> 무의미해진다 — 찾지 못한 세션은 갱신되지 않고, 어디에서도 오류가 나지 않는다.
   */
  @Test
  void sessionIsIndexedByUserId() {
    SignedIn signedIn = signIn(member);

    assertThat(indexedSessions.findByPrincipalName(String.valueOf(member.getId())))
        .containsKey(signedIn.id());
  }

  /* ------------------------------------------------------------------ T-32 */

  /**
   * T-32 — 이용 중 정지된 세션의 다음 요청.
   *
   * <p><b>{@code 401}이 아니라 {@code 403 SUSPENDED}여야 한다.</b> 세션을 지우면 클라이언트가 정지인지 단순 만료인지 구별하지 못하고, 정지
   * 직후 그 화면에서 안내를 띄울 수 없다 (3-1 §3-1-5 MUST).
   */
  @Test
  void suspensionReachesTheLiveSession() throws Exception {
    SignedIn signedIn = signIn(member);
    mockMvc.perform(as(signedIn, get(DOCS))).andExpect(status().isOk());

    suspend(member);

    mockMvc
        .perform(as(signedIn, get(DOCS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /**
   * 한 사람의 세션이 여럿일 수 있다 (PC·휴대폰).
   *
   * <p>하나라도 남기면 <b>정지된 사람이 그 브라우저로 계속 쓴다.</b>
   */
  @Test
  void everySessionOfThatPersonIsRefreshed() throws Exception {
    SignedIn onDesktop = signIn(member);
    SignedIn onPhone = signIn(member);

    suspend(member);

    for (SignedIn signedIn : List.of(onDesktop, onPhone)) {
      mockMvc
          .perform(as(signedIn, get(DOCS)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("SUSPENDED"));
    }
  }

  /* ------------------------------------------------------------------ T-33 */

  /**
   * T-33 — 로그인 중인 승인 대기 회원을 관리자가 일괄 승인한다.
   *
   * <p>여기서만 <b>실제 API</b>로 확인한다. 승인은 {@code POST /admin/users/approve}가 있고(#30), 정지·권한 회수는 아직
   * 없다(#31·#58).
   */
  @Test
  void approvalReachesTheLiveSession() throws Exception {
    SignedIn waiting = signIn(applicant);
    mockMvc
        .perform(as(waiting, get(DOCS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));

    SignedIn adminSession = signIn(admin);
    mockMvc
        .perform(
            Csrf.with(as(adminSession, post(APPROVE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[" + applicant.getId() + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved.length()").value(1));

    // 재로그인 없이 그대로 이용할 수 있어야 한다 (3-1 §3-1-4).
    mockMvc.perform(as(waiting, get(DOCS))).andExpect(status().isOk());
    mockMvc
        .perform(as(waiting, get(ME)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  /* ------------------------------------------------------------------ T-34 */

  /** T-34 — 권한을 회수하면 그 사람의 다음 관리자 API 요청이 막힌다. 대상 API는 #58이고 여기서는 공통 경로를 직접 부른다. */
  @Test
  void roleChangeReachesTheLiveSession() throws Exception {
    SignedIn adminSession = signIn(admin);
    mockMvc.perform(as(adminSession, get(ADMIN_USERS))).andExpect(status().isOk());

    transactionTemplate.executeWithoutResult(
        ignored -> {
          User target = userRepository.findById(admin.getId()).orElseThrow();
          target.demoteToUser();
          sessionSynchronizer.refreshAfterCommit(List.of(target));
        });

    mockMvc
        .perform(as(adminSession, get(ADMIN_USERS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /* ------------------------------------------------------------- 커밋 경계 */

  /**
   * 되돌아간 변경은 세션에도 반영되지 않는다.
   *
   * <p>세션 저장소는 자기 트랜잭션으로 커밋한다. 커밋을 기다리지 않으면 <b>롤백된 정지 때문에 멀쩡한 회원이 막힌다</b> — 그 사람은 이유도 모른 채 세션 만료까지
   * 기다려야 한다.
   */
  @Test
  void rolledBackChangeDoesNotReachTheSession() throws Exception {
    SignedIn signedIn = signIn(member);

    transactionTemplate.executeWithoutResult(
        status -> {
          User target = userRepository.findById(member.getId()).orElseThrow();
          target.suspend();
          sessionSynchronizer.refreshAfterCommit(List.of(target));
          status.setRollbackOnly();
        });

    mockMvc.perform(as(signedIn, get(DOCS))).andExpect(status().isOk());
  }

  private void suspend(User user) {
    transactionTemplate.executeWithoutResult(
        ignored -> {
          User target = userRepository.findById(user.getId()).orElseThrow();
          target.suspend();
          sessionSynchronizer.refreshAfterCommit(List.of(target));
        });
  }
}
