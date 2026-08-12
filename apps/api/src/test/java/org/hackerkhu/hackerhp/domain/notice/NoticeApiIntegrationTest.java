package org.hackerkhu.hackerhp.domain.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.AuthSession;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.session.InMemorySessionConfig;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** GET/POST/PATCH/DELETE /notices, PATCH /notices/{id}/pin — spec/3-2 §3-2-5. */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration")
@AutoConfigureMockMvc
@Import(InMemorySessionConfig.class)
class NoticeApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private NoticeRepository noticeRepository;
  @Autowired private JwtProvider jwtProvider;

  private User admin;
  private User member;

  @BeforeEach
  void createAccounts() {
    noticeRepository.deleteAll();
    userRepository.deleteAll();
    admin = userRepository.saveAndFlush(approvedAdmin("sub-admin", "admin@khu.ac.kr", "20240001"));
    member = userRepository.saveAndFlush(approved("sub-member", "member@khu.ac.kr", "20240002"));
  }

  @AfterEach
  void clear() {
    noticeRepository.deleteAll();
    userRepository.deleteAll();
  }

  private static User approved(String googleSub, String email, String studentNo) {
    User user = User.createFromGoogle(googleSub, email, "이름");
    user.submitApplication(studentNo, "본명");
    user.approve();
    return user;
  }

  private static User approvedAdmin(String googleSub, String email, String studentNo) {
    User user = approved(googleSub, email, studentNo);
    user.promoteToAdmin();
    return user;
  }

  private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder) {
    MockHttpSession session = new MockHttpSession();
    AuthSession.store(session, user);
    return builder
        .session(session)
        .cookie(new Cookie("ACCESS_TOKEN", jwtProvider.issue(user.getId())));
  }

  private MockHttpServletRequestBuilder write(
      User user, MockHttpServletRequestBuilder builder, String body) {
    return Csrf.with(as(user, builder)).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /** 같은 밀리초에 created_at이 찍히면 정렬 검증이 흔들린다. OS 타이머 해상도를 넘겨준다. */
  private static void sleepPastClockResolution() throws InterruptedException {
    Thread.sleep(20);
  }

  /*
   * 고정 공지가 먼저, 그 안에서는 최신순 — spec/2-1 §2-1-6 MUST.
   * created_at 순서만으로는 고정 여부가 드러나지 않으므로 일반 공지를 먼저 만들고
   * 나중에 고정 공지를 만들어, "고정이라서" 앞에 온다는 것을 구분해서 본다.
   */
  @Test
  void listReturnsPinnedFirstThenNewest() throws Exception {
    Notice older = noticeRepository.save(Notice.write("일반 공지", "내용1", admin));
    sleepPastClockResolution();
    Notice pinned = noticeRepository.save(Notice.write("고정 공지", "내용2", admin));
    pinned.pin();
    // save()는 저마다 독립 트랜잭션이다 — 반환된 엔티티는 그 트랜잭션이 끝나며 detach된다.
    // pin() 이후의 변경을 실제로 저장하려면 다시 save()해야 한다.
    noticeRepository.save(pinned);
    sleepPastClockResolution();
    Notice newer = noticeRepository.save(Notice.write("최신 공지", "내용3", admin));
    noticeRepository.flush();

    mockMvc
        .perform(as(member, get("/api/v1/notices")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(pinned.getId()))
        .andExpect(jsonPath("$.content[0].isPinned").value(true))
        .andExpect(jsonPath("$.content[1].id").value(newer.getId()))
        .andExpect(jsonPath("$.content[2].id").value(older.getId()))
        .andExpect(jsonPath("$.page.totalElements").value(3));
  }

  @Test
  void getReturnsNoticeFields() throws Exception {
    Notice notice = noticeRepository.saveAndFlush(Notice.write("제목", "본문", admin));

    mockMvc
        .perform(as(member, get("/api/v1/notices/{id}", notice.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("제목"))
        .andExpect(jsonPath("$.content").value("본문"))
        .andExpect(jsonPath("$.isPinned").value(false));
  }

  @Test
  void getWithUnknownIdReturns404() throws Exception {
    mockMvc
        .perform(as(member, get("/api/v1/notices/{id}", 999_999L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /* author_id는 요청 본문이 아니라 인증 주체로 정한다 — spec 3-2 §3-2-5, 이슈 #33 완료 조건. */
  @Test
  void adminCanCreateNoticeAndAuthorIsRecorded() throws Exception {
    mockMvc
        .perform(write(admin, post("/api/v1/notices"), "{\"title\":\"공지\",\"content\":\"내용\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("공지"))
        .andExpect(jsonPath("$.content").value("내용"))
        .andExpect(jsonPath("$.isPinned").value(false));

    assertThat(noticeRepository.findAll()).hasSize(1);
    assertThat(noticeRepository.findAll().get(0).getAuthor().getId()).isEqualTo(admin.getId());
  }

  @Test
  void memberCannotCreateNotice() throws Exception {
    mockMvc
        .perform(write(member, post("/api/v1/notices"), "{\"title\":\"공지\",\"content\":\"내용\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void createWithBlankTitleReturnsValidationError() throws Exception {
    mockMvc
        .perform(write(admin, post("/api/v1/notices"), "{\"title\":\"   \",\"content\":\"내용\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void adminCanUpdateNotice() throws Exception {
    Notice notice = noticeRepository.saveAndFlush(Notice.write("원래 제목", "원래 내용", admin));

    mockMvc
        .perform(
            write(
                admin,
                patch("/api/v1/notices/{id}", notice.getId()),
                "{\"title\":\"바뀐 제목\",\"content\":\"바뀐 내용\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("바뀐 제목"))
        .andExpect(jsonPath("$.content").value("바뀐 내용"));
  }

  @Test
  void updateWithUnknownIdReturns404() throws Exception {
    mockMvc
        .perform(
            write(
                admin,
                patch("/api/v1/notices/{id}", 999_999L),
                "{\"title\":\"제목\",\"content\":\"내용\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void memberCannotUpdateNotice() throws Exception {
    Notice notice = noticeRepository.saveAndFlush(Notice.write("제목", "내용", admin));

    mockMvc
        .perform(
            write(
                member,
                patch("/api/v1/notices/{id}", notice.getId()),
                "{\"title\":\"바뀐 제목\",\"content\":\"바뀐 내용\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanDeleteNotice() throws Exception {
    Notice notice = noticeRepository.saveAndFlush(Notice.write("제목", "내용", admin));

    mockMvc
        .perform(Csrf.with(as(admin, delete("/api/v1/notices/{id}", notice.getId()))))
        .andExpect(status().isNoContent());

    assertThat(noticeRepository.existsById(notice.getId())).isFalse();
  }

  @Test
  void deleteWithUnknownIdReturns404() throws Exception {
    mockMvc
        .perform(Csrf.with(as(admin, delete("/api/v1/notices/{id}", 999_999L))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void memberCannotDeleteNotice() throws Exception {
    Notice notice = noticeRepository.saveAndFlush(Notice.write("제목", "내용", admin));

    mockMvc
        .perform(Csrf.with(as(member, delete("/api/v1/notices/{id}", notice.getId()))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanToggleNoticePinOn() throws Exception {
    Notice notice = noticeRepository.saveAndFlush(Notice.write("제목", "내용", admin));

    mockMvc
        .perform(Csrf.with(as(admin, patch("/api/v1/notices/{id}/pin", notice.getId()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isPinned").value(true));
  }

  @Test
  void adminCanToggleNoticePinOff() throws Exception {
    Notice notice = noticeRepository.save(Notice.write("제목", "내용", admin));
    notice.pin();
    noticeRepository.saveAndFlush(notice);

    mockMvc
        .perform(Csrf.with(as(admin, patch("/api/v1/notices/{id}/pin", notice.getId()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isPinned").value(false));
  }

  @Test
  void togglePinWithUnknownIdReturns404() throws Exception {
    mockMvc
        .perform(Csrf.with(as(admin, patch("/api/v1/notices/{id}/pin", 999_999L))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void memberCannotTogglePin() throws Exception {
    Notice notice = noticeRepository.saveAndFlush(Notice.write("제목", "내용", admin));

    mockMvc
        .perform(Csrf.with(as(member, patch("/api/v1/notices/{id}/pin", notice.getId()))))
        .andExpect(status().isForbidden());
  }
}
