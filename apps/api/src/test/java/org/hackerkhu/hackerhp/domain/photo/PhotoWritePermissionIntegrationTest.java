package org.hackerkhu.hackerhp.domain.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.photo.entity.Photo;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 활동사진 쓰기 권한 (T-604 ~ T-612, spec 2-1 §2-1-7 · 3-1 §3-1-3 · 3-3 결정 30, #400).
 *
 * <p><b>업로드는 부원 전체, 삭제는 본인 것만</b>이다. 소모임장이 사진을 올릴 수 있게 하려고 연 것이고, 그 하나 때문에 관리자 권한을 주는 것은 너무 크다.
 *
 * <p><b>삭제가 이 절의 핵심이다.</b> 업로드를 열면서 삭제를 함께 열면 아무나 남의 사진을 지운다 — 그 조건은 역할이 아니라 소유자라
 * {@code @PreAuthorize}로 적을 수 없고, 빠뜨려도 "올릴 수 있나"만 보는 사례는 전부 통과한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStorageConfig.class)
class PhotoWritePermissionIntegrationTest extends AbstractIntegrationTest {

  private static final String PHOTOS = "/api/v1/photos";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private PhotoRepository photoRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User member;
  private User other;
  private User admin;

  @BeforeEach
  void setUp() {
    clearAll();
    member = userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    other = userRepository.saveAndFlush(Accounts.approved("sub-o", "o@khu.ac.kr", "20250002"));
    admin = userRepository.saveAndFlush(Accounts.admin("sub-a", "a@khu.ac.kr", "20200000"));
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

  /* ------------------------------------------------------------------ 도구 */

  /** 완결된 사진 행을 곧바로 만든다 — 소유자 판단을 보는 자리라 등록 흐름 전체를 거칠 이유가 없다. */
  private Photo photoOf(User uploader) {
    Photo saved =
        photoRepository.saveAndFlush(Photo.upload(null, "photos/uploads/temp.jpg", uploader));
    saved.assignStoredPath("photos/" + saved.getId() + "/final.jpg");
    return photoRepository.saveAndFlush(saved);
  }

  private MockHttpServletRequestBuilder issueUploadUrl(User caller) {
    return Csrf.with(sessions.as(caller, post(PHOTOS + "/upload-url")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"extensions\":[\"jpg\"]}");
  }

  private MockHttpServletRequestBuilder removePhoto(User caller, Long id) {
    return Csrf.with(sessions.as(caller, delete(PHOTOS + "/" + id)));
  }

  /* ------------------------------------------------------------------ 업로드 */

  /**
   * T-604. <b>일반 부원이 올릴 수 있다</b> (MUST).
   *
   * <p>발급 경로를 본다 — 여기가 막히면 나머지 단계에 닿지도 못한다. 예전에는 {@code SecurityConfig}가 필터 단에서 {@code ADMIN}을 강제해
   * 컨트롤러에 닿기도 전에 거절했다.
   */
  @Test
  void aPlainMemberCanIssueUploadUrls() throws Exception {
    mockMvc
        .perform(issueUploadUrl(member))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].uploadUrl").isNotEmpty());
  }

  /** T-605. {@code INACTIVE}도 올릴 수 있다 — 활동사진은 자료 갈래가 아니다 (#228). */
  @Test
  void anInactiveMemberCanIssueUploadUrls() throws Exception {
    User resting =
        userRepository.saveAndFlush(Accounts.inactive("sub-i", "i@khu.ac.kr", "20240001"));

    mockMvc.perform(issueUploadUrl(resting)).andExpect(status().isOk());
  }

  /** T-606. 쓸 수 없는 계정은 각자의 코드로 막힌다. */
  @Test
  void pendingAndSuspendedAreRejected() throws Exception {
    User waiting =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20260001"));
    User banned =
        userRepository.saveAndFlush(Accounts.suspended("sub-s", "s@khu.ac.kr", "20230001"));

    mockMvc
        .perform(issueUploadUrl(waiting))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
    mockMvc
        .perform(issueUploadUrl(banned))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /* ------------------------------------------------------------------ 삭제 */

  /** T-607. 올린 사람이 자기 사진을 지운다. */
  @Test
  void theUploaderDeletesTheirOwnPhoto() throws Exception {
    Photo mine = photoOf(member);

    mockMvc.perform(removePhoto(member, mine.getId())).andExpect(status().isNoContent());

    assertThat(photoRepository.findById(mine.getId())).isEmpty();
  }

  /**
   * T-608. <b>남의 사진은 못 지운다</b> (MUST).
   *
   * <p>이 절의 핵심이다. 업로드를 열면서 소유자 판단을 빠뜨리면 <b>아무나 남의 사진을 지운다</b> — 그런데 T-604·T-607은 그대로 통과한다.
   */
  @Test
  void aPlainMemberCannotDeleteSomeoneElsesPhoto() throws Exception {
    Photo theirs = photoOf(other);

    mockMvc
        .perform(removePhoto(member, theirs.getId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(photoRepository.findById(theirs.getId())).as("사진은 그대로 남는다").isPresent();
  }

  /** T-609. {@code ADMIN}은 남의 사진도 지운다. */
  @Test
  void anAdminDeletesAnyPhoto() throws Exception {
    Photo theirs = photoOf(other);

    mockMvc.perform(removePhoto(admin, theirs.getId())).andExpect(status().isNoContent());

    assertThat(photoRepository.findById(theirs.getId())).isEmpty();
  }

  /**
   * T-610. <b>업로더가 비어 있는 사진은 {@code ADMIN}만 지운다.</b>
   *
   * <p>탈퇴한 회원이 올린 사진이다 — 주인이 없으므로 "본인"이 성립하지 않는다 (3-2 §3-2-4의 자료 규칙과 같다).
   *
   * <p>T-608이 못 보는 자리다. 소유자 비교를 널 처리 없이 적으면 <b>둘 다 비었을 때 통과</b>하거나, 반대로 아무도 못 지우게 된다.
   */
  @Test
  void onlyAnAdminDeletesAPhotoWithNoUploader() throws Exception {
    Photo orphan = photoOf(null);

    mockMvc
        .perform(removePhoto(member, orphan.getId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mockMvc.perform(removePhoto(admin, orphan.getId())).andExpect(status().isNoContent());

    assertThat(photoRepository.findById(orphan.getId())).isEmpty();
  }

  /**
   * T-611. <b>정지되면 대기 중이던 쓰기도 거절된다</b> (MUST).
   *
   * <p>{@code @PreAuthorize}는 세션 값을 본다 — 필터를 통과한 요청이 잠금을 기다리는 사이에 관리자가 정지시킬 수 있다. 조건이 {@code
   * ADMIN}에서 "쓸 수 있는 계정"으로 느슨해졌을 뿐 <b>그 창은 그대로 있다.</b>
   *
   * <p>여기서는 그 창을 세션과 계정 행을 어긋나게 만들어 재현한다 — 세션은 살아 있고 계정만 정지된 상태다.
   */
  @Test
  void aSuspensionDuringTheRequestStillBlocksTheWrite() throws Exception {
    Photo mine = photoOf(member);
    var signedIn = sessions.signIn(member);
    jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", member.getId());

    mockMvc
        .perform(Csrf.with(signedIn.on(delete(PHOTOS + "/" + mine.getId()))))
        .andExpect(status().isForbidden());

    assertThat(photoRepository.findById(mine.getId())).as("지워지지 않는다").isPresent();
  }

  /**
   * T-612. <b>좋아요 경로는 그대로다</b> (T-571과 한 벌).
   *
   * <p>#400이 {@code SecurityConfig}의 사진 쓰기 매처를 걷어냈다. 나중에 그 자리에 새 규칙을 {@code /photos/**}로 더하면 좋아요가
   * 다시 막힌다 — 그 함정은 매처가 사라졌다고 없어지지 않는다.
   */
  @Test
  void likingStillWorks() throws Exception {
    Photo theirs = photoOf(other);

    mockMvc
        .perform(Csrf.with(sessions.as(member, post(PHOTOS + "/" + theirs.getId() + "/like"))))
        .andExpect(status().isNoContent());
  }
}
