package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
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

/**
 * 자료 내려받기 URL 발급 (#55, spec 2-1 §2-1-4 MUST, 3-2 §3-2-4).
 *
 * <p><b>영구적인 공개 URL은 존재하지 않는다.</b> 버킷은 완전 비공개이고, 파일에 닿는 길은 여기서 발급하는 짧은 수명의 서명된 주소뿐이다.
 *
 * <p>실제 만료(T-27)와 버킷 비공개(T-28)는 <b>진짜 S3가 있어야 확인된다</b> — 배포 리허설(#48)의 수동 점검 항목이다. 여기서 지키는 것은
 * <b>누구에게 발급하는가와 무엇을 서명에 담는가</b>다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStorageConfig.class)
class NoteDownloadIntegrationTest extends AbstractIntegrationTest {

  private static final String NOTES = "/api/v1/notes";

  /** {@code application.yml}의 {@code app.storage.download-presign-ttl}과 같은 값이다. */
  private static final Duration TTL = Duration.ofMinutes(1);

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FakeFileStorage storage;
  @Autowired private ObjectMapper objectMapper;

  private User me;
  private long noteId;
  private long fileId;

  @BeforeEach
  void setUp() throws Exception {
    clearAll();
    me = userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001"));
    JsonNode note = uploadNote("운영체제 정리본.pdf");
    noteId = note.path("id").asLong();
    fileId = note.path("files").get(0).path("id").asLong();
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

  /** 등록까지 실제 경로로 밟는다 — 파일 id는 등록이 만들어 주는 값이다. */
  private JsonNode uploadNote(String fileName) throws Exception {
    String issued =
        mockMvc
            .perform(
                Csrf.with(sessions.as(me, post(NOTES + "/upload-url")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"files\":[{\"originalName\":\"" + fileName + "\",\"sizeBytes\":1024}]}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String key = objectMapper.readTree(issued).path("uploads").get(0).path("key").asText();
    storage.put(key, 1024);

    String created =
        mockMvc
            .perform(
                Csrf.with(sessions.as(me, post(NOTES)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"category":"SUBJECT","title":"운영체제 정리","subjectName":"운영체제",
                         "year":2025,"semester":"SPRING",
                         "files":[{"key":"%s","originalName":"%s"}]}
                        """
                            .formatted(key, fileName)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(created);
  }

  private String downloadPath(long note, long file) {
    return NOTES + "/" + note + "/files/" + file;
  }

  /* -------------------------------------------------------------- 발급된다 */

  /** T-298 — 발급받은 주소로 곧바로 받을 수 있고, <b>저장될 이름이 함께 온다.</b> */
  @Test
  void anActiveMemberGetsASignedUrl() throws Exception {
    mockMvc
        .perform(sessions.as(me, get(downloadPath(noteId, fileId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").isNotEmpty())
        .andExpect(jsonPath("$.originalName").value("운영체제 정리본.pdf"))
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  /**
   * T-299 — <b>저장될 이름이 서명에 들어간다</b> (MUST).
   *
   * <p>S3 키는 {@code uuid}라, 담지 않으면 사용자 디스크에 알아볼 수 없는 이름으로 저장된다. 프론트가 {@code <a download="…">}로 고칠 수
   * 없다 — 그 힌트는 다른 오리진 링크에서 무시된다.
   */
  @Test
  void theSignedUrlCarriesTheFileNameAndAttachment() throws Exception {
    String json =
        mockMvc
            .perform(sessions.as(me, get(downloadPath(noteId, fileId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(objectMapper.readTree(json).path("url").asText()).isNotBlank();

    // 서명에 무엇을 담으라고 했는가. 실제 인코딩 형식은 S3FileStorageTest가 본다.
    String key =
        storage.keys().stream().filter(k -> k.startsWith("notes/")).findFirst().orElseThrow();
    assertThat(storage.dispositionOf(key)).isEqualTo("attachment; filename*=UTF-8''운영체제 정리본.pdf");
  }

  /** T-300 — <b>응답에 S3 키가 없다</b> (§3-2-4 MUST). 키 구조를 밖에 드러낼 이유가 없다. */
  @Test
  void theResponseDoesNotLeakTheObjectKeyPath() throws Exception {
    String json =
        mockMvc
            .perform(sessions.as(me, get(downloadPath(noteId, fileId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode body = objectMapper.readTree(json);
    assertThat(body.has("key")).isFalse();
    assertThat(body.has("storedPath")).isFalse();
    // url 안에는 키가 들어갈 수밖에 없다 — 그것이 받는 주소이기 때문이다. 본문의 다른 필드가 문제다.
    assertThat(body.path("originalName").asText()).doesNotContain("notes/");
  }

  /**
   * T-306 — <b>알려 준 만료가 실제 만료보다 늦으면 안 된다</b> (#208 리뷰).
   *
   * <p>S3의 만료는 <b>서명이 만들어진 순간</b>부터 센다. 기준 시각을 서명 뒤에 잡으면 우리가 알려 준 {@code expiresAt}이 실제보다 늦어지고, 그
   * 차이만큼 <b>"아직 유효하다고 적혀 있는데 S3는 거절하는"</b> 구간이 생긴다 — 서명이 오래 걸리거나 GC가 끼면 더 벌어진다.
   *
   * <p>반대로 틀리는 것은 안전하다. 이르게 만료된다고 알리면 다시 발급받으면 그만이다.
   */
  @Test
  void theReportedExpiryIsNeverLaterThanTheRealOne() throws Exception {
    storage.delayPresign(Duration.ofMillis(200));

    String json =
        mockMvc
            .perform(sessions.as(me, get(downloadPath(noteId, fileId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Instant reported = Instant.parse(objectMapper.readTree(json).path("expiresAt").asText());
    Instant real = storage.presignedAt().plus(TTL);

    assertThat(reported).isBeforeOrEqualTo(real);
  }

  /* ---------------------------------------------------------------- 막힌다 */

  /** T-301 — <b>다른 자료의 파일 번호를 끼워 넣으면 막힌다.</b> 경로가 거짓말하게 두지 않는다. */
  @Test
  void aFileFromAnotherNoteIsNotFound() throws Exception {
    long otherNoteId = uploadNote("다른자료.pdf").path("id").asLong();

    mockMvc
        .perform(sessions.as(me, get(downloadPath(otherNoteId, fileId))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aMissingNoteIsNotFound() throws Exception {
    mockMvc
        .perform(sessions.as(me, get(downloadPath(999_999L, fileId))))
        .andExpect(status().isNotFound());
  }

  @Test
  void aMissingFileIsNotFound() throws Exception {
    mockMvc
        .perform(sessions.as(me, get(downloadPath(noteId, 999_999L))))
        .andExpect(status().isNotFound());
  }

  /** T-302 — 비로그인은 발급받지 못한다 (§2-1-4 MUST). */
  @Test
  void guestsGetNothing() throws Exception {
    mockMvc.perform(get(downloadPath(noteId, fileId))).andExpect(status().isUnauthorized());
  }

  /** 승인 대기 계정도 막힌다 — {@code AccountStatusFilter}가 인가보다 먼저 끊는다. */
  @Test
  void pendingAccountsGetNothing() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-ap", "ap@khu.ac.kr", "20250003"));

    mockMvc
        .perform(sessions.as(applicant, get(downloadPath(noteId, fileId))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  /** 정지된 계정도 같다. */
  @Test
  void suspendedAccountsGetNothing() throws Exception {
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-sp", "sp@khu.ac.kr", "20250004"));

    mockMvc
        .perform(sessions.as(suspended, get(downloadPath(noteId, fileId))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /** 남이 올린 자료도 받을 수 있다 — 자료 공유가 이 사이트의 존재 이유다 (2-1 §2-1-2). */
  @Test
  void anyActiveMemberMayDownloadSomeoneElsesNote() throws Exception {
    User other =
        userRepository.saveAndFlush(Accounts.approved("sub-ot", "ot@khu.ac.kr", "20250002"));

    mockMvc
        .perform(sessions.as(other, get(downloadPath(noteId, fileId))))
        .andExpect(status().isOk());
  }
}
