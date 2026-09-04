package org.hackerkhu.hackerhp.domain.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.hackerkhu.hackerhp.domain.post.repository.PostCommentRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 자유 게시판 댓글 (#347, spec 2-1 §2-1-8, 3-2 §3-2-6, 3-3 결정 23).
 *
 * <p>수정·삭제 권한, CSRF, 상태 필터 재검증은 게시글({@code PostIntegrationTest})과 같은 계약을 그대로 물려받는다 — 여기서는 그 계약이 댓글
 * 경로에도 동일하게 적용되는지, 그리고 댓글만의 규칙(게시글 소속 확인, CASCADE 삭제)을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostCommentIntegrationTest extends AbstractIntegrationTest {

  private static final String POSTS = "/api/v1/posts";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;
  @Autowired private PostCommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private User member;
  private User admin;
  private Post post;

  @BeforeEach
  void setUp() {
    clearAll();
    member =
        userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001", "김부원"));
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200000"));
    post = postRepository.saveAndFlush(Post.write("글 제목", "글 본문", member.getId(), Instant.now()));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM post_comments");
    jdbcTemplate.update("DELETE FROM posts");
    userRepository.deleteAll();
  }

  private String commentsUrl(long postId) {
    return POSTS + "/" + postId + "/comments";
  }

  private MockHttpServletRequestBuilder writeRequest(User caller, long postId, String content) {
    return Csrf.with(sessions.as(caller, post(commentsUrl(postId))))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"content\":\"" + content + "\"}");
  }

  private MockHttpServletRequestBuilder editRequest(
      User caller, long postId, long commentId, String content) {
    return Csrf.with(sessions.as(caller, patch(commentsUrl(postId) + "/" + commentId)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"content\":\"" + content + "\"}");
  }

  private long write(User caller, long postId, String content) throws Exception {
    String json =
        mockMvc
            .perform(writeRequest(caller, postId, content))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).path("id").asLong();
  }

  /* ------------------------------------------------------------------ 기본 */

  /** 댓글을 쓰고 목록에서 본다. */
  @Test
  void aMemberWritesAndListsComments() throws Exception {
    long id = write(member, post.getId(), "첫 댓글");

    mockMvc
        .perform(sessions.as(member, get(commentsUrl(post.getId()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id))
        .andExpect(jsonPath("$[0].content").value("첫 댓글"))
        .andExpect(jsonPath("$[0].author.id").value(member.getId()))
        // 표시 이름이라 학번 끝 두 자리가 붙는다 (#301).
        .andExpect(jsonPath("$[0].author.name").value("김부원01"))
        .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
        .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
  }

  /** 댓글은 오래된 순이다 — 게시글 목록(최신순)과 반대다. */
  @Test
  void commentsAreOldestFirst() throws Exception {
    long first = write(member, post.getId(), "첫 댓글");
    long second = write(member, post.getId(), "둘째 댓글");
    long third = write(member, post.getId(), "셋째 댓글");

    mockMvc
        .perform(sessions.as(member, get(commentsUrl(post.getId()))))
        .andExpect(jsonPath("$[0].id").value(first))
        .andExpect(jsonPath("$[1].id").value(second))
        .andExpect(jsonPath("$[2].id").value(third));
  }

  /** 없는 게시글 아래에는 목록·등록 모두 {@code 404}다. */
  @Test
  void aMissingPostIsNotFoundForListAndWrite() throws Exception {
    mockMvc
        .perform(sessions.as(member, get(commentsUrl(999_999))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    mockMvc
        .perform(writeRequest(member, 999_999, "본문"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /* ------------------------------------------------------------------ 보안 */

  /** 본문은 손대지 않는다 — 게시글과 같은 이유(T-323). */
  @Test
  void htmlInTheBodyIsStoredAndReturnedVerbatim() throws Exception {
    String payload = "<script>alert(1)</script>";
    long id = write(member, post.getId(), payload);

    mockMvc
        .perform(sessions.as(member, get(commentsUrl(post.getId()))))
        .andExpect(jsonPath("$[0].content").value(payload));
    assertThat(commentRepository.findById(id).orElseThrow().getContent()).isEqualTo(payload);
  }

  /** 작성자는 세션에서만 온다 — 본문으로 보내도 무시된다. */
  @Test
  void theAuthorComesFromTheSessionNotTheBody() throws Exception {
    mockMvc
        .perform(
            Csrf.with(sessions.as(member, post(commentsUrl(post.getId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"본문\",\"authorId\":%d}".formatted(admin.getId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.author.id").value(member.getId()));
  }

  @Test
  void writingNeedsACsrfToken() throws Exception {
    mockMvc
        .perform(
            sessions
                .as(member, post(commentsUrl(post.getId())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"본문\"}"))
        .andExpect(status().isForbidden());
  }

  /* ------------------------------------------------------------------ 검증 */

  @Test
  void blankAndOversizedContentIsRejected() throws Exception {
    mockMvc.perform(writeRequest(member, post.getId(), "")).andExpect(status().isBadRequest());
    mockMvc.perform(writeRequest(member, post.getId(), "   ")).andExpect(status().isBadRequest());
    mockMvc
        .perform(writeRequest(member, post.getId(), "가".repeat(2_001)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(commentRepository.count()).isZero();
  }

  /** 상한 그 자체는 통과한다. */
  @Test
  void exactlyAtTheLimitIsAccepted() throws Exception {
    mockMvc
        .perform(writeRequest(member, post.getId(), "가".repeat(2_000)))
        .andExpect(status().isCreated());
  }

  /** 이모지는 코드포인트 한 글자다 — 게시글과 같은 이유(T-332·T-333). */
  @Test
  void aSupplementaryCharacterCountsAsOneLetter() throws Exception {
    mockMvc
        .perform(writeRequest(member, post.getId(), "🙂".repeat(2_000)))
        .andExpect(status().isCreated());
    mockMvc
        .perform(writeRequest(member, post.getId(), "🙂".repeat(2_001)))
        .andExpect(status().isBadRequest());
  }

  /* ------------------------------------------------------------------ 인가 */

  @Test
  void guestsGetNothing() throws Exception {
    mockMvc.perform(get(commentsUrl(post.getId()))).andExpect(status().isUnauthorized());
  }

  @Test
  void pendingAccountsGetNothing() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-ap", "ap@khu.ac.kr", "20250003"));

    mockMvc
        .perform(sessions.as(applicant, get(commentsUrl(post.getId()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  @Test
  void suspendedAccountsGetNothing() throws Exception {
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-sp", "sp@khu.ac.kr", "20250004"));

    mockMvc
        .perform(sessions.as(suspended, get(commentsUrl(post.getId()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /** 비활동 부원도 자료 외 기능은 그대로 쓰므로 댓글을 남길 수 있다 — 게시글과 같은 판단. */
  @Test
  void anInactiveMemberCanComment() throws Exception {
    User inactive = userRepository.findById(member.getId()).orElseThrow();
    inactive.deactivate(Instant.now());
    userRepository.saveAndFlush(inactive);

    mockMvc
        .perform(writeRequest(inactive, post.getId(), "비활동 중 댓글"))
        .andExpect(status().isCreated());
  }

  /** 필터를 지난 뒤 정지된 사람은 댓글을 남기지 못한다 — 게시글과 같은 이유. */
  @Test
  void anAuthorSuspendedAfterAuthorizationCannotFinishWriting() throws Exception {
    User target = userRepository.findById(member.getId()).orElseThrow();
    target.suspend();
    userRepository.saveAndFlush(target);

    mockMvc
        .perform(writeRequest(member, post.getId(), "정지 뒤 도착한 댓글"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));

    assertThat(commentRepository.count()).isZero();
  }

  /* ------------------------------------------------------------------ 수정 */

  /** 작성자 본인이 내용을 통째로 고친다. */
  @Test
  void authorEditsTheirOwnComment() throws Exception {
    long id = write(member, post.getId(), "원래 내용");
    var original = commentRepository.findById(id).orElseThrow();
    assertThat(original.getUpdatedAt()).isEqualTo(original.getCreatedAt());

    mockMvc
        .perform(editRequest(member, post.getId(), id, "고친 내용"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("고친 내용"))
        .andExpect(jsonPath("$.author.id").value(member.getId()));

    var edited = commentRepository.findById(id).orElseThrow();
    assertThat(edited.getCreatedAt()).isEqualTo(original.getCreatedAt());
    assertThat(edited.getUpdatedAt()).isNotEqualTo(edited.getCreatedAt());
  }

  /** 남의 댓글은 고칠 수 없다 — 예외 없음(3-3 결정 23 D2). */
  @Test
  void aMemberCannotEditSomeoneElsesComment() throws Exception {
    long id = write(member, post.getId(), "내용");
    User other =
        userRepository.saveAndFlush(Accounts.approved("sub-ot", "ot@khu.ac.kr", "20250002", "남"));

    mockMvc
        .perform(editRequest(other, post.getId(), id, "가로챈 내용"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(commentRepository.findById(id).orElseThrow().getContent()).isEqualTo("내용");
  }

  /** 관리자 역할도 수정 예외가 아니다 — 게시글과 같은 판단. */
  @Test
  void anAdminCannotEditSomeoneElsesCommentEither() throws Exception {
    long id = write(member, post.getId(), "내용");

    mockMvc
        .perform(editRequest(admin, post.getId(), id, "관리자가 고친 내용"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void editingAMissingCommentIsNotFound() throws Exception {
    mockMvc
        .perform(editRequest(member, post.getId(), 999_999, "내용"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /** 다른 글의 댓글 id를 넣으면 있어도 {@code 404}다. */
  @Test
  void editingACommentUnderTheWrongPostIsNotFound() throws Exception {
    Post otherPost =
        postRepository.saveAndFlush(Post.write("다른 글", "본문", member.getId(), Instant.now()));
    long id = write(member, post.getId(), "내용");

    mockMvc
        .perform(editRequest(member, otherPost.getId(), id, "가로챈 내용"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(commentRepository.findById(id).orElseThrow().getContent()).isEqualTo("내용");
  }

  @Test
  void editingNeedsACsrfToken() throws Exception {
    long id = write(member, post.getId(), "내용");

    mockMvc
        .perform(
            sessions
                .as(member, patch(commentsUrl(post.getId()) + "/" + id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"내용\"}"))
        .andExpect(status().isForbidden());
  }

  /* ------------------------------------------------------------------ 삭제 */

  /** 관리자가 댓글을 완전히 지운다. */
  @Test
  void adminDeletesAComment() throws Exception {
    long id = write(member, post.getId(), "지워질 댓글");

    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(commentsUrl(post.getId()) + "/" + id))))
        .andExpect(status().isNoContent());

    assertThat(commentRepository.existsById(id)).isFalse();
  }

  /** 작성자 본인도 자기 댓글을 지울 수 있다(3-3 결정 23 D2) — 게시글의 최종 삭제 모델을 그대로 물려받는다. */
  @Test
  void theAuthorDeletesTheirOwnComment() throws Exception {
    long id = write(member, post.getId(), "내 댓글");

    mockMvc
        .perform(Csrf.with(sessions.as(member, delete(commentsUrl(post.getId()) + "/" + id))))
        .andExpect(status().isNoContent());

    assertThat(commentRepository.existsById(id)).isFalse();
  }

  /** 일반 부원은 남의 댓글을 지울 수 없다. */
  @Test
  void aMemberCannotDeleteSomeoneElsesComment() throws Exception {
    long id = write(admin, post.getId(), "관리자 댓글");

    mockMvc
        .perform(Csrf.with(sessions.as(member, delete(commentsUrl(post.getId()) + "/" + id))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(commentRepository.existsById(id)).isTrue();
  }

  @Test
  void deletingAMissingCommentIsNotFound() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(commentsUrl(post.getId()) + "/999999"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /** 다른 글의 댓글 id를 넣으면 있어도 {@code 404}다. */
  @Test
  void deletingACommentUnderTheWrongPostIsNotFound() throws Exception {
    Post otherPost =
        postRepository.saveAndFlush(Post.write("다른 글", "본문", member.getId(), Instant.now()));
    long id = write(member, post.getId(), "내용");

    mockMvc
        .perform(Csrf.with(sessions.as(member, delete(commentsUrl(otherPost.getId()) + "/" + id))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(commentRepository.existsById(id)).isTrue();
  }

  @Test
  void deletingNeedsACsrfToken() throws Exception {
    long id = write(member, post.getId(), "내용");

    mockMvc
        .perform(sessions.as(admin, delete(commentsUrl(post.getId()) + "/" + id)))
        .andExpect(status().isForbidden());

    assertThat(commentRepository.existsById(id)).isTrue();
  }

  /** 세션이 아직 {@code ADMIN}이어도 DB에서 권한이 회수됐으면 삭제할 수 없다 — 게시글과 같은 재검증. */
  @Test
  void anAdminWhoseRoleWasRevokedAfterAuthorizationCannotDelete() throws Exception {
    long id = write(member, post.getId(), "남아야 할 댓글");
    SignedIn staleAdminSession = sessions.signIn(admin);
    User storedAdmin = userRepository.findById(admin.getId()).orElseThrow();
    storedAdmin.demoteToUser();
    userRepository.saveAndFlush(storedAdmin);

    mockMvc
        .perform(Csrf.with(staleAdminSession.on(delete(commentsUrl(post.getId()) + "/" + id))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(commentRepository.existsById(id)).isTrue();
  }

  /* ------------------------------------------------------------- 작성자 제거 */

  /** 댓글을 쓴 회원을 지울 수 있고, 그 댓글은 남는다 — 게시글과 같은 이유(2-2 §2-2-4). */
  @Test
  void removingTheAuthorKeepsTheCommentAndShowsWithdrawn() throws Exception {
    long id = write(member, post.getId(), "남을 댓글");

    mockMvc
        .perform(
            Csrf.with(
                sessions.as(
                    admin,
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/admin/users/" + member.getId()))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(admin, get(commentsUrl(post.getId()))))
        .andExpect(jsonPath("$[0].id").value(id))
        .andExpect(jsonPath("$[0].author.id").doesNotExist())
        .andExpect(jsonPath("$[0].author.name").value("탈퇴한 회원"));
  }

  /* ---------------------------------------------------------------- CASCADE */

  /** 게시글이 지워지면 댓글도 함께 지운다 (3-3 결정 23 D4). */
  @Test
  void deletingThePostCascadesToItsComments() throws Exception {
    long firstId = write(member, post.getId(), "첫 댓글");
    long secondId = write(admin, post.getId(), "둘째 댓글");

    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(POSTS + "/" + post.getId()))))
        .andExpect(status().isNoContent());

    assertThat(commentRepository.existsById(firstId)).isFalse();
    assertThat(commentRepository.existsById(secondId)).isFalse();
  }

  /** 다른 글의 댓글은 CASCADE의 영향을 받지 않는다. */
  @Test
  void deletingAPostDoesNotTouchAnotherPostsComments() throws Exception {
    Post otherPost =
        postRepository.saveAndFlush(Post.write("살아남을 글", "본문", member.getId(), Instant.now()));
    long survivingId = write(member, otherPost.getId(), "살아남을 댓글");

    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(POSTS + "/" + post.getId()))))
        .andExpect(status().isNoContent());

    assertThat(commentRepository.existsById(survivingId)).isTrue();
  }
}
