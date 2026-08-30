package org.hackerkhu.hackerhp.domain.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.hackerkhu.hackerhp.domain.post.repository.PostRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 자유 게시판 (#236·#238·#256, spec 2-1 §2-1-8, 3-2 §3-2-5, 3-3 결정 16·20·21).
 *
 * <p><b>이 저장소에서 일반 부원이 자유 서술을 남기는 첫 기능이다.</b> 지금까지 텍스트를 남기는 길은 공지({@code ADMIN} 전용)와 자료 메타데이터뿐이었다 —
 * 승인된 모든 부원이 쓰는 입력이라 지금까지 없던 표면이 함께 생긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostIntegrationTest extends AbstractIntegrationTest {

  private static final String POSTS = "/api/v1/posts";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository posts;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private User member;
  private User admin;

  @BeforeEach
  void setUp() {
    clearAll();
    member =
        userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001", "김부원"));
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200000"));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM posts");
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  private MockHttpServletRequestBuilder writeRequest(User caller, String title, String content) {
    return Csrf.with(sessions.as(caller, post(POSTS)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}");
  }

  private MockHttpServletRequestBuilder editRequest(
      User caller, long id, String title, String content) {
    return Csrf.with(sessions.as(caller, patch(POSTS + "/" + id)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}");
  }

  private long write(User caller, String title, String content) throws Exception {
    String json =
        mockMvc
            .perform(writeRequest(caller, title, content))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).path("id").asLong();
  }

  /** 같은 시각으로 여러 글을 심는다 — 정렬의 마지막 기준이 실제로 필요해지는 상황이다. */
  private List<Long> writeAtSameInstant(int count) {
    Instant now = Instant.parse("2026-08-23T09:00:00Z");
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      jdbcTemplate.update(
          """
          INSERT INTO posts (title, content, author_id, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?)
          """,
          "같은 시각 " + i,
          "본문",
          member.getId(),
          java.sql.Timestamp.from(now),
          java.sql.Timestamp.from(now));
    }
    jdbcTemplate.queryForList("SELECT id FROM posts ORDER BY id", Long.class).forEach(ids::add);
    return ids;
  }

  /* ------------------------------------------------------------------ 기본 */

  /** T-321 — 쓰고, 목록에서 보고, 상세로 연다. */
  @Test
  void aMemberWritesAndReadsBack() throws Exception {
    long id = write(member, "이번 학기 스터디 모집합니다", "매주 수요일 저녁에 모입니다.");

    mockMvc
        .perform(sessions.as(member, get(POSTS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(id))
        .andExpect(jsonPath("$.content[0].title").value("이번 학기 스터디 모집합니다"))
        .andExpect(jsonPath("$.content[0].author.id").value(member.getId()))
        // 표시 이름이라 학번 끝 두 자리가 붙는다 (#301, 3-2 §3-2-2).
        .andExpect(jsonPath("$.content[0].author.name").value("김부원01"));

    mockMvc
        .perform(sessions.as(member, get(POSTS + "/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("매주 수요일 저녁에 모입니다."))
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
  }

  /** 등록 직후 {@code updatedAt}은 {@code createdAt}과 같다 — 수정이 들어오면(#256) 그때부터 움직인다. */
  @Test
  void updatedAtStartsEqualToCreatedAt() throws Exception {
    long id = write(member, "제목", "본문");

    String json =
        mockMvc
            .perform(sessions.as(member, get(POSTS + "/" + id)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode body = objectMapper.readTree(json);
    assertThat(body.path("updatedAt").asText()).isEqualTo(body.path("createdAt").asText());
  }

  @Test
  void aMissingPostIsNotFound() throws Exception {
    mockMvc
        .perform(sessions.as(member, get(POSTS + "/999999")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /* ------------------------------------------------------------------ 보안 */

  /**
   * T-323 — <b>본문을 건드리지 않는다.</b>
   *
   * <p>서버가 정화하거나 변형하면 규칙이 어디까지인지 아무도 모르게 된다. 이스케이프는 화면이 텍스트 노드로 그리면서 한다 (T-330, #237).
   */
  @Test
  void htmlInTheBodyIsStoredAndReturnedVerbatim() throws Exception {
    String payload = "<script>alert(1)</script><img src=x onerror=alert(2)>";
    long id = write(member, "제목", payload);

    mockMvc
        .perform(sessions.as(member, get(POSTS + "/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value(payload));

    // 저장된 값도 그대로다 — 응답에서만 되돌리는 것이 아니다.
    assertThat(posts.findById(id).orElseThrow().getContent()).isEqualTo(payload);
  }

  /** 제목도 같다. 제목은 목록·상세 양쪽에서 다른 부원에게 보인다. */
  @Test
  void htmlInTheTitleIsStoredVerbatimToo() throws Exception {
    String payload = "<img src=x onerror=alert(1)>";
    long id = write(member, payload, "본문");

    mockMvc
        .perform(sessions.as(member, get(POSTS)))
        .andExpect(jsonPath("$.content[0].title").value(payload));
    assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo(payload);
  }

  /**
   * T-324 — <b>작성자는 세션에서만 온다</b> (MUST).
   *
   * <p>본문으로 받으면 다른 사람 이름으로 글을 올릴 수 있다.
   */
  @Test
  void theAuthorComesFromTheSessionNotTheBody() throws Exception {
    User other =
        userRepository.saveAndFlush(Accounts.approved("sub-ot", "ot@khu.ac.kr", "20250002", "남"));

    mockMvc
        .perform(
            Csrf.with(sessions.as(member, post(POSTS)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"제목","content":"본문","authorId":%d,
                     "author":{"id":%d,"name":"남"}}
                    """
                        .formatted(other.getId(), other.getId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.author.id").value(member.getId()))
        // 표시 이름이라 학번 끝 두 자리가 붙는다 (#301, 3-2 §3-2-2).
        .andExpect(jsonPath("$.author.name").value("김부원01"));
  }

  /** 쓰기에는 CSRF 토큰이 필요하다 (§3-2-3). */
  @Test
  void writingNeedsACsrfToken() throws Exception {
    mockMvc
        .perform(
            sessions
                .as(member, post(POSTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"본문\"}"))
        .andExpect(status().isForbidden());
  }

  /* ------------------------------------------------------------------ 검증 */

  /**
   * T-325 — <b>빈 값과 상한 초과를 모두 막는다.</b>
   *
   * <p>DB의 {@code NOT NULL}은 빈 문자열과 공백 문자열을 막지 않는다 — 상한만 재면 내용 없는 글이 저장된다.
   */
  @Test
  void blankAndOversizedFieldsAreRejected() throws Exception {
    mockMvc.perform(writeRequest(member, "", "본문")).andExpect(status().isBadRequest());
    mockMvc.perform(writeRequest(member, "   ", "본문")).andExpect(status().isBadRequest());
    mockMvc.perform(writeRequest(member, "제목", "")).andExpect(status().isBadRequest());
    mockMvc
        .perform(writeRequest(member, "제목", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    mockMvc.perform(writeRequest(member, "가".repeat(201), "본문")).andExpect(status().isBadRequest());
    mockMvc
        .perform(writeRequest(member, "제목", "가".repeat(10_001)))
        .andExpect(status().isBadRequest());

    assertThat(posts.count()).isZero();
  }

  /** 상한 그 자체는 통과한다 — 경계에서 한 칸 어긋나면 멀쩡한 글이 막힌다. */
  @Test
  void exactlyAtTheLimitIsAccepted() throws Exception {
    mockMvc
        .perform(writeRequest(member, "가".repeat(200), "나".repeat(10_000)))
        .andExpect(status().isCreated());
  }

  /**
   * T-332 — <b>이모지는 한 글자다.</b>
   *
   * <p>{@code @Size}는 UTF-16 길이를 세므로 이모지를 두 글자로 잡는다. 그대로 두면 사용자 기준 200자·10,000자인 글을 API가 먼저 {@code
   * 400}으로 거절하는데, <b>PostgreSQL의 {@code LENGTH()}는 코드포인트를 세므로 DB는 받아 줄 값이다.</b> 두 단위가 어긋나 있다는 뜻이라,
   * 상한 <i>그 자체</i>를 이모지로 채워 본다.
   */
  @Test
  void aSupplementaryCharacterCountsAsOneLetter() throws Exception {
    // 코드포인트로는 200자·10,000자지만 UTF-16 길이로는 그 두 배다.
    mockMvc
        .perform(writeRequest(member, "🙂".repeat(200), "🚀".repeat(10_000)))
        .andExpect(status().isCreated());

    assertThat(posts.count()).isEqualTo(1);
  }

  /** T-333 — 이모지도 한 글자를 넘기면 막힌다. 위 사례만 두면 상한을 아예 없애도 통과한다. */
  @Test
  void aSupplementaryCharacterOverTheLimitIsStillRejected() throws Exception {
    mockMvc
        .perform(writeRequest(member, "🙂".repeat(201), "본문"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc
        .perform(writeRequest(member, "제목", "🚀".repeat(10_001)))
        .andExpect(status().isBadRequest());

    assertThat(posts.count()).isZero();
  }

  /**
   * T-331 — <b>DB도 상한을 막는다</b> (MUST, §3-2-2).
   *
   * <p>요청 검증만 두면 다른 경로로 들어온 값이 그대로 저장된다. API로는 재현할 수 없으므로 직접 넣어 본다 — <b>이 사례가 없으면 마이그레이션에서 {@code
   * CHECK}를 빠뜨려도 전부 통과한다.</b>
   */
  @Test
  void theDatabaseRejectsAnOversizedBodyToo() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO posts (title, content, author_id, created_at, updated_at)
                    VALUES ('제목', ?, NULL, NOW(), NOW())
                    """,
                    "가".repeat(10_001)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /* ------------------------------------------------------------------ 목록 */

  /** T-326 — <b>목록은 본문을 담지 않는다</b> (MUST). */
  @Test
  void theListNeverCarriesTheBody() throws Exception {
    write(member, "제목", "여기 본문이 있다");

    String json =
        mockMvc
            .perform(sessions.as(member, get(POSTS)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(json).doesNotContain("여기 본문이 있다");
    assertThat(objectMapper.readTree(json).path("content").get(0).has("content")).isFalse();
  }

  /**
   * T-327 — <b>정렬 파라미터를 받지 않는다</b> (MUST).
   *
   * <p>이상한 값만 보면 부족하다 — {@code sort=title}처럼 <b>유효한</b> 이름을 그대로 흘리는 구현은 `500`이 나지 않으면서 고정 정렬 계약을
   * 조용히 깬다.
   */
  @Test
  void sortParametersAreIgnoredEntirely() throws Exception {
    write(member, "가 첫 글", "본문");
    write(member, "나 둘째 글", "본문");
    write(member, "다 셋째 글", "본문");

    // 최신순 고정이므로 마지막에 쓴 것이 맨 앞이다.
    for (String sort : List.of("bogus", "title", "title,asc", "createdAt,asc")) {
      mockMvc
          .perform(sessions.as(member, get(POSTS + "?sort=" + sort)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].title").value("다 셋째 글"))
          .andExpect(jsonPath("$.content[2].title").value("가 첫 글"));
    }
  }

  /** 이상한 `size`도 `500`이 되지 않는다 — 상한은 `spring.data.web.pageable`이 이미 잡는다 (§3-2-8). */
  @Test
  void oddPagingParametersDoNotBreakTheServer() throws Exception {
    write(member, "제목", "본문");

    mockMvc.perform(sessions.as(member, get(POSTS + "?size=99999"))).andExpect(status().isOk());
    mockMvc.perform(sessions.as(member, get(POSTS + "?page=999"))).andExpect(status().isOk());
  }

  /**
   * T-328 — <b>같은 시각의 글이 페이지를 넘겨도 흔들리지 않는다.</b>
   *
   * <p>정렬의 마지막 기준이 {@code id}가 아니면 같은 글이 두 번 보이거나 아예 빠진다 — 훑는 사람은 그것을 알아채지 못한다.
   */
  @Test
  void pagingIsStableWhenPostsShareTheSameInstant() throws Exception {
    writeAtSameInstant(6);

    List<Long> seen = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      String json =
          mockMvc
              .perform(sessions.as(member, get(POSTS + "?page=" + page + "&size=2")))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      objectMapper
          .readTree(json)
          .path("content")
          .forEach(node -> seen.add(node.path("id").asLong()));
    }

    assertThat(seen).as("여섯 건이 겹치지도 빠지지도 않는다").hasSize(6).doesNotHaveDuplicates();
  }

  /* ------------------------------------------------------------------ 인가 */

  /** T-322 — 비로그인은 아무것도 못 한다. */
  @Test
  void guestsGetNothing() throws Exception {
    mockMvc.perform(get(POSTS)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            Csrf.with(post(POSTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"본문\"}"))
        .andExpect(status().isUnauthorized());
  }

  /** 승인 대기 계정도 막힌다 — 필터가 인가보다 먼저 끊는다. */
  @Test
  void pendingAccountsGetNothing() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-ap", "ap@khu.ac.kr", "20250003"));

    mockMvc
        .perform(sessions.as(applicant, get(POSTS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  /** 정지된 계정도 같다. */
  @Test
  void suspendedAccountsGetNothing() throws Exception {
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-sp", "sp@khu.ac.kr", "20250004"));

    mockMvc
        .perform(sessions.as(suspended, get(POSTS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /* ------------------------------------------------------------- 작성자 제거 */

  /**
   * T-329 — <b>글을 쓴 회원을 지울 수 있고, 그 글은 남는다</b> (MUST, 2-2 §2-2-4).
   *
   * <p>{@code author_id}에 {@code ON DELETE SET NULL}이 없으면 <b>글이 남는지가 아니라 회원 삭제 자체가 FK 위반으로 실패한다</b>
   * — {@code notices.author_id}가 정확히 그랬다 (#58).
   */
  @Test
  void removingTheAuthorKeepsThePostAndShowsWithdrawn() throws Exception {
    long id = write(member, "남을 글", "본문");

    mockMvc
        .perform(
            Csrf.with(
                sessions.as(
                    admin,
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/admin/users/" + member.getId()))))
        .andExpect(status().isNoContent());

    assertThat(userRepository.existsById(member.getId())).isFalse();
    mockMvc
        .perform(sessions.as(admin, get(POSTS + "/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("남을 글"))
        .andExpect(jsonPath("$.author.id").doesNotExist())
        .andExpect(jsonPath("$.author.name").value("탈퇴한 회원"));

    // 목록에서도 같다 — 이름을 모아 읽는 경로가 따로라 각각 확인한다.
    mockMvc
        .perform(sessions.as(admin, get(POSTS)))
        .andExpect(jsonPath("$.content[0].author.name").value("탈퇴한 회원"));
  }

  /**
   * <b>본문을 잘라내지 않는다</b> (MUST, §3-2-5, #257 리뷰).
   *
   * <p>"받은 문자열을 그대로 저장하고 그대로 내보낸다"고 해 놓고 {@code trim}하면 그 계약이 첫 줄부터 깨진다 — <b>들여쓴 코드나 마지막 개행이 저장 시점에
   * 이미 사라진다.</b> 공백뿐인 입력은 {@code @NotBlank}가 이미 막으므로 잘라낼 이유도 없다.
   */
  @Test
  void theBodyKeepsItsLeadingAndTrailingWhitespace() throws Exception {
    String payload = "\n  들여쓴 코드\n    더 들여쓴 줄\n\n";
    String json =
        mockMvc
            .perform(
                Csrf.with(sessions.as(member, post(POSTS)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            java.util.Map.of("title", "제목", "content", payload))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long id = objectMapper.readTree(json).path("id").asLong();
    assertThat(posts.findById(id).orElseThrow().getContent()).isEqualTo(payload);
    mockMvc
        .perform(sessions.as(member, get(POSTS + "/" + id)))
        .andExpect(jsonPath("$.content").value(payload));
  }

  /**
   * <b>필터를 지난 뒤 정지된 사람은 글을 남기지 못한다</b> (3-1 §3-1-4 MUST, #257 리뷰).
   *
   * <p>인가는 세션 값으로 이루어지고 필터는 매 요청 {@code users}를 읽지 않는다(결정 12). 세션을 그대로 둔 채 DB만 바꾸면 <b>필터를 지난 뒤 정지된
   * 상태</b>가 그대로 재현된다 — 저장 직전에 잠그고 다시 보지 않으면 그 글이 커밋된다.
   */
  @Test
  void anAuthorSuspendedAfterAuthorizationCannotFinishWriting() throws Exception {
    User target = userRepository.findById(member.getId()).orElseThrow();
    target.suspend();
    userRepository.saveAndFlush(target);

    mockMvc
        .perform(writeRequest(member, "정지 뒤에 도착한 글", "본문"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));

    assertThat(posts.count()).isZero();
  }

  /* ------------------------------------------------------------------ 수정 (#256) */

  /**
   * T-341 — <b>작성자 본인이 제목·본문을 통째로 고친다</b> (MUST).
   *
   * <p>{@code updatedAt}이 {@code createdAt}과 달라진다 — 그 자체가 "수정됨"의 근거다 (결정 18).
   */
  @Test
  void authorEditsTheirOwnPost() throws Exception {
    long id = write(member, "원래 제목", "원래 본문");
    /*
     * PostgreSQL TIMESTAMP는 마이크로초 정밀도다. write()에 들어간 Instant나 API 직렬화값이
     * 아니라 수정 직전 DB round-trip 값을 기준으로 잡아, 수정이 저장 시각을 보존했는지
     * 정밀도 차이 없이 비교한다.
     */
    var original = posts.findById(id).orElseThrow();
    Instant originalCreatedAt = original.getCreatedAt();
    assertThat(original.getUpdatedAt()).isEqualTo(originalCreatedAt);

    mockMvc
        .perform(editRequest(member, id, "고친 제목", "고친 본문"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("고친 제목"))
        .andExpect(jsonPath("$.content").value("고친 본문"))
        .andExpect(jsonPath("$.author.id").value(member.getId()));

    mockMvc
        .perform(sessions.as(member, get(POSTS + "/" + id)))
        .andExpect(jsonPath("$.title").value("고친 제목"))
        .andExpect(jsonPath("$.content").value("고친 본문"));

    var edited = posts.findById(id).orElseThrow();
    assertThat(edited.getCreatedAt()).isEqualTo(originalCreatedAt);
    assertThat(edited.getUpdatedAt()).isNotEqualTo(edited.getCreatedAt());
  }

  /** T-342 — <b>남의 글은 고칠 수 없다</b> (MUST). 예외가 없다 — 작성자 본인만이다. */
  @Test
  void aMemberCannotEditSomeoneElsesPost() throws Exception {
    long id = write(member, "제목", "본문");
    User other =
        userRepository.saveAndFlush(Accounts.approved("sub-ot", "ot@khu.ac.kr", "20250002", "남"));

    mockMvc
        .perform(editRequest(other, id, "가로챈 제목", "가로챈 본문"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo("제목");
  }

  /** T-343 — <b>관리자도 예외가 아니다</b> (MUST, 결정 18 D1). 삭제는 관리자 전용이지만 수정은 반대다 — 관리자도 남의 글은 고칠 수 없다. */
  @Test
  void anAdminCannotEditSomeoneElsesPostEither() throws Exception {
    long id = write(member, "제목", "본문");

    mockMvc
        .perform(editRequest(admin, id, "관리자가 고친 제목", "관리자가 고친 본문"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo("제목");
  }

  /** 없는 글을 수정하면 {@code 404}다 — 소유자 확인보다 존재 확인이 먼저다. */
  @Test
  void editingAMissingPostIsNotFound() throws Exception {
    mockMvc
        .perform(editRequest(member, 999_999L, "제목", "본문"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /** <b>작성자는 바뀌지 않는다</b> (MUST, 결정 18 완료 조건). 수정은 내용만 바꾼다. */
  @Test
  void editingDoesNotChangeTheAuthor() throws Exception {
    long id = write(member, "제목", "본문");

    mockMvc
        .perform(editRequest(member, id, "고친 제목", "고친 본문"))
        .andExpect(jsonPath("$.author.id").value(member.getId()))
        .andExpect(jsonPath("$.author.name").value("김부원"));
  }

  /** 수정 요청도 등록과 같은 검증을 받는다 — 빈 값·상한 초과는 {@code 400}이다. */
  @Test
  void editRequestsAreValidatedLikeCreation() throws Exception {
    long id = write(member, "제목", "본문");

    mockMvc.perform(editRequest(member, id, "", "본문")).andExpect(status().isBadRequest());
    mockMvc
        .perform(editRequest(member, id, "제목", "가".repeat(10_001)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    // 거절된 수정은 반영되지 않는다.
    assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo("제목");
  }

  /** 수정 본문도 평문이다 (T-323과 같은 이유) — 서버가 정화하거나 변형하지 않는다. */
  @Test
  void editedHtmlIsStoredVerbatimToo() throws Exception {
    long id = write(member, "제목", "본문");
    String payload = "<script>alert(1)</script>";

    mockMvc
        .perform(editRequest(member, id, "제목", payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value(payload));

    assertThat(posts.findById(id).orElseThrow().getContent()).isEqualTo(payload);
  }

  /** 비로그인은 수정을 시도조차 할 수 없다. */
  @Test
  void guestsCannotEdit() throws Exception {
    long id = write(member, "제목", "본문");

    mockMvc
        .perform(
            Csrf.with(patch(POSTS + "/" + id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());

    assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo("제목");
  }

  /** 수정에도 CSRF 토큰이 필요하다 (§3-2-3). */
  @Test
  void editingNeedsACsrfToken() throws Exception {
    long id = write(member, "제목", "본문");

    mockMvc
        .perform(
            sessions
                .as(member, patch(POSTS + "/" + id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"본문\"}"))
        .andExpect(status().isForbidden());
  }

  /**
   * <b>필터를 지난 뒤 정지된 사람은 수정을 끝내지 못한다</b> (3-1 §3-1-7 MUST, {@link
   * #anAuthorSuspendedAfterAuthorizationCannotFinishWriting}과 같은 이유).
   */
  @Test
  void anAuthorSuspendedAfterAuthorizationCannotFinishEditing() throws Exception {
    long id = write(member, "제목", "본문");
    User target = userRepository.findById(member.getId()).orElseThrow();
    target.suspend();
    userRepository.saveAndFlush(target);

    mockMvc
        .perform(editRequest(member, id, "정지 뒤에 도착한 수정", "본문"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));

    assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo("제목");
  }

  /* ------------------------------------------------------------------ 삭제 (#238) */

  /**
   * T-463 — <b>관리자가 글을 완전히 지운다</b> (MUST).
   *
   * <p>목록·상세 어디서도 다시 보이지 않는다 — 감춤이 아니라 행 자체가 사라진다.
   */
  @Test
  void adminDeletesAPost() throws Exception {
    long id = write(member, "지워질 글", "본문");

    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(POSTS + "/" + id))))
        .andExpect(status().isNoContent());

    assertThat(posts.existsById(id)).isFalse();
    mockMvc.perform(sessions.as(admin, get(POSTS + "/" + id))).andExpect(status().isNotFound());
    mockMvc
        .perform(sessions.as(admin, get(POSTS)))
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  /** T-464 — <b>ACTIVE 작성자 본인은 자기 글을 완전히 지울 수 있다</b> (MUST). */
  @Test
  void theAuthorDeletesTheirOwnPost() throws Exception {
    long id = write(member, "내가 쓴 글", "본문");

    mockMvc
        .perform(Csrf.with(sessions.as(member, delete(POSTS + "/" + id))))
        .andExpect(status().isNoContent());

    assertThat(posts.existsById(id)).isFalse();
  }

  /** T-464 — 비활동 부원은 자료 외 기능을 그대로 쓰므로 자기 글도 지울 수 있다. */
  @Test
  void anInactiveAuthorDeletesTheirOwnPost() throws Exception {
    long id = write(member, "비활동 중 지울 글", "본문");
    User inactive = userRepository.findById(member.getId()).orElseThrow();
    inactive.deactivate(Instant.now());
    userRepository.saveAndFlush(inactive);

    mockMvc
        .perform(Csrf.with(sessions.as(inactive, delete(POSTS + "/" + id))))
        .andExpect(status().isNoContent());

    assertThat(posts.existsById(id)).isFalse();
  }

  /** T-464 — 일반 부원은 남의 글을 지울 수 없다. */
  @Test
  void aMemberCannotDeleteSomeoneElsesPost() throws Exception {
    long id = write(admin, "관리자가 쓴 글", "본문");

    mockMvc
        .perform(Csrf.with(sessions.as(member, delete(POSTS + "/" + id))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(posts.existsById(id)).isTrue();
  }

  /** T-464 — 관리자가 자신이 쓴 글을 지울 때도 같은 완전 삭제 경로를 쓴다. */
  @Test
  void anAdminDeletesTheirOwnPost() throws Exception {
    long id = write(admin, "관리자 본인 글", "본문");

    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(POSTS + "/" + id))))
        .andExpect(status().isNoContent());

    assertThat(posts.existsById(id)).isFalse();
  }

  /** T-476 — 승인 대기 상태는 작성자 id가 같아도 필터에서 삭제를 거부한다. */
  @Test
  void aPendingAuthorCannotDelete() throws Exception {
    User pending =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));
    Post post = posts.saveAndFlush(Post.write("신청자 글", "본문", pending.getId(), Instant.now()));

    mockMvc
        .perform(Csrf.with(sessions.as(pending, delete(POSTS + "/" + post.getId()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));

    assertThat(posts.existsById(post.getId())).isTrue();
  }

  /**
   * T-476 — 세션이 아직 {@code ADMIN}이어도 DB에서 권한이 회수됐으면 삭제할 수 없다.
   *
   * <p>{@code @PreAuthorize}는 로그인 때 저장한 role을 보므로 이 요청은 컨트롤러까지 들어간다. 서비스가 잠근 최신 사용자 행을 다시 확인해야 되돌릴
   * 수 없는 삭제를 막는다.
   */
  @Test
  void anAdminWhoseRoleWasRevokedAfterAuthorizationCannotDelete() throws Exception {
    long id = write(member, "남아야 할 글", "본문");
    SignedIn staleAdminSession = sessions.signIn(admin);
    User storedAdmin = userRepository.findById(admin.getId()).orElseThrow();
    storedAdmin.demoteToUser();
    userRepository.saveAndFlush(storedAdmin);

    mockMvc
        .perform(Csrf.with(staleAdminSession.on(delete(POSTS + "/" + id))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(posts.existsById(id)).isTrue();
  }

  /** T-476 — 세션이 아직 {@code ACTIVE}여도 DB에서 정지됐으면 같은 최신 상태 재검증이 삭제를 막는다. */
  @Test
  void anAdminWhoWasSuspendedAfterAuthorizationCannotDelete() throws Exception {
    long id = write(member, "남아야 할 글", "본문");
    SignedIn staleAdminSession = sessions.signIn(admin);
    User storedAdmin = userRepository.findById(admin.getId()).orElseThrow();
    storedAdmin.suspend();
    userRepository.saveAndFlush(storedAdmin);

    mockMvc
        .perform(Csrf.with(staleAdminSession.on(delete(POSTS + "/" + id))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));

    assertThat(posts.existsById(id)).isTrue();
  }

  /** T-476 — 작성자도 인가 뒤 정지됐다면 잠근 최신 계정 상태가 삭제를 막는다. */
  @Test
  void anAuthorSuspendedAfterAuthorizationCannotDelete() throws Exception {
    long id = write(member, "남아야 할 작성자 글", "본문");
    SignedIn staleAuthorSession = sessions.signIn(member);
    User storedAuthor = userRepository.findById(member.getId()).orElseThrow();
    storedAuthor.suspend();
    userRepository.saveAndFlush(storedAuthor);

    mockMvc
        .perform(Csrf.with(staleAuthorSession.on(delete(POSTS + "/" + id))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));

    assertThat(posts.existsById(id)).isTrue();
  }

  /** T-476 — 제거된 작성자는 옛 세션이 남아 있어도 작성자 관계가 끊겨 자기 글을 지울 수 없다. */
  @Test
  void aRemovedAuthorCannotDeleteWithAStaleSession() throws Exception {
    long id = write(member, "남아야 할 탈퇴 작성자 글", "본문");
    SignedIn staleAuthorSession = sessions.signIn(member);
    userRepository.deleteById(member.getId());
    userRepository.flush();

    assertThat(posts.findById(id).orElseThrow().getAuthorId()).isNull();
    mockMvc
        .perform(Csrf.with(staleAuthorSession.on(delete(POSTS + "/" + id))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

    assertThat(posts.existsById(id)).isTrue();
  }

  /** 비로그인은 삭제를 시도조차 할 수 없다. */
  @Test
  void guestsCannotDelete() throws Exception {
    long id = write(member, "제목", "본문");

    mockMvc.perform(Csrf.with(delete(POSTS + "/" + id))).andExpect(status().isUnauthorized());

    assertThat(posts.existsById(id)).isTrue();
  }

  /** 삭제에도 CSRF 토큰이 필요하다 (§3-2-3, {@link #writingNeedsACsrfToken}과 같은 이유). */
  @Test
  void deletingNeedsACsrfToken() throws Exception {
    long id = write(member, "제목", "본문");

    mockMvc.perform(sessions.as(admin, delete(POSTS + "/" + id))).andExpect(status().isForbidden());

    assertThat(posts.existsById(id)).isTrue();
  }

  /** 없는 글은 {@code 404}다 — 관리자 권한과 별개로 대상이 있어야 한다. */
  @Test
  void deletingAMissingPostIsNotFound() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(POSTS + "/999999"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
