package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.web.servlet.MockMvc;
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

    User toPromote = Accounts.approved("sub-admin", "admin@khu.ac.kr", "20200001", "관리자");
    toPromote.promoteToAdmin();
    admin = userRepository.saveAndFlush(toPromote);

    member =
        userRepository.saveAndFlush(
            Accounts.approved("sub-user", "user@khu.ac.kr", "20240101", "회원"));

    User pending = User.createFromGoogle("sub-pending", "pending@khu.ac.kr", "구글이름");
    pending.submitApplication("20240102", "신청자");
    applicant = userRepository.saveAndFlush(pending);
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /** 세션 저장소를 직접 다뤄야 하는 사례가 있다 — 갱신이 실패한 상황을 만들 때다. */
  @SuppressWarnings("unchecked")
  private void save(Session session) {
    ((SessionRepository<Session>) sessionRepository).save(session);
  }

  /* ------------------------------------------------------------------ 색인 */

  /**
   * 색인이 실제로 채워지는지.
   *
   * <p>이것이 없으면 아래 사례가 전부 <b>조용히</b> 무의미해진다 — 찾지 못한 세션은 갱신되지 않고, 어디에서도 오류가 나지 않는다.
   */
  @Test
  void sessionIsIndexedByUserId() {
    SignedIn signedIn = sessions.signIn(member);

    assertThat(indexedSessions.findByPrincipalName(String.valueOf(member.getId())))
        .containsKey(signedIn.id());
  }

  /* ------------------------------------------------------------------ T-32 */

  /**
   * T-32 — 이용 중 정지된 세션의 다음 요청.
   *
   * <p><b>{@code 401}이 아니라 {@code 403 SUSPENDED}여야 한다.</b> 세션을 지우면 클라이언트가 정지인지 단순 만료인지 구별하지 못하고, 정지
   * 직후 그 화면에서 안내를 띄울 수 없다 (3-1 §3-1-5 MUST).
   *
   * <p>실제 API로 정지한다 (#31). 관리자가 화면에서 누르는 것과 같은 경로다.
   */
  @Test
  void suspensionReachesTheLiveSession() throws Exception {
    SignedIn signedIn = sessions.signIn(member);
    mockMvc.perform(signedIn.on(get(DOCS))).andExpect(status().isOk());

    suspendThroughTheApi(member);

    mockMvc
        .perform(signedIn.on(get(DOCS)))
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
    SignedIn onDesktop = sessions.signIn(member);
    SignedIn onPhone = sessions.signIn(member);

    suspendThroughTheApi(member);

    for (SignedIn signedIn : List.of(onDesktop, onPhone)) {
      mockMvc
          .perform(signedIn.on(get(DOCS)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("SUSPENDED"));
    }
  }

  /**
   * <b>같은 요청을 다시 보내는 것이 복구 수단이어야 한다.</b>
   *
   * <p>세션 갱신 실패는 예외로 올리지 않고 기록만 한다 — 이미 커밋된 변경까지 실패한 것처럼 보이면 안 되기 때문이다. 그래서 관리자가 할 수 있는 일은 다시 누르는
   * 것뿐인데, 대상이 이미 그 상태라고 일찍 돌아가 버리면 <b>그 재시도가 아무 일도 하지 않는다.</b>
   *
   * <p>여기서는 갱신이 실패한 상황을 만든다 — DB는 {@code SUSPENDED}인데 세션만 {@code ACTIVE}로 되돌려 놓고, 같은 정지를 다시 보낸다.
   */
  @Test
  void repeatingTheSameChangeReSyncsAStaleSession() throws Exception {
    SignedIn signedIn = sessions.signIn(member);
    suspendThroughTheApi(member);

    // 갱신이 실패해 세션만 옛 값으로 남은 상태를 만든다.
    Session stale = sessionRepository.findById(signedIn.id());
    stale.setAttribute(AuthSession.STATUS, Status.ACTIVE);
    save(stale);
    mockMvc.perform(signedIn.on(get(DOCS))).andExpect(status().isOk());

    // 관리자가 같은 정지를 다시 누른다.
    suspendThroughTheApi(member);

    mockMvc
        .perform(signedIn.on(get(DOCS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /**
   * <b>늦게 도착한 옛 값이 새 값을 덮지 않는다.</b>
   *
   * <p>행 잠금은 DB 변경만 직렬화한다 — 커밋과 함께 잠금이 풀리고 세션 저장은 그 뒤에 각자 일어나므로 순서가 뒤집힐 수 있다. 해제가 먼저 커밋된 뒤 세션 저장이
   * 늦어지고 그 사이 정지가 커밋·저장까지 마치면, 뒤늦게 도착한 해제가 세션을 {@code ACTIVE}로 되돌린다 — <b>정지된 사람이 계속 이용하게 된다.</b>
   *
   * <p>타이밍을 재현하는 대신 <b>규칙</b>을 확인한다 — 세션이 더 새 버전을 들고 있으면 낮은 버전의 갱신은 아무 일도 하지 않아야 한다.
   */
  @Test
  void aLateArrivingOldValueDoesNotOverwriteTheNewOne() throws Exception {
    SignedIn signedIn = sessions.signIn(member);

    // 더 새 변경이 이미 세션까지 반영된 상태를 만든다.
    Session ahead = sessionRepository.findById(signedIn.id());
    ahead.setAttribute(AuthSession.STATUS, Status.SUSPENDED);
    ahead.setAttribute(AuthSession.VERSION, Long.MAX_VALUE);
    save(ahead);

    // 그 뒤에 갱신이 도착한다 — DB의 회원은 아직 ACTIVE이고 버전이 낮다.
    sessionSynchronizer.refresh(List.of(member.getId()));

    mockMvc
        .perform(signedIn.on(get(DOCS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /**
   * 앞뒤 콜백이 <b>동시에</b> 도착해도 새 값이 남는다.
   *
   * <p>계정을 읽는 것부터 세션에 쓰는 것까지를 그 사람의 계정 행으로 한 줄로 세운다. 값을 미리 읽어 들고 오는 방식이면 <b>둘이 같은 옛 값을 함께 들고 와</b>
   * 나중에 저장하는 쪽이 이긴다.
   *
   * <p><b>이 사례가 잠금이 빠진 구현을 언제나 잡아내지는 않는다</b> — 두 작업이 실제로 겹치지 않으면 그대로 통과한다. 타이밍에 기대지 않는 확인은 T-173이
   * 한다.
   */
  @Test
  void concurrentRefreshesLeaveTheNewerValue() throws Exception {
    SignedIn signedIn = sessions.signIn(member);

    transactionTemplate.executeWithoutResult(
        ignored -> userRepository.findById(member.getId()).orElseThrow().suspend());

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      pool.invokeAll(List.of(push(member.getId(), ready), push(member.getId(), ready)));
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    mockMvc
        .perform(signedIn.on(get(DOCS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  private Callable<Boolean> push(Long userId, CyclicBarrier ready) {
    return () -> {
      ready.await(10, TimeUnit.SECONDS);
      sessionSynchronizer.refresh(List.of(userId));
      return true;
    };
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
    SignedIn waiting = sessions.signIn(applicant);
    mockMvc
        .perform(waiting.on(get(DOCS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));

    SignedIn adminSession = sessions.signIn(admin);
    mockMvc
        .perform(
            Csrf.with(adminSession.on(post(APPROVE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[" + applicant.getId() + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved.length()").value(1));

    // 재로그인 없이 그대로 이용할 수 있어야 한다 (3-1 §3-1-4).
    mockMvc.perform(waiting.on(get(DOCS))).andExpect(status().isOk());
    mockMvc
        .perform(waiting.on(get(ME)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  /* ------------------------------------------------------------------ T-34 */

  /** T-34 — 권한을 회수하면 그 사람의 다음 관리자 API 요청이 막힌다. 대상 API는 #58이고 여기서는 공통 경로를 직접 부른다. */
  @Test
  void roleChangeReachesTheLiveSession() throws Exception {
    SignedIn adminSession = sessions.signIn(admin);
    mockMvc.perform(adminSession.on(get(ADMIN_USERS))).andExpect(status().isOk());

    transactionTemplate.executeWithoutResult(
        ignored -> userRepository.findById(admin.getId()).orElseThrow().demoteToUser());
    sessionSynchronizer.refresh(List.of(admin.getId()));

    mockMvc
        .perform(adminSession.on(get(ADMIN_USERS)))
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
    SignedIn signedIn = sessions.signIn(member);

    transactionTemplate.executeWithoutResult(
        status -> {
          userRepository.findById(member.getId()).orElseThrow().suspend();
          status.setRollbackOnly();
        });
    // 커밋되지 않았으므로 갱신을 부르지 않는다. 불러도 잠근 채 읽은 값은 여전히 ACTIVE다.
    sessionSynchronizer.refresh(List.of(member.getId()));

    mockMvc.perform(signedIn.on(get(DOCS))).andExpect(status().isOk());
  }

  /** 관리자가 화면에서 정지를 누르는 것과 같은 경로 (#31). */
  private void suspendThroughTheApi(User user) throws Exception {
    SignedIn adminSession = sessions.signIn(admin);
    mockMvc
        .perform(
            Csrf.with(adminSession.on(patch(ADMIN_USERS + "/" + user.getId() + "/status")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUSPENDED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
  }
}
