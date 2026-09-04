package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.note.repository.NoteLikeRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 자료 좋아요 (#344, spec 2-1 §2-1-1, 3-2 §3-2-4, 3-3 결정 25).
 *
 * <p>멱등성·동시성 계약은 즐겨찾기({@code BookmarkIntegrationTest})와 같다 — 여기서는 그 계약이 좋아요에도 적용되는지, 그리고 좋아요만의
 * 규칙(즐겨찾기와 별개 자원, `likeCount`·`likedByMe` 응답, `INACTIVE` 차단)을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoteLikeIntegrationTest extends AbstractIntegrationTest {

  private static final String NOTES = "/api/v1/notes";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private NoteLikeRepository likes;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User me;
  private User other;

  @BeforeEach
  void setUp() {
    clearAll();
    me = userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001"));
    other = userRepository.saveAndFlush(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM note_likes");
    jdbcTemplate.update("DELETE FROM bookmarks");
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  private Long insertNote(String title) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO notes (category, title, subject_name, year, semester, uploader_id,
                           created_at, updated_at)
        VALUES ('SUBJECT', ?, '운영체제', 2025, 'SPRING', ?, now(), now()) RETURNING id
        """,
        Long.class,
        title,
        me.getId());
  }

  private MockHttpServletRequestBuilder like(User caller, Long noteId) {
    return Csrf.with(sessions.as(caller, post(NOTES + "/" + noteId + "/like")));
  }

  private MockHttpServletRequestBuilder unlike(User caller, Long noteId) {
    return Csrf.with(sessions.as(caller, delete(NOTES + "/" + noteId + "/like")));
  }

  /* ------------------------------------------------------------------ 누르기 */

  @Test
  void likesANote() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndNoteId(me.getId(), noteId)).isTrue();
  }

  /** <b>이미 눌렀어도 성공이다.</b> 두 번 눌러도 좋아요는 하나다 — 토글이 아니다. */
  @Test
  void likingTwiceStaysAsOneLike() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());
    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    assertThat(likes.findAll()).hasSize(1);
  }

  /** 없는 자료는 {@code 404}다. */
  @Test
  void likingAMissingNoteIsNotFound() throws Exception {
    mockMvc
        .perform(like(me, 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 취소 */

  @Test
  void unlikesANote() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, noteId)).andExpect(status().isNoContent());

    assertThat(likes.findAll()).isEmpty();
  }

  /** 눌러져 있지 않아도, 없는 자료여도 성공이다. */
  @Test
  void unlikingWhatIsNotThereSucceeds() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(unlike(me, noteId)).andExpect(status().isNoContent());
    mockMvc.perform(unlike(me, 999_999L)).andExpect(status().isNoContent());
  }

  /** 남의 좋아요를 건드리지 않는다. */
  @Test
  void unlikingDoesNotTouchSomeoneElse() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(like(other, noteId)).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, noteId)).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndNoteId(other.getId(), noteId)).isTrue();
  }

  /* --------------------------------------------------------- 목록·상세 표시 */

  /** <b>목록·상세가 개수와 내가 눌렀는지를 함께 보여준다</b> (#344 완료 조건). */
  @Test
  void listAndDetailShowLikeCountAndWhetherILiked() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc
        .perform(sessions.as(me, get(NOTES)))
        .andExpect(jsonPath("$.content[0].likeCount").value(0))
        .andExpect(jsonPath("$.content[0].likedByMe").value(false));
    mockMvc
        .perform(sessions.as(me, get(NOTES + "/" + noteId)))
        .andExpect(jsonPath("$.likeCount").value(0))
        .andExpect(jsonPath("$.likedByMe").value(false));

    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());
    mockMvc.perform(like(other, noteId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES)))
        .andExpect(jsonPath("$.content[0].likeCount").value(2))
        .andExpect(jsonPath("$.content[0].likedByMe").value(true));
    mockMvc
        .perform(sessions.as(me, get(NOTES + "/" + noteId)))
        .andExpect(jsonPath("$.likeCount").value(2))
        .andExpect(jsonPath("$.likedByMe").value(true));
  }

  /** <b>즐겨찾기와 완전히 별개다</b> (3-3 결정 25 D1) — 좋아요를 눌러도 즐겨찾기 여부는 그대로다. */
  @Test
  void likingDoesNotAffectBookmarkStatus() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES + "/" + noteId)))
        .andExpect(jsonPath("$.likedByMe").value(true))
        .andExpect(jsonPath("$.bookmarked").value(false));
  }

  /* --------------------------------------------------------------- CASCADE */

  /** 자료가 지워지면 좋아요도 함께 사라진다 (완료 조건, {@code ON DELETE CASCADE}). */
  @Test
  void deletingANoteRemovesItsLikes() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    jdbcTemplate.update("DELETE FROM notes WHERE id = ?", noteId);

    assertThat(likes.findAll()).isEmpty();
  }

  /** 좋아요를 누른 회원이 지워지면 그 좋아요도 함께 사라진다. */
  @Test
  void removingAUserRemovesTheirLikes() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    userRepository.deleteById(me.getId());

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 권한 */

  @Test
  void requiresAuthentication() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc
        .perform(Csrf.with(post(NOTES + "/" + noteId + "/like")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pendingMemberIsBlocked() throws Exception {
    Long noteId = insertNote("정리");
    User pending =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));

    mockMvc
        .perform(Csrf.with(sessions.as(pending, post(NOTES + "/" + noteId + "/like"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
    assertThat(likes.findAll()).isEmpty();
  }

  /**
   * <b>비활동 부원은 좋아요를 누를 수 없다</b> — 자료는 자료 갈래 전체가 막힌다(#228).
   *
   * <p>즐겨찾기와 같은 권한이다 — {@code NoteLikeService}가 {@code RequesterCheck#requireNoteAccess}를 쓴다는 계약이
   * 여기서 지켜진다. 공지 좋아요(#343)와 정반대다.
   */
  @Test
  void inactiveMemberCannotLike() throws Exception {
    Long noteId = insertNote("정리");
    User inactive = userRepository.findById(me.getId()).orElseThrow();
    inactive.deactivate(Instant.now());
    userRepository.saveAndFlush(inactive);

    mockMvc
        .perform(like(inactive, noteId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INACTIVE"));

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------- 내가 좋아요한 자료 (#355) */

  /** <b>{@code liked=true}는 내가 누른 자료만 준다</b> (#355 완료 조건). */
  @Test
  void likedReturnsOnlyWhatILiked() throws Exception {
    Long mine = insertNote("내가 누른 것");
    Long theirs = insertNote("남이 누른 것");
    mockMvc.perform(like(me, mine)).andExpect(status().isNoContent());
    mockMvc.perform(like(other, theirs)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES)).param("liked", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].title").value("내가 누른 것"));
  }

  /** <b>좋아요를 취소하면 이 목록에서도 빠진다</b> (#355 완료 조건). */
  @Test
  void unlikingRemovesItFromTheLikedList() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, noteId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES)).param("liked", "true"))
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  /** <b>한 자료를 여럿이 눌러도 한 번만 나온다</b> — 조인으로 짜면 좋아요 수만큼 중복된다. */
  @Test
  void likedDoesNotDuplicateWhenManyPeopleLikedIt() throws Exception {
    Long noteId = insertNote("인기 자료");
    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());
    mockMvc.perform(like(other, noteId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES)).param("liked", "true"))
        .andExpect(jsonPath("$.page.totalElements").value(1));
  }

  /** 검색·필터와 AND로 함께 걸린다. */
  @Test
  void likedCombinesWithOtherFilters() throws Exception {
    // 도구가 넣는 자료는 과목명이 모두 같으므로, 검색어는 제목으로만 갈리게 고른다.
    Long mid = insertNote("중간고사 정리");
    Long fin = insertNote("기말고사 정리");
    mockMvc.perform(like(me, mid)).andExpect(status().isNoContent());
    mockMvc.perform(like(me, fin)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES)).param("liked", "true").param("q", "중간고사"))
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].title").value("중간고사 정리"));
  }

  /* ------------------------------------------------------------------ 동시 */

  /** <b>동시에 눌러도 하나다.</b> */
  @Test
  void concurrentLikesStayIdempotent() throws Exception {
    Long noteId = insertNote("정리");
    int burst = 8;
    ExecutorService pool = Executors.newFixedThreadPool(burst);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> shots = new ArrayList<>();
      for (int shot = 0; shot < burst; shot++) {
        shots.add(
            pool.submit(
                () -> {
                  start.await();
                  mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> shot : shots) {
        shot.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(likes.findAll()).hasSize(1);
  }

  /** <b>동시에 떼도 모두 성공이다.</b> */
  @Test
  void concurrentUnlikesStayIdempotent() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(like(me, noteId)).andExpect(status().isNoContent());

    int burst = 8;
    ExecutorService pool = Executors.newFixedThreadPool(burst);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> shots = new ArrayList<>();
      for (int shot = 0; shot < burst; shot++) {
        shots.add(
            pool.submit(
                () -> {
                  start.await();
                  mockMvc.perform(unlike(me, noteId)).andExpect(status().isNoContent());
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> shot : shots) {
        shot.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(likes.findAll()).isEmpty();
  }
}
