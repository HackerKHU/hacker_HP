package org.hackerkhu.hackerhp.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 회원 탈퇴 — {@code DELETE /auth/me} (spec 2-2 §2-2-4 "본인 탈퇴", T-370 ~ T-386·T-392·T-396, #223 #225).
 *
 * <p><b>처리는 관리자의 회원 제거와 같다.</b> 그래서 여기서 다시 재는 것은 <b>문이 다르다는 데서 오는 차이</b>뿐이다 — 누가 통과하고 누가 막히는가, 이력이
 * 어느 값으로 남는가, 세션과 쿠키가 함께 끝나는가. 남기는 것과 지우는 것 자체는 T-187 ~ T-197이 본다.
 *
 * <p><b>필터를 태운다</b> (MUST). {@code SUSPENDED}·{@code PENDING} 판단이 인가보다 앞에 있는 필터의 몫이라, 서비스만 부르면 이
 * 사례들이 재는 것이 사라진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthWithdrawalIntegrationTest extends AbstractIntegrationTest {

  private static final String ME = "/api/v1/auth/me";
  private static final String SUMMARY = ME + "/content-summary";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clearAll();
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    actions.deleteAll();
    userRepository.deleteAll();
  }

  private User save(User user) {
    return userRepository.saveAndFlush(user);
  }

  private MvcResult withdraw(SignedIn signedIn) throws Exception {
    return mockMvc.perform(Csrf.with(signedIn.on(delete(ME)))).andReturn();
  }

  private static boolean expired(MvcResult result, String name) {
    Cookie cookie = result.getResponse().getCookie(name);
    return cookie != null && cookie.getMaxAge() == 0;
  }

  private List<AdminActionLog> historyOf(Long userId) {
    return actions.findAll().stream().filter(a -> a.getTargetId().equals(userId)).toList();
  }

  /* ------------------------------------------------------------ 지워지는 것 */

  /** T-370·T-371. 계정은 사라지고 <b>그 사람이 올린 것은 남는다.</b> 즐겨찾기만 함께 지워진다. */
  @Test
  void withdrawalKeepsTheContentAndDropsOnlyTheBookmarks() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    User other = save(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));
    Long noteId = insertNote(member.getId(), "남을 자료");
    insertBookmark(member.getId(), noteId);
    insertBookmark(other.getId(), noteId);

    mockMvc
        .perform(Csrf.with(sessions.signIn(member).on(delete(ME))))
        .andExpect(status().isNoContent());

    assertThat(userRepository.existsById(member.getId())).isFalse();
    assertThat(uploaderOf(noteId)).as("자료는 남고 업로더만 빈다").isNull();
    assertThat(bookmarkCount(noteId)).as("본인 즐겨찾기만 사라진다").isEqualTo(1);
  }

  /** T-372. 응답이 <b>지금 요청의 세션과 토큰 쿠키를 함께 버린다.</b> */
  @Test
  void withdrawalEndsTheCurrentSessionAndToken() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    SignedIn signedIn = sessions.signIn(member);

    MvcResult result = withdraw(signedIn);

    assertThat(result.getResponse().getStatus()).isEqualTo(204);
    assertThat(signedIn.storedInRepository()).as("응답을 내보낼 때 되살아나면 안 된다").isFalse();
    assertThat(expired(result, "ACCESS_TOKEN")).as("토큰 쿠키도 버린다").isTrue();
  }

  /** T-373. <b>다른 기기의 세션</b>도 남지 않는다 — 저장소의 세션을 전부 폐기한다. */
  @Test
  void withdrawalDiscardsSessionsOnOtherDevices() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    SignedIn phone = sessions.signIn(member);
    SignedIn laptop = sessions.signIn(member);

    mockMvc.perform(Csrf.with(phone.on(delete(ME)))).andExpect(status().isNoContent());

    assertThat(laptop.storedInRepository()).as("다른 기기 세션도 사라진다").isFalse();
  }

  /* ------------------------------------------------------ 누가 나갈 수 있는가 */

  /** T-374. <b>마지막 활성 관리자는 탈퇴할 수 없다</b> — 계정도 그대로 남는다. */
  @Test
  void theLastActiveAdminCannotWithdraw() throws Exception {
    User admin = save(Accounts.admin("sub-a", "a@khu.ac.kr", "20200001"));
    // 정지된 관리자는 활성으로 세지 않는다. 이 계정이 있어도 admin은 여전히 마지막이다.
    save(Accounts.suspendedAdmin("sub-a2", "a2@khu.ac.kr", "20200002"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(admin).on(delete(ME))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(userRepository.existsById(admin.getId())).as("계정이 남는다").isTrue();
    assertThat(historyOf(admin.getId())).as("아무 조작도 일어나지 않았다").isEmpty();
  }

  /** T-375. 활성 관리자가 둘이면 한 명은 나갈 수 있다. */
  @Test
  void anAdminCanWithdrawWhenAnotherActiveAdminRemains() throws Exception {
    User admin = save(Accounts.admin("sub-a", "a@khu.ac.kr", "20200001"));
    save(Accounts.admin("sub-a2", "a2@khu.ac.kr", "20200002"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(admin).on(delete(ME))))
        .andExpect(status().isNoContent());

    assertThat(userRepository.existsById(admin.getId())).isFalse();
  }

  /**
   * T-376. <b>신청서를 내지 않은 {@code PENDING}</b>도 나갈 수 있다.
   *
   * <p>막으면 그 사람이 자기 계정을 지울 방법이 없다 — 관리자 제거는 되지만 승인 목록({@code status=PENDING&applied=true})에 뜨지 않아
   * 눈에 띄지 않는다.
   *
   * <p><b>정지를 밟지 않는다.</b> {@code PENDING} → {@code SUSPENDED}는 §2-2-3에 없는 전이라, 밟으려 하면 여기서 실패한다.
   */
  @Test
  void aPendingAccountThatNeverAppliedCanWithdraw() throws Exception {
    User pending = save(Accounts.signedIn("sub-p", "p@khu.ac.kr"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(pending).on(delete(ME))))
        .andExpect(status().isNoContent());

    assertThat(userRepository.existsById(pending.getId())).isFalse();
  }

  /** T-377. 신청서까지 낸 {@code PENDING}도 같다. */
  @Test
  void aPendingApplicantCanWithdraw() throws Exception {
    User applicant = save(Accounts.applied("sub-p", "p@khu.ac.kr", "20250001"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(applicant).on(delete(ME))))
        .andExpect(status().isNoContent());

    assertThat(userRepository.existsById(applicant.getId())).isFalse();
  }

  /**
   * T-379. <b>{@code SUSPENDED}는 이 문에 닿지 못한다</b> — 탈퇴를 위한 예외를 뚫지 않는다.
   *
   * <p>열면 정지된 사람이 <b>탈퇴한 뒤 재가입으로 정지를 지운다</b> (T-381이 그 재가입을 보장한다).
   */
  @Test
  void aSuspendedAccountCannotWithdraw() throws Exception {
    User suspended = save(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250001"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(suspended).on(delete(ME))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));

    assertThat(userRepository.existsById(suspended.getId())).isTrue();
  }

  /** T-380. 비로그인은 {@code 401}이다 — "권한이 없다"가 아니라 "로그인하면 되는 상황"이다. */
  @Test
  void anonymousCannotWithdraw() throws Exception {
    mockMvc
        .perform(Csrf.with(delete(ME)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  /**
   * T-381. 탈퇴한 뒤 <b>같은 구글 계정으로 다시 가입할 수 있다.</b>
   *
   * <p>계정 레코드를 지우므로 {@code google_sub}·{@code email} UNIQUE를 붙잡는 행이 남지 않는다. 상태로 남기는 방식이었다면 탈퇴한 사람이
   * 영영 못 돌아온다.
   */
  @Test
  void thesameGoogleAccountCanSignUpAgainAfterWithdrawal() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(member).on(delete(ME))))
        .andExpect(status().isNoContent());

    User again = save(Accounts.signedIn("sub-m", "m@khu.ac.kr"));
    assertThat(again.getId()).isNotEqualTo(member.getId());
  }

  /* ------------------------------------------------------------------ 이력 */

  /**
   * T-382. {@code PENDING}이 나가면 이력은 <b>{@code WITHDRAW} 한 행</b>이다.
   *
   * <p>정지를 밟지 않으므로 {@code SUSPEND}가 없다. {@code actor_id}와 {@code target_id}는 같다.
   */
  @Test
  void aPendingWithdrawalLeavesOnlyTheWithdrawRow() throws Exception {
    User pending = save(Accounts.applied("sub-p", "p@khu.ac.kr", "20250001"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(pending).on(delete(ME))))
        .andExpect(status().isNoContent());

    assertThat(historyOf(pending.getId()))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.getAction()).isEqualTo(AdminAction.WITHDRAW);
              assertThat(a.getActorId()).isEqualTo(pending.getId());
            });
  }

  /**
   * T-383. 관리자가 <b>관리 화면으로</b> 자기를 지우면 {@code REMOVE}다.
   *
   * <p>같은 사람이 같은 계정을 지워도 <b>어느 문으로 들어왔는지가 기록에 남아야</b> 나중에 읽을 수 있다. 이 사례가 없으면 두 경로가 하나로 합쳐지기 쉽고,
   * 합쳐지면 계정이 사라진 뒤에는 가릴 방법이 없다.
   */
  @Test
  void removingYourselfThroughTheAdminDoorIsRecordedAsRemove() throws Exception {
    User admin = save(Accounts.admin("sub-a", "a@khu.ac.kr", "20200001"));
    save(Accounts.admin("sub-a2", "a2@khu.ac.kr", "20200002"));

    mockMvc
        .perform(
            Csrf.with(sessions.signIn(admin).on(delete("/api/v1/admin/users/" + admin.getId()))))
        .andExpect(status().isNoContent());

    assertThat(historyOf(admin.getId()))
        .extracting(AdminActionLog::getAction)
        .contains(AdminAction.REMOVE)
        .doesNotContain(AdminAction.WITHDRAW);
  }

  /* -------------------------------------------------------------- 건수 조회 */

  /** T-384. <b>네 값을 항상 담는다</b> — {@code 0}도 빠지지 않는다. */
  @Test
  void theSummaryAlwaysCarriesAllFourCounts() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));

    mockMvc
        .perform(sessions.signIn(member).on(get(SUMMARY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notes").value(0))
        .andExpect(jsonPath("$.notices").value(0))
        .andExpect(jsonPath("$.photos").value(0))
        .andExpect(jsonPath("$.posts").value(0));
  }

  /** T-385. <b>본인 것만</b> 센다. */
  @Test
  void theSummaryCountsOnlyYourOwnContent() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    User other = save(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));
    insertNote(member.getId(), "내 자료");
    insertNote(other.getId(), "남의 자료");

    mockMvc
        .perform(sessions.signIn(member).on(get(SUMMARY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notes").value(1));
  }

  /**
   * T-386. 일반 부원에게 <b>관리자용 경로를 열지 않는다.</b>
   *
   * <p>그쪽은 {@code {id}}를 받으므로 열면 남의 콘텐츠 건수를 세어 볼 수 있다.
   */
  @Test
  void aMemberCannotReadSomeoneElsesSummaryThroughTheAdminPath() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    User other = save(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));

    mockMvc
        .perform(
            sessions
                .signIn(member)
                .on(get("/api/v1/admin/users/" + other.getId() + "/content-summary")))
        .andExpect(status().isForbidden());
  }

  /**
   * T-392. <b>{@code PENDING}도 건수를 읽을 수 있다.</b>
   *
   * <p>탈퇴만 통과 목록에 넣으면 T-376은 통과하는데, 확인 창이 건수를 먼저 읽으므로 <b>실제 흐름은 탈퇴에 닿기 전에 막힌다.</b> 두 경로가 한 벌이다.
   */
  @Test
  void aPendingAccountCanReadItsOwnSummary() throws Exception {
    User pending = save(Accounts.signedIn("sub-p", "p@khu.ac.kr"));

    mockMvc
        .perform(sessions.signIn(pending).on(get(SUMMARY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notes").value(0))
        .andExpect(jsonPath("$.posts").value(0));
  }

  /** 정지된 계정은 건수도 읽지 못한다 — 탈퇴와 같은 문이다. */
  @Test
  void aSuspendedAccountCannotReadTheSummary() throws Exception {
    User suspended = save(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250001"));

    mockMvc
        .perform(sessions.signIn(suspended).on(get(SUMMARY)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /* ------------------------------------------------------------------ 인가 */

  /**
   * T-396. <b>관리자가 아닌 일반 부원도 탈퇴할 수 있다.</b>
   *
   * <p>선행 정지를 관리자 경로로 밟으면 <b>첫 단계에서 {@code 403}이 된다</b> — 그쪽은 요청자가 활성 관리자인지 먼저 보기 때문이다. *"처리는 회원
   * 제거와 같다"* 를 곧이곧대로 옮기면 정확히 그렇게 구현된다.
   */
  @Test
  void anOrdinaryMemberCanWithdraw() throws Exception {
    User member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    save(Accounts.admin("sub-a", "a@khu.ac.kr", "20200001"));

    mockMvc
        .perform(Csrf.with(sessions.signIn(member).on(delete(ME))))
        .andExpect(status().isNoContent());

    assertThat(userRepository.existsById(member.getId())).isFalse();
    assertThat(historyOf(member.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.SUSPEND, AdminAction.WITHDRAW);
  }

  /* -------------------------------------------------------------- 거들기 */

  private Long insertNote(Long uploaderId, String title) {
    jdbcTemplate.update(
        """
        INSERT INTO notes (category, title, subject_name, professor, year, semester,
                           uploader_id, created_at, updated_at)
        VALUES ('SUBJECT', ?, '과목', '교수', 2026, 'SPRING', ?, now(), now())
        """,
        title,
        uploaderId);
    return jdbcTemplate.queryForObject("SELECT id FROM notes WHERE title = ?", Long.class, title);
  }

  private void insertBookmark(Long userId, Long noteId) {
    jdbcTemplate.update(
        "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (?, ?, now())",
        userId,
        noteId);
  }

  private Long uploaderOf(Long noteId) {
    return jdbcTemplate.queryForObject(
        "SELECT uploader_id FROM notes WHERE id = ?", Long.class, noteId);
  }

  private Integer bookmarkCount(Long noteId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM bookmarks WHERE note_id = ?", Integer.class, noteId);
  }
}
