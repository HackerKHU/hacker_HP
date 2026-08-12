package org.hackerkhu.hackerhp.domain.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.AuthSession;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.session.InMemorySessionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET /admin/users} (spec 2-2 §2-2-1, 3-2 §3-2-6).
 *
 * <p><b>여기서 T-05가 처음으로 실제 확인된다.</b> "USER로 {@code GET /admin/users} → {@code 403 FORBIDDEN}"은 계약이
 * 정한 지 오래됐지만 대상 API가 없어 미뤄져 있었다 (#28 PR에 그렇게 적어 뒀다).
 *
 * <p>정렬과 필터를 특히 조밀하게 본다. <b>이 화면의 결과는 곧 승인·정지의 대상 목록이 된다</b> — 순서가 어긋나거나 신청하지 않은 계정이 승인 대기로 섞이면
 * 관리자가 잘못된 사람을 처리한다.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration")
@AutoConfigureMockMvc
@Import(InMemorySessionConfig.class)
class AdminUserListIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/admin/users";

  private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User admin;
  private User alice;
  private User dave;

  /**
   * 다섯 명. 상태·권한·신청 여부가 겹치지 않게 흩어 놓는다.
   *
   * <p>이름은 코드포인트 순서가 곧 사전 순서인 한글 음절로 골랐다 — 컨테이너의 collation이 무엇이든 정렬 결과가 같다.
   *
   * <pre>
   * 관리자   ADMIN     ACTIVE     신청 4일 전
   * 김가나   USER      ACTIVE     신청 3일 전
   * 박다라   USER      PENDING    신청 1일 전   ← 승인 대상
   * 이마바   USER      PENDING    신청하지 않음 ← 승인 대상이 아니다
   * 최사아   USER      SUSPENDED  신청 2일 전
   * </pre>
   */
  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();

    User toPromote = approved("sub-admin", "admin@khu.ac.kr", "20200001", "관리자");
    // 저장 뒤에 승격시키면 안 된다. 다시 저장할 때 메모리에 남은 옛 applied_at이
    // 아래에서 못 박은 값을 덮어써, 정렬 사례가 조용히 무의미해진다.
    toPromote.promoteToAdmin();
    admin = save(toPromote, 4);

    alice = save(approved("sub-alice", "alice@khu.ac.kr", "20240101", "김가나"), 3);

    User bob = User.createFromGoogle("sub-bob", "BOB@khu.ac.kr", "구글이름");
    bob.submitApplication("20240102", "박다라");
    save(bob, 1);

    // 구글 로그인만 해보고 신청서를 내지 않았다. 학번이 없다.
    userRepository.saveAndFlush(User.createFromGoogle("sub-carol", "carol@khu.ac.kr", "이마바"));

    User suspended = approved("sub-dave", "dave@khu.ac.kr", "20240104", "최사아");
    suspended.suspend();
    dave = save(suspended, 2);
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

  /**
   * 저장 후 신청 시각을 못 박는다.
   *
   * <p>{@code submitApplication}이 넣는 값은 {@code Instant.now()}라 다섯 건의 간격이 마이크로초다. 그 상태로 정렬을 확인하면
   * <b>통과 여부가 실행 속도에 달린다.</b>
   */
  private User save(User user, int daysAgo) {
    User saved = userRepository.saveAndFlush(user);
    jdbcTemplate.update(
        "update users set applied_at = ? where id = ?",
        java.sql.Timestamp.from(NOW.minus(daysAgo, ChronoUnit.DAYS)),
        saved.getId());
    return saved;
  }

  private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder) {
    MockHttpSession session = new MockHttpSession();
    AuthSession.store(session, user);
    return builder
        .session(session)
        .cookie(new Cookie("ACCESS_TOKEN", jwtProvider.issue(user.getId())));
  }

  private MockHttpServletRequestBuilder query(String query) {
    return as(admin, get(PATH + query));
  }

  /**
   * 검색어는 URL에 이어 붙이지 않고 파라미터로 싣는다.
   *
   * <p>{@code get(...)}에 넘기는 문자열은 URI 템플릿이라 <b>{@code %}가 한 번 더 인코딩된다.</b> 공백({@code %20})이나
   * 와일드카드({@code %25})를 그렇게 보내면 서버는 {@code "%20"}이라는 글자를 받고, 검사가 의도와 다른 것을 확인하게 된다.
   */
  private MockHttpServletRequestBuilder search(String keyword) {
    return as(admin, get(PATH).param("q", keyword));
  }

  /* ---------------------------------------------------------------- 권한 */

  /** T-05. 계약이 정한 지 오래된 사례인데 대상 API가 이제야 생겼다. */
  @Test
  void memberIsForbidden() throws Exception {
    mockMvc
        .perform(as(alice, get(PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void anonymousIsUnauthenticated() throws Exception {
    mockMvc
        .perform(get(PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  /** 정지된 관리자는 권한이 아니라 상태 때문에 막힌다 — 코드가 다르면 화면이 정지 안내를 띄우지 못한다. */
  @Test
  void suspendedIsBlockedByStatus() throws Exception {
    mockMvc
        .perform(as(dave, get(PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /* ---------------------------------------------------------------- 응답 */

  /** 표시 항목은 2-2 §2-2-1이 원본이다 — 이름, 학번, 이메일, Role, Status, 가입 신청일, 승인일. */
  @Test
  void rowCarriesEveryColumnTheScreenShows() throws Exception {
    mockMvc
        .perform(search("김가나"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(alice.getId()))
        .andExpect(jsonPath("$.content[0].name").value("김가나"))
        .andExpect(jsonPath("$.content[0].studentNo").value("20240101"))
        .andExpect(jsonPath("$.content[0].email").value("alice@khu.ac.kr"))
        .andExpect(jsonPath("$.content[0].role").value("USER"))
        .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.content[0].appliedAt").exists())
        .andExpect(jsonPath("$.content[0].approvedAt").exists())
        .andExpect(jsonPath("$.content[0].createdAt").exists());
  }

  /**
   * 엔티티를 그대로 내보내지 않는다는 규칙이 실제로 지켜지는지.
   *
   * <p>이슈의 완료 조건은 "비밀번호 해시가 응답에 없다"인데 구글 OAuth뿐이라 비밀번호 컬럼 자체가 없다. <b>같은 취지로 새면 안 되는 것은 {@code
   * google_sub}와 {@code version}이다</b> — 하나는 구글 계정 식별자이고 하나는 동시성 제어용 컬럼이다.
   */
  @Test
  void internalColumnsAreNotExposed() throws Exception {
    mockMvc
        .perform(query(""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].googleSub").doesNotExist())
        .andExpect(jsonPath("$.content[0].version").doesNotExist());
  }

  /** 페이지 응답 형태는 계약이 고정한다 (3-2 §3-2-8). `pageable`·`sort` 같은 내부 필드가 새면 안 된다. */
  @Test
  void pageEnvelopeFollowsTheContract() throws Exception {
    mockMvc
        .perform(query("?page=1&size=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.page.size").value(2))
        .andExpect(jsonPath("$.page.number").value(1))
        .andExpect(jsonPath("$.page.totalElements").value(5))
        .andExpect(jsonPath("$.page.totalPages").value(3))
        .andExpect(jsonPath("$.pageable").doesNotExist())
        .andExpect(jsonPath("$.sort").doesNotExist());
  }

  /** 상한이 없으면 관리자 한 명이 전 회원을 한 번에 긁는다. 넘는 값은 잘린다. */
  @Test
  void pageSizeIsCapped() throws Exception {
    mockMvc
        .perform(query("?size=500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.size").value(100));
  }

  /* ---------------------------------------------------------------- 정렬 */

  /**
   * 기본은 신청일 최신순이고 <b>신청하지 않은 계정이 맨 뒤로 간다.</b>
   *
   * <p>{@code NULLS LAST}를 빠뜨리면 PostgreSQL이 널을 앞에 올려, 관리자가 첫 화면에서 보는 것이 승인 대상이 아닌 사람들이 된다.
   */
  @Test
  void defaultOrderIsLatestApplicationFirstWithNonApplicantsLast() throws Exception {
    mockMvc
        .perform(query(""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("박다라"))
        .andExpect(jsonPath("$.content[1].name").value("최사아"))
        .andExpect(jsonPath("$.content[2].name").value("김가나"))
        .andExpect(jsonPath("$.content[3].name").value("관리자"))
        .andExpect(jsonPath("$.content[4].name").value("이마바"));
  }

  /** 화면이 보내는 값 그대로다 ({@code apps/web/src/api/adminUsers.ts}). */
  @Test
  void sortsByName() throws Exception {
    mockMvc
        .perform(query("?sort=name"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("관리자"))
        .andExpect(jsonPath("$.content[4].name").value("최사아"));
  }

  /** 학번도 신청 전에는 비어 있다. 널이 앞에 서면 빈 칸부터 보인다. */
  @Test
  void sortsByStudentNoWithEmptyOnesLast() throws Exception {
    mockMvc
        .perform(query("?sort=studentNo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].studentNo").value("20200001"))
        .andExpect(jsonPath("$.content[4].studentNo").doesNotExist());
  }

  /**
   * 정렬할 수 없는 필드는 <b>무시하지 않고 거절한다.</b>
   *
   * <p>조용히 버리면 관리자는 정렬이 적용된 줄 알고 그 순서를 신뢰한다. 잘못된 순서의 명단으로 승인·정지를 누르는 것이 400을 받는 것보다 나쁘다.
   */
  @Test
  void unknownSortFieldIsRejected() throws Exception {
    mockMvc
        .perform(query("?sort=googleSub"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /* ---------------------------------------------------------------- 필터 */

  @Test
  void filtersByStatus() throws Exception {
    mockMvc
        .perform(query("?status=PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(2));
  }

  @Test
  void filtersByRole() throws Exception {
    mockMvc
        .perform(query("?role=ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("관리자"));
  }

  /**
   * <b>이 조합이 승인 대기 목록이다</b> (3-2 §3-2-6 — {@code status = PENDING AND applied_at IS NOT NULL}).
   *
   * <p>{@code applied}가 없으면 관리자는 "승인 대기만 보여줘"를 할 수 없다. 화면에서 거르는 것으로는 안 된다 — 서버가 준 20건 중 일부를 버리면 총
   * 건수와 총 페이지 수가 실제와 어긋난다.
   */
  @Test
  void separatesApplicantsFromAccountsThatOnlySignedIn() throws Exception {
    mockMvc
        .perform(query("?status=PENDING&applied=true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("박다라"));

    mockMvc
        .perform(query("?status=PENDING&applied=false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("이마바"));
  }

  /* ---------------------------------------------------------------- 검색 */

  @Test
  void searchesByStudentNo() throws Exception {
    mockMvc
        .perform(search("20240102"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("박다라"));
  }

  /** 이메일은 대소문자를 가리지 않는다. 관리자가 주소를 그대로 옮겨 붙이는 화면이다. */
  @Test
  void searchesByEmailIgnoringCase() throws Exception {
    mockMvc
        .perform(search("bob@KHU.ac.kr"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("박다라"));
  }

  /** 부분 일치다. 이름의 일부만 기억나는 것이 검색을 쓰는 이유다. */
  @Test
  void searchesByPartOfTheName() throws Exception {
    mockMvc
        .perform(search("가나"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("김가나"));
  }

  /**
   * {@code LIKE}의 특수 문자를 글자 그대로 찾는다.
   *
   * <p>escape하지 않으면 {@code _}가 "아무 글자 하나"로 해석되어 <b>전원이 결과에 나온다.</b> 관리자는 그 목록을 검색 결과로 믿고 승인·정지를
   * 누른다.
   */
  @Test
  void wildcardsInTheKeywordAreLiteral() throws Exception {
    mockMvc
        .perform(search("_"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(0));

    mockMvc
        .perform(search("%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  /** 공백뿐인 검색어는 없는 것으로 본다. 화면이 입력을 비우는 중에도 전체 목록이 그대로 보여야 한다. */
  @Test
  void blankKeywordDoesNotFilter() throws Exception {
    mockMvc
        .perform(search("   "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(5));
  }
}
