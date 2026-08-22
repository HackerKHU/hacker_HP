package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.note.repository.BookmarkRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.storage.FakeFileStorage;
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
 * 자료 수정·삭제 (#54, spec 2-1 §2-1-3 MUST, 3-1 §3-1-3 §3-1-7).
 *
 * <p><b>본인 것만. {@code ADMIN}은 전체.</b> 화면이 버튼을 숨기는 것과 별개로 서버가 소유자를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStorageConfig.class)
class NoteEditIntegrationTest extends AbstractIntegrationTest {

  private static final String NOTES = "/api/v1/notes";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private BookmarkRepository bookmarks;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FakeFileStorage storage;
  @Autowired private ObjectMapper objectMapper;

  private User owner;
  private User other;
  private User admin;
  private long noteId;
  private long fileId;
  private String storedKey;

  @BeforeEach
  void setUp() throws Exception {
    clearAll();
    owner = userRepository.saveAndFlush(Accounts.approved("sub-ow", "ow@khu.ac.kr", "20250001"));
    other = userRepository.saveAndFlush(Accounts.approved("sub-ot", "ot@khu.ac.kr", "20250002"));
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200000"));

    JsonNode note = createNote(owner, "정리본.pdf");
    noteId = note.path("id").asLong();
    fileId = note.path("files").get(0).path("id").asLong();
    storedKey = storedKeys().get(0);
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    storage.clear();
    jdbcTemplate.update("DELETE FROM bookmarks");
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  private List<String> storedKeys() {
    return storage.keys().stream()
        .filter(key -> !key.startsWith("notes/uploads/"))
        .sorted()
        .toList();
  }

  private String upload(User caller, String fileName) throws Exception {
    String json =
        mockMvc
            .perform(
                Csrf.with(sessions.as(caller, post(NOTES + "/upload-url")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"files\":[{\"originalName\":\"" + fileName + "\",\"sizeBytes\":1024}]}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String key = objectMapper.readTree(json).path("uploads").get(0).path("key").asText();
    storage.put(key, 1024);
    return key;
  }

  private JsonNode createNote(User caller, String fileName) throws Exception {
    String key = upload(caller, fileName);
    String json =
        mockMvc
            .perform(
                Csrf.with(sessions.as(caller, post(NOTES)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"category":"SUBJECT","title":"원래 제목","subjectName":"운영체제",
                         "professor":"김교수","year":2025,"semester":"SPRING",
                         "files":[{"key":"%s","originalName":"%s"}]}
                        """
                            .formatted(key, fileName)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json);
  }

  private MockHttpServletRequestBuilder updateRequest(User caller, long id, String files) {
    return Csrf.with(sessions.as(caller, patch(NOTES + "/" + id)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"category":"SUBJECT","title":"고친 제목","subjectName":"네트워크",
             "year":2024,"semester":"FALL","files":[%s]}
            """
                .formatted(files));
  }

  private static String keepExisting(long fileId) {
    return "{\"fileId\":" + fileId + "}";
  }

  private static String addNew(String key, String name) {
    return "{\"key\":\"" + key + "\",\"originalName\":\"" + name + "\"}";
  }

  /* ------------------------------------------------------------------ 수정 */

  /** T-308 — 메타데이터가 바뀌고, <b>업로더는 그대로다.</b> */
  @Test
  void theOwnerEditsMetadata() throws Exception {
    mockMvc
        .perform(updateRequest(owner, noteId, keepExisting(fileId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("고친 제목"))
        .andExpect(jsonPath("$.subjectName").value("네트워크"))
        .andExpect(jsonPath("$.year").value(2024))
        .andExpect(jsonPath("$.semester").value("FALL"))
        .andExpect(jsonPath("$.uploader.id").value(owner.getId()));

    // 보내지 않은 professor는 비워진다 — 전체 교체다.
    mockMvc
        .perform(sessions.as(owner, get(NOTES + "/" + noteId)))
        .andExpect(jsonPath("$.professor").doesNotExist());
  }

  /** T-309 — <b>{@code files}에서 빠진 파일은 사라지고, S3 오브젝트도 함께 정리된다</b> (§2-1-3 SHOULD). */
  @Test
  void aFileLeftOutOfTheListIsDetachedAndCleanedUp() throws Exception {
    String added = upload(owner, "추가본.pdf");

    mockMvc
        .perform(updateRequest(owner, noteId, addNew(added, "추가본.pdf")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(1))
        .andExpect(jsonPath("$.files[0].originalName").value("추가본.pdf"));

    assertThat(storage.has(storedKey)).as("떨어져 나온 파일은 지운다").isFalse();
    assertThat(storedKeys()).hasSize(1);
  }

  /** 기존 파일을 남기면서 새 파일을 더할 수 있다. 순서는 보낸 대로다. */
  @Test
  void anExistingFileAndANewOneCanBeKeptTogether() throws Exception {
    String added = upload(owner, "추가본.pdf");

    mockMvc
        .perform(
            updateRequest(owner, noteId, keepExisting(fileId) + "," + addNew(added, "추가본.pdf")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(2))
        .andExpect(jsonPath("$.files[0].originalName").value("정리본.pdf"))
        .andExpect(jsonPath("$.files[1].originalName").value("추가본.pdf"));

    assertThat(storage.has(storedKey)).as("남긴 파일은 건드리지 않는다").isTrue();
  }

  /** T-310 — <b>남의 자료는 고칠 수 없다</b> (MUST). */
  @Test
  void someoneElsesNoteCannotBeEdited() throws Exception {
    mockMvc
        .perform(updateRequest(other, noteId, keepExisting(fileId)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    mockMvc
        .perform(sessions.as(owner, get(NOTES + "/" + noteId)))
        .andExpect(jsonPath("$.title").value("원래 제목"));
  }

  /** T-311 — <b>{@code ADMIN}은 남의 자료도 고친다.</b> 그래도 업로더는 바뀌지 않는다. */
  @Test
  void anAdminEditsSomeoneElsesNoteWithoutTakingItOver() throws Exception {
    mockMvc
        .perform(updateRequest(admin, noteId, keepExisting(fileId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("고친 제목"))
        .andExpect(jsonPath("$.uploader.id").value(owner.getId()));
  }

  /** 권한 없는 요청은 <b>파일을 옮기기도 전에</b> 끊는다. */
  @Test
  void aForbiddenEditNeverTouchesStorage() throws Exception {
    String added = upload(other, "남의추가본.pdf");

    mockMvc
        .perform(updateRequest(other, noteId, addNew(added, "남의추가본.pdf")))
        .andExpect(status().isForbidden());

    assertThat(storedKeys()).as("최종 자리에 새로 생긴 것이 없다").hasSize(1);
    assertThat(storage.has(added)).as("임시본은 그대로 있다").isTrue();
  }

  /** T-312 — <b>다른 자료의 {@code fileId}는 붙일 수 없다.</b> */
  @Test
  void aFileIdFromAnotherNoteIsRejected() throws Exception {
    long otherFileId = createNote(other, "남의자료.pdf").path("files").get(0).path("id").asLong();

    mockMvc
        .perform(updateRequest(owner, noteId, keepExisting(otherFileId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** T-313 — <b>{@code fileId}와 {@code key}는 둘 중 하나만.</b> 둘 다 오면 무엇을 뜻하는지 정할 수 없다. */
  @Test
  void aFileRefMustBeEitherExistingOrNew() throws Exception {
    String added = upload(owner, "추가본.pdf");

    mockMvc
        .perform(
            updateRequest(
                owner,
                noteId,
                "{\"fileId\":" + fileId + ",\"key\":\"" + added + "\",\"originalName\":\"x.pdf\"}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(updateRequest(owner, noteId, "{}")).andExpect(status().isBadRequest());
  }

  /** 파일을 하나도 남기지 않을 수는 없다 (§2-1-2 — "1개 이상 첨부"). */
  @Test
  void aNoteCannotBeLeftWithoutFiles() throws Exception {
    mockMvc.perform(updateRequest(owner, noteId, "")).andExpect(status().isBadRequest());
  }

  /** 새로 붙이는 파일도 확장자 검사를 받는다 — 등록과 같은 규칙이다 (#207 리뷰). */
  @Test
  void aNewlyAttachedFileIsCheckedToo() throws Exception {
    String added = upload(owner, "추가본.pdf");

    mockMvc
        .perform(updateRequest(owner, noteId, addNew(added, "악성코드.exe")))
        .andExpect(status().isUnsupportedMediaType());
  }

  /* ------------------------------------------------------------------ 삭제 */

  /** T-314 — 삭제하면 <b>첨부·즐겨찾기 레코드와 S3 오브젝트가 함께 사라진다</b> (MUST). */
  @Test
  void deletingRemovesFilesBookmarksAndObjects() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(other, post(NOTES + "/" + noteId + "/bookmark"))))
        .andExpect(status().isNoContent());
    assertThat(bookmarks.existsByUserIdAndNoteId(other.getId(), noteId)).isTrue();

    mockMvc
        .perform(Csrf.with(sessions.as(owner, delete(NOTES + "/" + noteId))))
        .andExpect(status().isNoContent());

    mockMvc.perform(sessions.as(owner, get(NOTES + "/" + noteId))).andExpect(status().isNotFound());
    assertThat(bookmarks.existsByUserIdAndNoteId(other.getId(), noteId)).isFalse();
    assertThat(countNoteFiles()).isZero();
    assertThat(storage.has(storedKey)).isFalse();
  }

  /** T-315 — <b>남의 자료는 지울 수 없다</b> (MUST). */
  @Test
  void someoneElsesNoteCannotBeDeleted() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(other, delete(NOTES + "/" + noteId))))
        .andExpect(status().isForbidden());

    mockMvc.perform(sessions.as(owner, get(NOTES + "/" + noteId))).andExpect(status().isOk());
    assertThat(storage.has(storedKey)).as("파일도 그대로다").isTrue();
  }

  /** {@code ADMIN}은 남의 자료를 지운다. */
  @Test
  void anAdminDeletesSomeoneElsesNote() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete(NOTES + "/" + noteId))))
        .andExpect(status().isNoContent());
  }

  /**
   * T-316 — <b>S3 정리 실패가 삭제를 무르지 않는다</b> (§2-1-3 SHOULD).
   *
   * <p>사용자에게 삭제는 이미 끝난 일이다. 여기서 실패로 답하면 <b>재요청해도 자료가 없어 영원히 실패한다.</b>
   */
  @Test
  void aFailedObjectCleanupStillReportsSuccess() throws Exception {
    storage.failDeletes(true);

    mockMvc
        .perform(Csrf.with(sessions.as(owner, delete(NOTES + "/" + noteId))))
        .andExpect(status().isNoContent());

    storage.failDeletes(false);
    mockMvc.perform(sessions.as(owner, get(NOTES + "/" + noteId))).andExpect(status().isNotFound());
  }

  @Test
  void aMissingNoteIsNotFound() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(owner, delete(NOTES + "/999999"))))
        .andExpect(status().isNotFound());
  }

  /** 정지된 계정은 자기 자료도 손대지 못한다 — 필터가 인가보다 먼저 끊는다. */
  @Test
  void suspendedAccountsCannotEditTheirOwnNote() throws Exception {
    User target = userRepository.findById(owner.getId()).orElseThrow();
    target.suspend();
    userRepository.saveAndFlush(target);

    mockMvc
        .perform(Csrf.with(sessions.as(owner, delete(NOTES + "/" + noteId))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  private int countNoteFiles() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM note_files WHERE note_id = ?", Integer.class, noteId);
    return count == null ? 0 : count;
  }
}
