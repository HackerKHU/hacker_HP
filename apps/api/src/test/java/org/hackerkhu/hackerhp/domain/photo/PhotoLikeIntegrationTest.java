package org.hackerkhu.hackerhp.domain.photo;

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
import org.hackerkhu.hackerhp.domain.photo.entity.Photo;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoLikeRepository;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.storage.FakeStorageConfig;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 활동사진 좋아요 (#346, spec 2-1 §2-1-7, 3-2 §3-2-5, 3-3 결정 27).
 *
 * <p>멱등성·동시성 계약은 즐겨찾기·공지 좋아요와 같다 — 여기서는 사진만의 차이(`INACTIVE`도 열림 — 공지·게시판 좋아요와 같은 결론, 삭제된 사진의
 * CASCADE)를 본다. 진짜 S3 왕복이 필요 없으므로(좋아요는 리사이즈·업로드를 건드리지 않는다) {@link FakeStorageConfig}로 가볍게 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStorageConfig.class)
class PhotoLikeIntegrationTest extends AbstractIntegrationTest {

  private static final String PHOTOS = "/api/v1/photos";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private PhotoRepository photoRepository;
  @Autowired private PhotoLikeRepository likes;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User me;
  private User other;
  private User admin;
  private Photo photo;

  @BeforeEach
  void setUp() {
    clearAll();
    me = userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001"));
    other = userRepository.saveAndFlush(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200000"));
    photo = insertPhoto();
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM photo_likes");
    jdbcTemplate.update("DELETE FROM photos");
    userRepository.deleteAll();
  }

  /** 완결된(임시 키가 아닌) 사진 행을 곧바로 만든다 — 좋아요는 리사이즈·S3 왕복을 건드리지 않으므로 등록 흐름 전체를 거칠 이유가 없다. */
  private Photo insertPhoto() {
    Photo saved =
        photoRepository.saveAndFlush(Photo.upload(null, "photos/uploads/temp.jpg", admin));
    saved.assignStoredPath("photos/" + saved.getId() + "/final.jpg");
    return photoRepository.saveAndFlush(saved);
  }

  private MockHttpServletRequestBuilder like(User caller, Long photoId) {
    return Csrf.with(sessions.as(caller, post(PHOTOS + "/" + photoId + "/like")));
  }

  private MockHttpServletRequestBuilder unlike(User caller, Long photoId) {
    return Csrf.with(sessions.as(caller, delete(PHOTOS + "/" + photoId + "/like")));
  }

  /* ------------------------------------------------------------------ 누르기 */

  @Test
  void likesAPhoto() throws Exception {
    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndPhotoId(me.getId(), photo.getId())).isTrue();
  }

  /** <b>이미 눌렀어도 성공이다.</b> 두 번 눌러도 좋아요는 하나다 — 토글이 아니다. */
  @Test
  void likingTwiceStaysAsOneLike() throws Exception {
    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());
    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());

    assertThat(likes.findAll()).hasSize(1);
  }

  /** 없는 사진은 {@code 404}다. */
  @Test
  void likingAMissingPhotoIsNotFound() throws Exception {
    mockMvc
        .perform(like(me, 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 취소 */

  @Test
  void unlikesAPhoto() throws Exception {
    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, photo.getId())).andExpect(status().isNoContent());

    assertThat(likes.findAll()).isEmpty();
  }

  /** 눌러져 있지 않아도, 없는 사진이어도 성공이다. */
  @Test
  void unlikingWhatIsNotThereSucceeds() throws Exception {
    mockMvc.perform(unlike(me, photo.getId())).andExpect(status().isNoContent());
    mockMvc.perform(unlike(me, 999_999L)).andExpect(status().isNoContent());
  }

  /** 남의 좋아요를 건드리지 않는다. */
  @Test
  void unlikingDoesNotTouchSomeoneElse() throws Exception {
    mockMvc.perform(like(other, photo.getId())).andExpect(status().isNoContent());

    mockMvc.perform(unlike(me, photo.getId())).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndPhotoId(other.getId(), photo.getId())).isTrue();
  }

  /* --------------------------------------------------------- 목록 표시 */

  /** <b>목록이 개수와 내가 눌렀는지를 함께 보여준다</b> (#346 완료 조건). */
  @Test
  void listShowsLikeCountAndWhetherILiked() throws Exception {
    mockMvc
        .perform(sessions.as(me, get(PHOTOS)))
        .andExpect(jsonPath("$.content[0].likeCount").value(0))
        .andExpect(jsonPath("$.content[0].likedByMe").value(false));

    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());
    mockMvc.perform(like(other, photo.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(PHOTOS)))
        .andExpect(jsonPath("$.content[0].likeCount").value(2))
        .andExpect(jsonPath("$.content[0].likedByMe").value(true));
  }

  /** <b>남이 누른 것은 내 좋아요가 아니다.</b> */
  @Test
  void someoneElsesLikeDoesNotShowAsMine() throws Exception {
    mockMvc.perform(like(other, photo.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(me, get(PHOTOS)))
        .andExpect(jsonPath("$.content[0].likeCount").value(1))
        .andExpect(jsonPath("$.content[0].likedByMe").value(false));
  }

  /* --------------------------------------------------------------- CASCADE */

  /** 사진이 지워지면 좋아요도 함께 사라진다 (완료 조건, {@code ON DELETE CASCADE}). */
  @Test
  void deletingAPhotoRemovesItsLikes() throws Exception {
    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());

    jdbcTemplate.update("DELETE FROM photos WHERE id = ?", photo.getId());

    assertThat(likes.findAll()).isEmpty();
  }

  /** 좋아요를 누른 회원이 지워지면 그 좋아요도 함께 사라진다. */
  @Test
  void removingAUserRemovesTheirLikes() throws Exception {
    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());

    userRepository.deleteById(me.getId());

    assertThat(likes.findAll()).isEmpty();
  }

  /* ------------------------------------------------------------------ 권한 */

  @Test
  void requiresAuthentication() throws Exception {
    mockMvc
        .perform(Csrf.with(post(PHOTOS + "/" + photo.getId() + "/like")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pendingMemberIsBlocked() throws Exception {
    User pending =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));

    mockMvc
        .perform(Csrf.with(sessions.as(pending, post(PHOTOS + "/" + photo.getId() + "/like"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
    assertThat(likes.findAll()).isEmpty();
  }

  @Test
  void suspendedMemberIsBlocked() throws Exception {
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250004"));

    mockMvc
        .perform(Csrf.with(sessions.as(suspended, post(PHOTOS + "/" + photo.getId() + "/like"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /**
   * <b>비활동 부원도 좋아요를 누를 수 있다</b> — 활동사진은 자료 갈래가 아니다(#228).
   *
   * <p>{@code PhotoLikeService}가 {@code RequesterCheck#requireActive}만 쓴다는 계약이 여기서 지켜진다. 자료
   * 좋아요(#344)와 정반대다.
   */
  @Test
  void inactiveMemberCanLike() throws Exception {
    User inactive = userRepository.findById(me.getId()).orElseThrow();
    inactive.deactivate(Instant.now());
    userRepository.saveAndFlush(inactive);

    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());

    assertThat(likes.existsByUserIdAndPhotoId(me.getId(), photo.getId())).isTrue();
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
                  mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());
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
    mockMvc.perform(like(me, photo.getId())).andExpect(status().isNoContent());

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
                  mockMvc.perform(unlike(me, photo.getId())).andExpect(status().isNoContent());
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
