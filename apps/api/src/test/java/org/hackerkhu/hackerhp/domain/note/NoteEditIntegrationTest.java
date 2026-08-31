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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    return updateRequest(caller, id, "고친 제목", files);
  }

  private MockHttpServletRequestBuilder updateRequest(
      User caller, long id, String title, String files) {
    return Csrf.with(sessions.as(caller, patch(NOTES + "/" + id)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"category":"SUBJECT","title":"%s","subjectName":"네트워크",
             "year":2024,"semester":"FALL","files":[%s]}
            """
                .formatted(title, files));
  }

  private static String keepExisting(long fileId) {
    return "{\"fileId\":" + fileId + "}";
  }

  private static String addNew(String key, String name) {
    return "{\"key\":\"" + key + "\",\"originalName\":\"" + name + "\"}";
  }

  /* ------------------------------------------------------------------ 수정 */

  /**
   * 50자 제한 전에 저장된 제목은 원문 그대로면 다른 메타데이터를 고칠 수 있다. 새 장문으로 바꾸는 것만 거부하고, 50자 이하로 줄이면 다시 일반 계약에 들어온다.
   */
  @Test
  void aLegacyLongTitleIsGrandfatheredOnlyWhileUnchanged() throws Exception {
    String legacy = "가".repeat(200);
    jdbcTemplate.update("UPDATE notes SET title = ? WHERE id = ?", legacy, noteId);

    mockMvc
        .perform(updateRequest(owner, noteId, "  " + legacy + "  ", keepExisting(fileId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value(legacy))
        .andExpect(jsonPath("$.subjectName").value("네트워크"));

    mockMvc
        .perform(updateRequest(owner, noteId, "\u00a0" + legacy + "\u00a0", keepExisting(fileId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("기존 제목의 저장 상한을 넘었습니다."));

    mockMvc
        .perform(updateRequest(owner, noteId, "다른장문".repeat(20), keepExisting(fileId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("제목은 50자까지 쓸 수 있습니다."));
    mockMvc
        .perform(sessions.as(owner, get(NOTES + "/" + noteId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value(legacy));

    mockMvc
        .perform(updateRequest(owner, noteId, "짧아진 제목", keepExisting(fileId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("짧아진 제목"));
  }

  /** 기존 장문 제목은 응답에서 자르지 않고, 제목을 줄이지 않아도 삭제할 수 있다. */
  @Test
  void aLegacyLongTitleCanStillBeReadAndDeleted() throws Exception {
    String legacy = "조회와 삭제에서도 보존할 기존 장문 제목".repeat(6);
    jdbcTemplate.update("UPDATE notes SET title = ? WHERE id = ?", legacy, noteId);

    mockMvc
        .perform(sessions.as(owner, get(NOTES + "/" + noteId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value(legacy));

    mockMvc
        .perform(Csrf.with(sessions.as(owner, delete(NOTES + "/" + noteId))))
        .andExpect(status().isNoContent());
    mockMvc.perform(sessions.as(owner, get(NOTES + "/" + noteId))).andExpect(status().isNotFound());
  }

  /** 수정 제목도 코드포인트로 세므로 이모지 50자는 허용한다. */
  @Test
  void anEditedTitleCountsCodePoints() throws Exception {
    String title = "🎉".repeat(50);

    mockMvc
        .perform(updateRequest(owner, noteId, title, keepExisting(fileId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value(title));
  }

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

  /* --------------------------------------------------- 요청 형식 (#211 리뷰) */

  /**
   * T-317 — <b>{@code files}에 {@code null}이 섞여도 {@code 400}이다.</b>
   *
   * <p>Bean Validation의 {@code @Valid}는 <b>원소가 {@code null}인 것 자체는 위반으로 보지 않는다</b> — 그대로 두면 뒤에서
   * {@code NullPointerException}이 나 잘못된 입력이 {@code 500}으로 나간다.
   */
  @Test
  void aNullElementInFilesIsAValidationError() throws Exception {
    mockMvc
        .perform(updateRequest(owner, noteId, "null"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /**
   * T-318 — <b>새로 붙이는 파일에 이름이 없으면 {@code 400}이다.</b>
   *
   * <p>없어도 뒤에서 걸리기는 한다 — 빈 확장자가 허용 목록에 없어 {@code 415}가 나간다. 그런데 이것은 <b>파일 형식 문제가 아니라 요청 형식 문제다.</b>
   * {@code 415}로 답하면 화면은 "이 파일은 못 올린다"고 안내하고, 사용자는 멀쩡한 파일을 바꾸려 든다.
   */
  @Test
  void aNewFileWithoutANameIsAValidationErrorNotAMediaTypeError() throws Exception {
    String added = upload(owner, "추가본.pdf");

    mockMvc
        .perform(updateRequest(owner, noteId, "{\"key\":\"" + added + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    mockMvc
        .perform(
            updateRequest(owner, noteId, "{\"key\":\"" + added + "\",\"originalName\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * T-319 — 같은 자료를 <b>동시에</b> 수정하면 한 줄로 선다 (#211 리뷰).
   *
   * <p>수정은 "남길 첨부 전부"를 받아 통째로 갈아끼운다. 두 요청이 각자 기존 목록을 읽고 각자 갈아끼우면 <b>보낸 목록이 최종 상태라는 계약이 깨진다</b> — A를
   * B로 바꾸는 요청과 A를 C로 바꾸는 요청이 겹치면 B와 C가 함께 남는다.
   *
   * <p><b>요청자 계정 행을 잠그는 것으로는 막히지 않는다.</b> 소유자와 관리자는 서로 다른 사람이라 다른 행을 잠근다 — 자료 행을 잠가야 한다.
   *
   * <p><b>확인하는 것은 "둘 다 남지 않는다"만이 아니다.</b> 잠그지 않아도 뒤엣것은 낡은 목록을 들고 저장하다 터져 {@code 500}을 낸다 — 데이터는 멀쩡해
   * 보이지만 <b>멀쩡한 요청이 서버 오류로 거절된다.</b> 잠그면 기다렸다가 새 목록을 보고 제대로 끝낸다.
   */
  @Test
  void twoEditsOfTheSameNoteAtOnceAreSerialized() throws Exception {
    String byOwner = upload(owner, "주인추가.pdf");
    String byAdmin = upload(admin, "관리자추가.pdf");

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Integer> statuses;
    try {
      statuses =
          pool
              .invokeAll(
                  List.of(
                      edit(owner, addNew(byOwner, "주인추가.pdf"), ready),
                      edit(admin, addNew(byAdmin, "관리자추가.pdf"), ready)))
              .stream()
              .map(NoteEditIntegrationTest::valueOf)
              .toList();
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertThat(statuses).as("어느 쪽도 서버 오류로 끝나면 안 된다").doesNotContain(500);
    assertThat(statuses).as("적어도 한쪽은 성공한다").contains(200);
    // 어느 쪽이 이기든 남는 첨부는 하나다. 둘 다 남으면 "보낸 목록이 최종 상태"가 깨진 것이다.
    assertThat(countNoteFiles()).as("두 요청의 파일이 함께 남으면 안 된다").isEqualTo(1);
  }

  private Callable<Integer> edit(User caller, String files, CyclicBarrier ready) {
    return () -> {
      ready.await(10, TimeUnit.SECONDS);
      return mockMvc
          .perform(updateRequest(caller, noteId, files))
          .andReturn()
          .getResponse()
          .getStatus();
    };
  }

  private static int valueOf(Future<Integer> result) {
    try {
      return result.get();
    } catch (Exception e) {
      return -1;
    }
  }

  private int countNoteFiles() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM note_files WHERE note_id = ?", Integer.class, noteId);
    return count == null ? 0 : count;
  }
}
