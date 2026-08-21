package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.note.repository.BookmarkRepository;
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
 * 즐겨찾기 (#56, spec 2-1 §2-1-5, 3-2 §3-2-4).
 *
 * <p>자료는 SQL로 직접 넣는다 — 등록 API가 아직 없고(#53), 즐겨찾기가 지켜야 하는 것은 넣는 방법과 무관하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookmarkIntegrationTest extends AbstractIntegrationTest {

  private static final String NOTES = "/api/v1/notes";
  private static final String BOOKMARKS = "/api/v1/bookmarks";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private BookmarkRepository bookmarks;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User me;
  private User other;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM bookmarks");
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    userRepository.deleteAll();
    me = userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001"));
    other = userRepository.saveAndFlush(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));
  }

  @AfterEach
  void clear() {
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

  private MockHttpServletRequestBuilder add(User caller, Long noteId) {
    return Csrf.with(sessions.as(caller, post(NOTES + "/" + noteId + "/bookmark")));
  }

  private MockHttpServletRequestBuilder remove(User caller, Long noteId) {
    return Csrf.with(sessions.as(caller, delete(NOTES + "/" + noteId + "/bookmark")));
  }

  /* ------------------------------------------------------------------ 추가 */

  @Test
  void addsABookmark() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(add(me, noteId)).andExpect(status().isNoContent());

    assertThat(bookmarks.existsByUserIdAndNoteId(me.getId(), noteId)).isTrue();
  }

  /**
   * <b>이미 담겨 있어도 성공이다.</b>
   *
   * <p>목록과 상세에서 각각 누르거나 두 번 누르는 일은 흔하다. 오류를 주면 화면은 <b>사용자에게 아무 의미 없는 안내</b>를 띄워야 한다.
   *
   * <p><b>토글이 아니라는 것도 여기서 지켜진다</b> — 두 번 눌러도 빠지지 않는다. 뒤집는 구현이면 재시도가 방금 담은 것을 조용히 뺀다.
   */
  @Test
  void addingTwiceKeepsItBookmarked() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(add(me, noteId)).andExpect(status().isNoContent());
    mockMvc.perform(add(me, noteId)).andExpect(status().isNoContent());

    assertThat(bookmarks.findAll()).hasSize(1);
    assertThat(bookmarks.existsByUserIdAndNoteId(me.getId(), noteId)).isTrue();
  }

  /** 없는 자료는 {@code 404}다. 그대로 넣으면 FK 위반이 {@code 500}으로 나간다. */
  @Test
  void bookmarkingAMissingNoteIsNotFound() throws Exception {
    mockMvc
        .perform(add(me, 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(bookmarks.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 해제 */

  @Test
  void removesABookmark() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(add(me, noteId)).andExpect(status().isNoContent());

    mockMvc.perform(remove(me, noteId)).andExpect(status().isNoContent());

    assertThat(bookmarks.findAll()).isEmpty();
  }

  /** 담겨 있지 않아도, 없는 자료여도 성공이다 — 화면이 지울 수 없는 별표를 들고 있게 하지 않는다. */
  @Test
  void removingWhatIsNotThereSucceeds() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(remove(me, noteId)).andExpect(status().isNoContent());
    mockMvc.perform(remove(me, 999_999L)).andExpect(status().isNoContent());
  }

  /** 남의 즐겨찾기를 건드리지 않는다. */
  @Test
  void removingDoesNotTouchSomeoneElse() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(add(other, noteId)).andExpect(status().isNoContent());

    mockMvc.perform(remove(me, noteId)).andExpect(status().isNoContent());

    assertThat(bookmarks.existsByUserIdAndNoteId(other.getId(), noteId)).isTrue();
  }

  /* ------------------------------------------------------------------ 목록 */

  /** <b>내 것만 나온다</b> (완료 조건). */
  @Test
  void listsOnlyMyBookmarks() throws Exception {
    Long mine = insertNote("내 것");
    Long theirs = insertNote("남의 것");
    mockMvc.perform(add(me, mine)).andExpect(status().isNoContent());
    mockMvc.perform(add(other, theirs)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(BOOKMARKS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].title").value("내 것"))
        // 이 목록의 항목은 언제나 담겨 있다.
        .andExpect(jsonPath("$.content[0].bookmarked").value(true));
  }

  /**
   * <b>정렬은 내가 표시한 순서다</b> — 자료의 등록 시각이 아니다.
   *
   * <p>먼저 올라온 자료를 나중에 담았다면 그것이 위에 온다. 이 화면의 기준은 "언제 올라온 자료인가"가 아니라 "언제 내가 담았나"다.
   */
  @Test
  void ordersByWhenIBookmarkedNotWhenTheNoteWasCreated() throws Exception {
    Long older = insertNote("먼저 올라온 것");
    Long newer = insertNote("나중에 올라온 것");

    // 나중에 올라온 것을 먼저 담고, 먼저 올라온 것을 나중에 담는다.
    mockMvc.perform(add(me, newer)).andExpect(status().isNoContent());
    Thread.sleep(10);
    mockMvc.perform(add(me, older)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(BOOKMARKS)))
        .andExpect(jsonPath("$.content[0].title").value("먼저 올라온 것"))
        .andExpect(jsonPath("$.content[1].title").value("나중에 올라온 것"));
  }

  /** 자료가 지워지면 즐겨찾기도 함께 사라진다 (완료 조건, {@code ON DELETE CASCADE}). */
  @Test
  void deletingANoteRemovesItsBookmarks() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(add(me, noteId)).andExpect(status().isNoContent());

    jdbcTemplate.update("DELETE FROM notes WHERE id = ?", noteId);

    assertThat(bookmarks.findAll()).isEmpty();
    mockMvc
        .perform(sessions.as(me, get(BOOKMARKS)))
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  /* --------------------------------------------------------- 자료 목록의 표시 */

  /**
   * <b>자료 목록·상세가 "내가 담았나"를 알려준다</b> (2-1 §2-1-5 — 목록에서도 추가·해제한다).
   *
   * <p>이것이 없으면 화면이 별표를 채울지 비울지 알 수 없어 {@code GET /bookmarks}를 통째로 받아 대조해야 한다.
   */
  @Test
  void noteListAndDetailShowWhetherIBookmarkedIt() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc
        .perform(sessions.as(me, get(NOTES)))
        .andExpect(jsonPath("$.content[0].bookmarked").value(false));
    mockMvc
        .perform(sessions.as(me, get(NOTES + "/" + noteId)))
        .andExpect(jsonPath("$.bookmarked").value(false));

    mockMvc.perform(add(me, noteId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES)))
        .andExpect(jsonPath("$.content[0].bookmarked").value(true));
    mockMvc
        .perform(sessions.as(me, get(NOTES + "/" + noteId)))
        .andExpect(jsonPath("$.bookmarked").value(true));
  }

  /** <b>남이 담은 것은 내 별표가 아니다.</b> 표시가 새면 목록이 남의 상태를 보여준다. */
  @Test
  void someoneElsesBookmarkDoesNotShowAsMine() throws Exception {
    Long noteId = insertNote("정리");
    mockMvc.perform(add(other, noteId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTES)))
        .andExpect(jsonPath("$.content[0].bookmarked").value(false));
  }

  /* ------------------------------------------------------------------ 권한 */

  @Test
  void requiresAuthentication() throws Exception {
    Long noteId = insertNote("정리");

    mockMvc.perform(get(BOOKMARKS)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(Csrf.with(post(NOTES + "/" + noteId + "/bookmark")))
        .andExpect(status().isUnauthorized());
  }

  /** 승인 대기 회원은 막힌다 — {@code AccountStatusFilter}가 인가보다 먼저 본다. */
  @Test
  void pendingMemberIsBlocked() throws Exception {
    Long noteId = insertNote("정리");
    User pending =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));

    mockMvc
        .perform(sessions.as(pending, get(BOOKMARKS)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
    mockMvc
        .perform(Csrf.with(sessions.as(pending, post(NOTES + "/" + noteId + "/bookmark"))))
        .andExpect(status().isForbidden());
    assertThat(bookmarks.findAll()).isEmpty();
  }

  /** 담긴 시각이 기록된다 — 정렬의 기준이다. */
  @Test
  void recordsWhenItWasBookmarked() throws Exception {
    Instant before = Instant.now().minusSeconds(1);
    Long noteId = insertNote("정리");

    mockMvc.perform(add(me, noteId)).andExpect(status().isNoContent());

    assertThat(bookmarks.findAll())
        .singleElement()
        .satisfies(bookmark -> assertThat(bookmark.getCreatedAt()).isAfter(before));
  }
}
