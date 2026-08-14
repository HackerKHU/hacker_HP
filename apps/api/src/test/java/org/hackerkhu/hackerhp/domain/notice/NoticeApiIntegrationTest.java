package org.hackerkhu.hackerhp.domain.notice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** GET /notices, GET /notices/{id} — spec/3-2 §3-2-5. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NoticeApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @PersistenceContext private EntityManager entityManager;

  /*
   * 고정 공지가 먼저, 그 안에서는 최신순 — spec/2-1 §2-1-6 MUST.
   * created_at 순서만으로는 고정 여부가 드러나지 않으므로 일반 공지를 먼저 만들고
   * 나중에 고정 공지를 만들어, "고정이라서" 앞에 온다는 것을 구분해서 본다.
   */
  @Test
  @WithMockUser
  void listReturnsPinnedFirstThenNewest() throws Exception {
    User author = persistAuthor("author-1");
    Notice older = persist(Notice.write("일반 공지", "내용1", author));
    sleepPastClockResolution();
    Notice pinned = persist(Notice.write("고정 공지", "내용2", author));
    pinned.pin();
    sleepPastClockResolution();
    Notice newer = persist(Notice.write("최신 공지", "내용3", author));
    entityManager.flush();

    mockMvc
        .perform(get("/api/v1/notices"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(pinned.getId()))
        .andExpect(jsonPath("$.content[0].isPinned").value(true))
        .andExpect(jsonPath("$.content[1].id").value(newer.getId()))
        .andExpect(jsonPath("$.content[2].id").value(older.getId()))
        .andExpect(jsonPath("$.page.totalElements").value(3));
  }

  @Test
  @WithMockUser
  void getReturnsNoticeFields() throws Exception {
    User author = persistAuthor("author-2");
    Notice notice = persist(Notice.write("제목", "본문", author));
    entityManager.flush();

    mockMvc
        .perform(get("/api/v1/notices/{id}", notice.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("제목"))
        .andExpect(jsonPath("$.content").value("본문"))
        .andExpect(jsonPath("$.isPinned").value(false));
  }

  @Test
  @WithMockUser
  void getWithUnknownIdReturns404() throws Exception {
    mockMvc
        .perform(get("/api/v1/notices/{id}", 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /** 같은 밀리초에 created_at이 찍히면 정렬 검증이 흔들린다. OS 타이머 해상도를 넘겨준다. */
  private void sleepPastClockResolution() throws InterruptedException {
    Thread.sleep(20);
  }

  private User persistAuthor(String googleSub) {
    User author = User.createFromGoogle(googleSub, googleSub + "@khu.ac.kr", "테스트");
    entityManager.persist(author);
    return author;
  }

  private Notice persist(Notice notice) {
    entityManager.persist(notice);
    return notice;
  }
}
