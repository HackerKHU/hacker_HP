package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.storage.FakeFileStorage;
import org.hackerkhu.testsupport.storage.FakeStorageConfig;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>등록이 무산되면 옮겨 둔 파일을 도로 지운다</b> (#53).
 *
 * <p>파일은 DB에 저장하기 <b>전에</b> 최종 자리로 옮겨진다. 그 뒤 저장이 실패하면 그 파일은 <b>아무도 지울 수 없는 것</b>이 된다 — 최종 자리에는 만료
 * 규칙이 없고(자료는 오래 남아야 한다), DB에 행이 없으니 찾을 실마리도 없다.
 *
 * <p>DB 실패를 실제로 만들 수 없으므로 <b>저장 자리만 갈아끼운다.</b> 확인하려는 것은 저장 실패의 원인이 아니라 <b>그 뒤에 무엇이 남는가</b>다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStorageConfig.class)
class NoteCreateRollbackIntegrationTest extends AbstractIntegrationTest {

  private static final String NOTES = "/api/v1/notes";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private FakeFileStorage storage;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private NoteRepository noteRepository;

  private User me;

  @BeforeEach
  void setUp() {
    storage.clear();
    userRepository.deleteAll();
    me = userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001"));
  }

  @AfterEach
  void clear() {
    storage.clear();
    userRepository.deleteAll();
  }

  @Test
  void aFailedSaveLeavesNothingBehindInTheStoredArea() throws Exception {
    Mockito.when(noteRepository.save(any(Note.class)))
        .thenThrow(new IllegalStateException("저장 실패를 흉내낸다"));

    String key = uploaded("정리본.pdf", 1024);
    String body =
        """
        {"category":"SUBJECT","title":"제목","subjectName":"과목","year":2025,"semester":"SPRING",
         "files":[{"key":"%s","originalName":"정리본.pdf"}]}
        """
            .formatted(key);

    mockMvc
        .perform(
            Csrf.with(sessions.as(me, post(NOTES)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isInternalServerError());

    /*
     * 임시본은 남아 있어야 한다 — 하루 뒤 라이프사이클이 걷어가고, 그 전에 사용자가 다시
     * 등록을 시도하면 그대로 쓸 수 있다. 지워지면 안 되는 쪽은 최종 자리다.
     */
    assertThat(storage.keys()).containsExactly(key);
  }

  private String uploaded(String name, long size) throws Exception {
    String json =
        mockMvc
            .perform(
                Csrf.with(sessions.as(me, post(NOTES + "/upload-url")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"files\":[{\"originalName\":\""
                            + name
                            + "\",\"sizeBytes\":"
                            + size
                            + "}]}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String key = objectMapper.readTree(json).path("uploads").get(0).path("key").asText();
    storage.put(key, size);
    return key;
  }
}
