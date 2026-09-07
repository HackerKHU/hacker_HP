package org.hackerkhu.hackerhp.domain.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.hackerkhu.hackerhp.domain.post.repository.PostLikeRepository;
import org.hackerkhu.hackerhp.domain.post.repository.PostRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 자유 게시판 좋아요 (#345, spec 2-1 §2-1-8, 3-2 §3-2-5, 3-3 결정 26).
 *
 * <p>멱등성·동시성 계약은 즐겨찾기·공지 좋아요(#343)와 같다 — 여기서는 게시판만의 차이(`INACTIVE`도 열림 — 자료 좋아요와 정반대, 삭제된 글의 좋아요
 * CASCADE)를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostLikeIntegrationTest extends AbstractIntegrationTest {

  private static final String POSTS = "/api/v1/posts";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;
  @Autowired private PostLikeRepository likes;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User me;
  private User other;
  private Post post;

  @BeforeEach
  void setUp() {
    clearAll();
    me = userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001"));
    other = userRepository.saveAndFlush(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));
    post = postRepository.saveAndFlush(Post.write("글 제목", "글 본문", me.getId(), Instant.now()));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM post_likes");
    jdbcTemplate.update("DELETE FROM post_comments");
    jdbcTemplate.update("DELETE FROM posts");
    userRepository.deleteAll();
  }

  private MockHttpServletRequestBuilder like(User caller, Long postId) {
    return Csrf.with(sessions.as(caller, post(POSTS + "/" + postId + "/like")));
  }

  private MockHttpServletRequestBuilder unlike(User caller, Long postId) {
    return Csrf.with(sessions.as(caller, delete(POSTS + "/" + postId + "/like")));
  }

  /* ------------------------------------------------------------------ 누르기 */

  @Test
  void likesAPost() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndPostId(me.getId(), post.getId())).isTrue();
  }

  /** <b>이미 눌렀어도 성공이다.</b> 두 번 눌러도 좋아요는 하나다 — 토글이 아니다. */
  @Test
  void likingTwiceStaysAsOneLike() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    assertThat(likes.findAll()).hasSize(1);
  }

  /** 없는 게시글은 {@code 404}다. */
  @Test
  void likingAMissingPostIsNotFound() throws Exception {
    mockMvc
        .perform(like(me, 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 취소 */

  @Test
  void unlikesAPost() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, post.getId())).andExpect(status().isNoContent());

    assertThat(likes.findAll()).isEmpty();
  }

  /** 눌러져 있지 않아도, 없는 게시글이어도 성공이다. */
  @Test
  void unlikingWhatIsNotThereSucceeds() throws Exception {
    mockMvc.perform(unlike(me, post.getId())).andExpect(status().isNoContent());
    mockMvc.perform(unlike(me, 999_999L)).andExpect(status().isNoContent());
  }

  /** 남의 좋아요를 건드리지 않는다. */
  @Test
  void unlikingDoesNotTouchSomeoneElse() throws Exception {
    mockMvc.perform(like(other, post.getId())).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, post.getId())).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndPostId(other.getId(), post.getId())).isTrue();
  }

  /* --------------------------------------------------------- 목록·상세 표시 */

  /** <b>목록·상세가 개수와 내가 눌렀는지를 함께 보여준다</b> (#345 완료 조건). */
  @Test
  void listAndDetailShowLikeCountAndWhetherILiked() throws Exception {
    mockMvc
        .perform(sessions.as(me, get(POSTS)))
        .andExpect(jsonPath("$.content[0].likeCount").value(0))
        .andExpect(jsonPath("$.content[0].likedByMe").value(false));
    mockMvc
        .perform(sessions.as(me, get(POSTS + "/" + post.getId())))
        .andExpect(jsonPath("$.likeCount").value(0))
        .andExpect(jsonPath("$.likedByMe").value(false));

    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());
    mockMvc.perform(like(other, post.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(POSTS)))
        .andExpect(jsonPath("$.content[0].likeCount").value(2))
        .andExpect(jsonPath("$.content[0].likedByMe").value(true));
    mockMvc
        .perform(sessions.as(me, get(POSTS + "/" + post.getId())))
        .andExpect(jsonPath("$.likeCount").value(2))
        .andExpect(jsonPath("$.likedByMe").value(true));
  }

  /** <b>남이 누른 것은 내 좋아요가 아니다.</b> */
  @Test
  void someoneElsesLikeDoesNotShowAsMine() throws Exception {
    mockMvc.perform(like(other, post.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(POSTS)))
        .andExpect(jsonPath("$.content[0].likeCount").value(1))
        .andExpect(jsonPath("$.content[0].likedByMe").value(false));
  }

  /** 수정해도 좋아요 개수·내 상태는 그대로다. */
  @Test
  void editingAPostDoesNotAffectItsLikes() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(
            Csrf.with(sessions.as(me, patch(POSTS + "/" + post.getId())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"고친 제목\",\"content\":\"고친 본문\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.likedByMe").value(true));
  }

  /* ------------------------------------------- 내가 좋아요한 글 (#355) */

  /** <b>{@code liked=true}는 내가 누른 글만 준다</b> (#355 완료 조건). */
  @Test
  void likedReturnsOnlyWhatILiked() throws Exception {
    Post another =
        postRepository.saveAndFlush(Post.write("남이 누른 글", "본문", me.getId(), Instant.now()));
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());
    mockMvc.perform(like(other, another.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(POSTS)).param("liked", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(post.getId()));
  }

  /** <b>좋아요를 취소하면 이 목록에서도 빠진다</b> (#355 완료 조건). */
  @Test
  void unlikingRemovesItFromTheLikedList() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, post.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(POSTS)).param("liked", "true"))
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  /** <b>한 글을 여럿이 눌러도 한 번만 나온다</b> — 조인으로 짜면 좋아요 수만큼 중복된다. */
  @Test
  void likedDoesNotDuplicateWhenManyPeopleLikedIt() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());
    mockMvc.perform(like(other, post.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(POSTS)).param("liked", "true"))
        .andExpect(jsonPath("$.page.totalElements").value(1));
  }

  /** <b>{@code mine}과 함께 걸면 AND다</b> — 내가 쓰고 내가 누른 글만 남는다 (#353·#355). */
  @Test
  void likedAndMineCombineWithAnd() throws Exception {
    Post othersPost =
        postRepository.saveAndFlush(Post.write("남이 쓴 글", "본문", other.getId(), Instant.now()));
    // 내 글에는 좋아요를 누르지 않고, 남의 글에만 누른다.
    mockMvc.perform(like(me, othersPost.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(POSTS)).param("mine", "true").param("liked", "true"))
        .andExpect(jsonPath("$.page.totalElements").value(0));

    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(POSTS)).param("mine", "true").param("liked", "true"))
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(post.getId()));
  }

  /* --------------------------------------------------------------- CASCADE */

  /** 게시글이 지워지면 좋아요도 함께 사라진다 (완료 조건, {@code ON DELETE CASCADE}). */
  @Test
  void deletingAPostRemovesItsLikes() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    jdbcTemplate.update("DELETE FROM posts WHERE id = ?", post.getId());

    assertThat(likes.findAll()).isEmpty();
  }

  /** 좋아요를 누른 회원이 지워지면 그 좋아요도 함께 사라진다. */
  @Test
  void removingAUserRemovesTheirLikes() throws Exception {
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    userRepository.deleteById(me.getId());

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 권한 */

  @Test
  void requiresAuthentication() throws Exception {
    mockMvc
        .perform(Csrf.with(post(POSTS + "/" + post.getId() + "/like")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pendingMemberIsBlocked() throws Exception {
    User pending =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));

    mockMvc
        .perform(Csrf.with(sessions.as(pending, post(POSTS + "/" + post.getId() + "/like"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
    assertThat(likes.findAll()).isEmpty();
  }

  @Test
  void suspendedMemberIsBlocked() throws Exception {
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250004"));

    mockMvc
        .perform(Csrf.with(sessions.as(suspended, post(POSTS + "/" + post.getId() + "/like"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /**
   * <b>비활동 부원도 좋아요를 누를 수 있다</b> — 게시판은 자료 갈래가 아니다(#228).
   *
   * <p>{@code PostLikeService}가 {@code RequesterCheck#requireActive}만 쓴다는 계약이 여기서 지켜진다. 자료
   * 좋아요(#344)와 정반대다.
   */
  @Test
  void inactiveMemberCanLike() throws Exception {
    User inactive = userRepository.findById(me.getId()).orElseThrow();
    inactive.deactivate(Instant.now());
    userRepository.saveAndFlush(inactive);

    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndPostId(me.getId(), post.getId())).isTrue();
  }

  /* ------------------------------------------------------------------ 동시 */

  /** <b>동시에 눌러도 하나다.</b> */
  @Test
  void concurrentLikesStayIdempotent() throws Exception {
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
                  mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());
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
    mockMvc.perform(like(me, post.getId())).andExpect(status().isNoContent());

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
                  mockMvc.perform(unlike(me, post.getId())).andExpect(status().isNoContent());
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
