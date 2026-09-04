package org.hackerkhu.hackerhp.domain.notice;

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
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeLikeRepository;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeRepository;
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
 * 공지 좋아요 (#343, spec 2-1 §2-1-6, 3-2 §3-2-5, 3-3 결정 24).
 *
 * <p>담기·빼기의 멱등성·동시성 계약은 즐겨찾기({@code BookmarkIntegrationTest})와 같다 — 여기서는 그 계약이 공지에도 동일하게 적용되는지, 그리고
 * 공지만의 규칙(`ACTIVE`·`INACTIVE` 둘 다 열림, 목록·상세의 `likeCount`·`likedByMe`)을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoticeLikeIntegrationTest extends AbstractIntegrationTest {

  private static final String NOTICES = "/api/v1/notices";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private NoticeRepository noticeRepository;
  @Autowired private NoticeLikeRepository likes;
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
    jdbcTemplate.update("DELETE FROM notice_likes");
    jdbcTemplate.update("DELETE FROM notices");
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  private Long insertNotice(String title) {
    Notice notice = Notice.write(title, "본문", null);
    return noticeRepository.saveAndFlush(notice).getId();
  }

  private MockHttpServletRequestBuilder like(User caller, Long noticeId) {
    return Csrf.with(sessions.as(caller, post(NOTICES + "/" + noticeId + "/like")));
  }

  private MockHttpServletRequestBuilder unlike(User caller, Long noticeId) {
    return Csrf.with(sessions.as(caller, delete(NOTICES + "/" + noticeId + "/like")));
  }

  /* ------------------------------------------------------------------ 누르기 */

  @Test
  void likesANotice() throws Exception {
    Long noticeId = insertNotice("공지");

    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndNoticeId(me.getId(), noticeId)).isTrue();
  }

  /** <b>이미 눌렀어도 성공이다.</b> 두 번 눌러도 좋아요는 하나다 — 토글이 아니다. */
  @Test
  void likingTwiceStaysAsOneLike() throws Exception {
    Long noticeId = insertNotice("공지");

    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());
    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());

    assertThat(likes.findAll()).hasSize(1);
  }

  /** 없는 공지는 {@code 404}다. 그대로 넣으면 FK 위반이 {@code 500}으로 나간다. */
  @Test
  void likingAMissingNoticeIsNotFound() throws Exception {
    mockMvc
        .perform(like(me, 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 취소 */

  @Test
  void unlikesANotice() throws Exception {
    Long noticeId = insertNotice("공지");
    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, noticeId)).andExpect(status().isNoContent());

    assertThat(likes.findAll()).isEmpty();
  }

  /** 눌러져 있지 않아도, 없는 공지여도 성공이다 — 화면이 지울 수 없는 표시를 들고 있게 하지 않는다. */
  @Test
  void unlikingWhatIsNotThereSucceeds() throws Exception {
    Long noticeId = insertNotice("공지");

    mockMvc.perform(unlike(me, noticeId)).andExpect(status().isNoContent());
    mockMvc.perform(unlike(me, 999_999L)).andExpect(status().isNoContent());
  }

  /** 남의 좋아요를 건드리지 않는다. */
  @Test
  void unlikingDoesNotTouchSomeoneElse() throws Exception {
    Long noticeId = insertNotice("공지");
    mockMvc.perform(like(other, noticeId)).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, noticeId)).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndNoticeId(other.getId(), noticeId)).isTrue();
  }

  /* --------------------------------------------------------- 목록·상세 표시 */

  /** <b>목록·상세가 개수와 내가 눌렀는지를 함께 보여준다</b> (#343 완료 조건). */
  @Test
  void listAndDetailShowLikeCountAndWhetherIliked() throws Exception {
    Long noticeId = insertNotice("공지");

    mockMvc
        .perform(sessions.as(me, get(NOTICES)))
        .andExpect(jsonPath("$.content[0].likeCount").value(0))
        .andExpect(jsonPath("$.content[0].likedByMe").value(false));
    mockMvc
        .perform(sessions.as(me, get(NOTICES + "/" + noticeId)))
        .andExpect(jsonPath("$.likeCount").value(0))
        .andExpect(jsonPath("$.likedByMe").value(false));

    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());
    mockMvc.perform(like(other, noticeId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTICES)))
        .andExpect(jsonPath("$.content[0].likeCount").value(2))
        .andExpect(jsonPath("$.content[0].likedByMe").value(true));
    mockMvc
        .perform(sessions.as(me, get(NOTICES + "/" + noticeId)))
        .andExpect(jsonPath("$.likeCount").value(2))
        .andExpect(jsonPath("$.likedByMe").value(true));
  }

  /** <b>남이 누른 것은 내 좋아요가 아니다.</b> 표시가 새면 목록이 남의 상태를 보여준다. */
  @Test
  void someoneElsesLikeDoesNotShowAsMine() throws Exception {
    Long noticeId = insertNotice("공지");
    mockMvc.perform(like(other, noticeId)).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(NOTICES)))
        .andExpect(jsonPath("$.content[0].likeCount").value(1))
        .andExpect(jsonPath("$.content[0].likedByMe").value(false));
  }

  /* --------------------------------------------------------------- CASCADE */

  /** 공지가 지워지면 좋아요도 함께 사라진다 (완료 조건, {@code ON DELETE CASCADE}). */
  @Test
  void deletingANoticeRemovesItsLikes() throws Exception {
    Long noticeId = insertNotice("공지");
    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());

    jdbcTemplate.update("DELETE FROM notices WHERE id = ?", noticeId);

    assertThat(likes.findAll()).isEmpty();
  }

  /** 좋아요를 누른 회원이 지워지면 그 좋아요도 함께 사라진다 — bookmarks와 같은 판단(3-3 결정 24). */
  @Test
  void removingAUserRemovesTheirLikes() throws Exception {
    Long noticeId = insertNotice("공지");
    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());

    userRepository.deleteById(me.getId());

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 권한 */

  @Test
  void requiresAuthentication() throws Exception {
    Long noticeId = insertNotice("공지");

    mockMvc
        .perform(Csrf.with(post(NOTICES + "/" + noticeId + "/like")))
        .andExpect(status().isUnauthorized());
  }

  /** 승인 대기 회원은 막힌다 — {@code AccountStatusFilter}가 인가보다 먼저 본다. */
  @Test
  void pendingMemberIsBlocked() throws Exception {
    Long noticeId = insertNotice("공지");
    User pending =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));

    mockMvc
        .perform(Csrf.with(sessions.as(pending, post(NOTICES + "/" + noticeId + "/like"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
    assertThat(likes.findAll()).isEmpty();
  }

  /** 정지된 회원도 막힌다. */
  @Test
  void suspendedMemberIsBlocked() throws Exception {
    Long noticeId = insertNotice("공지");
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250004"));

    mockMvc
        .perform(Csrf.with(sessions.as(suspended, post(NOTICES + "/" + noticeId + "/like"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /**
   * <b>비활동 부원도 좋아요를 누를 수 있다</b> — 공지는 자료 갈래가 아니다(#228).
   *
   * <p>{@code RequesterCheck#requireActive}만 쓰고 {@code requireNoteAccess}를 쓰지 않는다는 계약이 여기서 지켜진다.
   */
  @Test
  void inactiveMemberCanLike() throws Exception {
    Long noticeId = insertNotice("공지");
    User inactive = userRepository.findById(me.getId()).orElseThrow();
    inactive.deactivate(Instant.now());
    userRepository.saveAndFlush(inactive);

    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndNoticeId(me.getId(), noticeId)).isTrue();
  }

  /* ------------------------------------------------------------------ 동시 */

  /** <b>동시에 눌러도 하나다.</b> 확인 후 저장 방식이면 겹친 요청이 모두 통과한다 — {@code BookmarkIntegrationTest}와 같은 이유. */
  @Test
  void concurrentLikesStayIdempotent() throws Exception {
    Long noticeId = insertNotice("공지");
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
                  mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());
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

  /** <b>동시에 떼도 모두 성공이다.</b> 읽고 지우면 겹친 두 요청 중 뒤의 것이 stale-state로 터진다. */
  @Test
  void concurrentUnlikesStayIdempotent() throws Exception {
    Long noticeId = insertNotice("공지");
    mockMvc.perform(like(me, noticeId)).andExpect(status().isNoContent());

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
                  mockMvc.perform(unlike(me, noticeId)).andExpect(status().isNoContent());
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
