package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.note.repository.BookmarkRepository;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>확인과 삽입 사이에 자료가 지워지면</b> (#189 리뷰).
 *
 * <p>{@code ON CONFLICT DO NOTHING}은 중복만 넘기고 FK 위반은 그대로 올린다. 그냥 두면 {@code 500}이 나가지만, 사용자에게 일어난 일은
 * <b>"그 자료가 없다"</b>이므로 계약대로 {@code 404}여야 한다.
 *
 * <p>그 창은 실제로는 아주 좁아 손으로 맞출 수 없다. 그래서 <b>존재 확인만 거짓으로 통과시키고</b> 삽입이 실제 FK에 걸리게 한다 — 확인 뒤 삭제가 커밋된 상태와
 * DB가 보는 것이 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookmarkVanishedNoteIntegrationTest extends AbstractIntegrationTest {

  private static final long MISSING_NOTE_ID = 999_999L;

  @MockitoBean private NoteRepository notes;

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private BookmarkRepository bookmarks;

  private User me;

  @BeforeEach
  void setUp() {
    bookmarks.deleteAll();
    userRepository.deleteAll();
    me = userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001"));
    // 확인 시점에는 있었다. 그 뒤 삭제가 커밋된 상황이다.
    given(notes.existsById(any())).willReturn(true);
  }

  @AfterEach
  void clear() {
    bookmarks.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void bookmarkingAVanishedNoteIsNotFoundNotAServerError() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(me, post("/api/v1/notes/" + MISSING_NOTE_ID + "/bookmark"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(bookmarks.findAll()).isEmpty();
  }
}
