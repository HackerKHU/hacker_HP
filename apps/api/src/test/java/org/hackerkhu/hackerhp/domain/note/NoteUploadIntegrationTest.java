package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.IntStream;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.storage.FakeFileStorage;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 자료 업로드·등록 (#53, spec 2-1 §2-1-2 MUST, 3-2 §3-2-4).
 *
 * <p><b>서버는 파일 바이트를 받지 않는다.</b> ①에서 presigned URL을 받고, ②는 브라우저가 S3에 직접 올리고, ③에서 키만 돌려준다. 여기서 ②는
 * {@link FakeFileStorage#put}이 대신한다 — <b>"올라와 있다"만 재현하면 되기 때문이다.</b>
 *
 * <p>실제 S3 연동(서명·CORS·권한)은 배포 리허설(#48)의 수동 점검이 맡는다. 여기서 지키는 것은 <b>발급 조건·크기 검증·정리 순서</b>다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoteUploadIntegrationTest extends AbstractIntegrationTest {

  private static final String NOTES = "/api/v1/notes";

  /**
   * 진짜 S3 대신 가짜를 쓴다.
   *
   * <p>{@code @MockitoBean}이 아니라 {@code @Primary} 빈인 이유는, 이 테스트가 확인하려는 것이 <b>호출 여부가 아니라 저장소에 무엇이
   * 남았는가</b>이기 때문이다.
   */
  @TestConfiguration
  static class FakeStorageConfig {
    @Bean
    @Primary
    FakeFileStorage fakeFileStorage() {
      return new FakeFileStorage();
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FakeFileStorage storage;
  @Autowired private ObjectMapper objectMapper;

  private User me;
  private User other;

  @BeforeEach
  void setUp() {
    clearAll();
    me =
        userRepository.saveAndFlush(Accounts.approved("sub-me", "me@khu.ac.kr", "20250001", "김부원"));
    other = userRepository.saveAndFlush(Accounts.approved("sub-ot", "ot@khu.ac.kr", "20250002"));
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

  private MockHttpServletRequestBuilder jsonPost(User caller, String path, String body) {
    return Csrf.with(sessions.as(caller, post(path)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static String uploadUrlBody(String name, long size) {
    return "{\"files\":[{\"originalName\":\"" + name + "\",\"sizeBytes\":" + size + "}]}";
  }

  /** ①을 부르고 발급된 키를 돌려준다. */
  private String issueKey(User caller, String name, long size) throws Exception {
    String json =
        mockMvc
            .perform(jsonPost(caller, NOTES + "/upload-url", uploadUrlBody(name, size)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).path("uploads").get(0).path("key").asText();
  }

  /** ①·②를 함께 밟아 "올라와 있는" 키를 만든다. */
  private String uploaded(User caller, String name, long size) throws Exception {
    String key = issueKey(caller, name, size);
    storage.put(key, size);
    return key;
  }

  private static String createBody(String key, String originalName) {
    return """
        {"category":"SUBJECT","title":"운영체제 정리본","subjectName":"운영체제",
         "professor":"김교수","year":2025,"semester":"SPRING",
         "files":[{"key":"%s","originalName":"%s"}]}
        """
        .formatted(key, originalName);
  }

  /* ---------------------------------------------------------------- 발급 ① */

  /** 발급받은 키는 <b>임시 자리에, 내 id를 달고</b> 만들어진다 (#53 D2·D3). */
  @Test
  void issuedKeyIsStagedUnderTheUploader() throws Exception {
    String key = issueKey(me, "정리본.pdf", 1024);

    assertThat(key).startsWith("notes/uploads/" + me.getId() + "/").endsWith(".pdf");
  }

  /** T-26 — 허용되지 않은 확장자. */
  @Test
  void anUnsupportedExtensionIsRejected() throws Exception {
    mockMvc
        .perform(jsonPost(me, NOTES + "/upload-url", uploadUrlBody("해킹툴.exe", 1024)))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
  }

  /** 확장자가 아예 없는 파일도 같다 — 허용 목록에 빈 값이 없으므로 그대로 걸린다. */
  @Test
  void aFileWithoutAnExtensionIsRejected() throws Exception {
    mockMvc
        .perform(jsonPost(me, NOTES + "/upload-url", uploadUrlBody("README", 1024)))
        .andExpect(status().isUnsupportedMediaType());
  }

  /** 확장자는 대소문자를 가리지 않는다 — 윈도우에서 온 파일은 흔히 대문자다. */
  @Test
  void theExtensionCheckIgnoresCase() throws Exception {
    mockMvc
        .perform(jsonPost(me, NOTES + "/upload-url", uploadUrlBody("정리본.PDF", 1024)))
        .andExpect(status().isOk());
  }

  /** T-25 — 상한을 넘는 크기. */
  @Test
  void aTooLargeFileIsRejectedBeforeUploading() throws Exception {
    mockMvc
        .perform(jsonPost(me, NOTES + "/upload-url", uploadUrlBody("정리본.pdf", 21L * 1024 * 1024)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
  }

  /**
   * <b>둘 다 어긋나면 확장자를 먼저 답한다.</b>
   *
   * <p>크기를 먼저 말하면 사용자는 압축해서 다시 시도한 뒤에야 종류가 문제였음을 안다.
   */
  @Test
  void theExtensionAnswersFirstWhenBothAreWrong() throws Exception {
    mockMvc
        .perform(jsonPost(me, NOTES + "/upload-url", uploadUrlBody("영상.mp4", 30L * 1024 * 1024)))
        .andExpect(status().isUnsupportedMediaType());
  }

  /** 개수 상한을 넘으면 <b>아무것도 발급하지 않는다.</b> */
  @Test
  void tooManyFilesGetNothing() throws Exception {
    String files =
        IntStream.range(0, 11)
            .mapToObj(i -> "{\"originalName\":\"f" + i + ".pdf\",\"sizeBytes\":10}")
            .reduce((a, b) -> a + "," + b)
            .orElseThrow();

    mockMvc
        .perform(jsonPost(me, NOTES + "/upload-url", "{\"files\":[" + files + "]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** 하나가 걸리면 <b>통과한 것까지 발급하지 않는다.</b> 반쯤 발급하면 무엇이 통과했는지 알 수 없다. */
  @Test
  void oneBadFileBlocksTheWholeBatch() throws Exception {
    String body =
        """
        {"files":[{"originalName":"좋은파일.pdf","sizeBytes":10},
                  {"originalName":"나쁜파일.exe","sizeBytes":10}]}
        """;

    mockMvc
        .perform(jsonPost(me, NOTES + "/upload-url", body))
        .andExpect(status().isUnsupportedMediaType());
    assertThat(storage.keys()).isEmpty();
  }

  /* ---------------------------------------------------------------- 등록 ③ */

  /** 등록하면 파일이 <b>최종 자리로 옮겨지고 임시본은 사라진다</b> (#53 D2). */
  @Test
  void registeringMovesTheFileOutOfStaging() throws Exception {
    String key = uploaded(me, "정리본.pdf", 2048);

    String json =
        mockMvc
            .perform(jsonPost(me, NOTES, createBody(key, "정리본.pdf")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.uploader.id").value(me.getId()))
            .andExpect(jsonPath("$.uploader.name").value("김부원"))
            .andExpect(jsonPath("$.files[0].originalName").value("정리본.pdf"))
            .andExpect(jsonPath("$.files[0].sizeBytes").value(2048))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(storage.has(key)).as("임시본은 지운다").isFalse();
    assertThat(storage.keys()).allSatisfy(k -> assertThat(k).startsWith("notes/"));
    assertThat(storage.keys()).noneSatisfy(k -> assertThat(k).startsWith("notes/uploads/"));

    // 목록·상세로도 보인다.
    Long id = objectMapper.readTree(json).path("id").asLong();
    mockMvc
        .perform(sessions.as(me, get(NOTES + "/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(1));
  }

  /** <b>S3 키는 응답에 담지 않는다</b> (§3-2-4 MUST). 버킷 구조를 밖에 드러낼 이유가 없다. */
  @Test
  void theResponseNeverCarriesTheObjectKey() throws Exception {
    String key = uploaded(me, "정리본.pdf", 2048);

    String json =
        mockMvc
            .perform(jsonPost(me, NOTES, createBody(key, "정리본.pdf")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(json).doesNotContain("notes/");
  }

  /**
   * <b>남이 올린 키는 등록할 수 없다</b> (#53 D3).
   *
   * <p>등록은 "올라온 키 목록"을 그대로 받으므로, 막지 않으면 키 문자열만 알면 남의 파일을 자기 자료로 삼을 수 있다.
   */
  @Test
  void someoneElsesKeyCannotBeRegistered() throws Exception {
    String theirKey = uploaded(other, "남의정리본.pdf", 2048);

    mockMvc
        .perform(jsonPost(me, NOTES, createBody(theirKey, "가로챈파일.pdf")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(storage.has(theirKey)).as("남의 파일을 건드리지도 않는다").isTrue();
  }

  /** 최종 자리의 키를 그대로 다시 내는 것도 막힌다 — 임시 프리픽스가 아니다. */
  @Test
  void aStoredKeyCannotBeRegisteredAgain() throws Exception {
    mockMvc
        .perform(jsonPost(me, NOTES, createBody("notes/whatever.pdf", "x.pdf")))
        .andExpect(status().isForbidden());
  }

  /** T-25(등록 쪽) — <b>실제로 올라온</b> 크기가 상한을 넘으면 지우고 거절한다 (MUST). */
  @Test
  void aFileThatArrivedTooLargeIsDeletedAndRejected() throws Exception {
    // ①은 정직한 크기로 통과시키고, ②에서 20MB를 넘겨 올린다 — presigned PUT은 이것을 막지 못한다.
    String key = issueKey(me, "정리본.pdf", 1024);
    storage.put(key, 21L * 1024 * 1024);

    mockMvc
        .perform(jsonPost(me, NOTES, createBody(key, "정리본.pdf")))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

    assertThat(storage.has(key)).as("넘긴 파일을 그 자리에 두지 않는다").isFalse();
  }

  /** 올리지 않고 등록만 시도하면 <b>서버 장애가 아니라 입력 오류다.</b> */
  @Test
  void registeringAKeyThatWasNeverUploadedIsAValidationError() throws Exception {
    String key = issueKey(me, "정리본.pdf", 1024);

    mockMvc
        .perform(jsonPost(me, NOTES, createBody(key, "정리본.pdf")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /**
   * 하나라도 걸리면 <b>아무것도 옮기지 않는다.</b>
   *
   * <p>옮기고 나서 거절하면 최종 자리에 주인 없는 파일이 남는데, 그 자리에는 만료 규칙이 없다.
   */
  @Test
  void nothingIsMovedWhenOneFileFails() throws Exception {
    String good = uploaded(me, "좋은파일.pdf", 1024);
    String missing = issueKey(me, "안올린파일.pdf", 1024);

    String body =
        """
        {"category":"SUBJECT","title":"제목","subjectName":"과목","year":2025,"semester":"SPRING",
         "files":[{"key":"%s","originalName":"좋은파일.pdf"},
                  {"key":"%s","originalName":"안올린파일.pdf"}]}
        """
            .formatted(good, missing);

    mockMvc.perform(jsonPost(me, NOTES, body)).andExpect(status().isBadRequest());

    assertThat(storage.keys()).containsExactly(good);
  }

  /** {@code category=EXAM}인데 시험 구분이 없으면 <b>DB 제약이 아니라 우리가</b> 막는다 (§3-2-2). */
  @Test
  void anExamNoteWithoutAnExamTypeIsRejected() throws Exception {
    String key = uploaded(me, "기출.pdf", 1024);
    String body =
        """
        {"category":"EXAM","title":"기출","subjectName":"운영체제","year":2025,"semester":"SPRING",
         "files":[{"key":"%s","originalName":"기출.pdf"}]}
        """
            .formatted(key);

    mockMvc
        .perform(jsonPost(me, NOTES, body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** 반대 방향도 막는다 — 과목 자료에 시험 구분이 붙으면 CHECK 제약이 터진다. */
  @Test
  void aSubjectNoteWithAnExamTypeIsRejected() throws Exception {
    String key = uploaded(me, "정리본.pdf", 1024);
    String body =
        """
        {"category":"SUBJECT","title":"정리","subjectName":"운영체제","year":2025,
         "semester":"SPRING","examType":"MIDTERM",
         "files":[{"key":"%s","originalName":"정리본.pdf"}]}
        """
            .formatted(key);

    mockMvc.perform(jsonPost(me, NOTES, body)).andExpect(status().isBadRequest());
  }

  /** 파일 없이 등록할 수 없다 (2-1 §2-1-2 — "1개 이상 첨부"). */
  @Test
  void aNoteWithoutFilesIsRejected() throws Exception {
    String body =
        """
        {"category":"SUBJECT","title":"제목","subjectName":"과목","year":2025,
         "semester":"SPRING","files":[]}
        """;

    mockMvc.perform(jsonPost(me, NOTES, body)).andExpect(status().isBadRequest());
  }

  /** 업로더는 <b>로그인한 사람</b>이다. 본문에 남의 id를 적어도 무시된다. */
  @Test
  void theUploaderComesFromTheSessionNotTheBody() throws Exception {
    String key = uploaded(me, "정리본.pdf", 1024);
    String body =
        """
        {"category":"SUBJECT","title":"제목","subjectName":"과목","year":2025,"semester":"SPRING",
         "uploaderId":%d,"uploader":{"id":%d,"name":"남"},
         "files":[{"key":"%s","originalName":"정리본.pdf"}]}
        """
            .formatted(other.getId(), other.getId(), key);

    mockMvc
        .perform(jsonPost(me, NOTES, body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.uploader.id").value(me.getId()));
  }

  /** 같은 키를 두 번 담아도 <b>한 번만</b> 등록된다 — 중복 클릭·재시도로 흔히 생기는 모양이다. */
  @Test
  void theSameKeyTwiceIsRegisteredOnce() throws Exception {
    String key = uploaded(me, "정리본.pdf", 1024);
    String body =
        """
        {"category":"SUBJECT","title":"제목","subjectName":"과목","year":2025,"semester":"SPRING",
         "files":[{"key":"%s","originalName":"정리본.pdf"},
                  {"key":"%s","originalName":"정리본.pdf"}]}
        """
            .formatted(key, key);

    mockMvc
        .perform(jsonPost(me, NOTES, body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.files.length()").value(1));
  }

  /** 교수명은 없어도 된다. 빈 문자열은 {@code null}로 눕혀 필터가 빈 항목을 만들지 않게 한다 (§3-2-4). */
  @Test
  void aBlankProfessorBecomesNull() throws Exception {
    String key = uploaded(me, "정리본.pdf", 1024);
    String body =
        """
        {"category":"SUBJECT","title":"제목","subjectName":"과목","professor":"  ","year":2025,
         "semester":"SPRING","files":[{"key":"%s","originalName":"정리본.pdf"}]}
        """
            .formatted(key);

    mockMvc.perform(jsonPost(me, NOTES, body)).andExpect(status().isCreated());

    mockMvc
        .perform(sessions.as(me, get(NOTES + "/filters")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.professors").isEmpty());
  }

  /* --------------------------------------------------------------- 인증·인가 */

  /** 비로그인은 발급도 등록도 못 한다. */
  @Test
  void guestsCannotUploadOrRegister() throws Exception {
    mockMvc
        .perform(
            Csrf.with(post(NOTES + "/upload-url"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadUrlBody("정리본.pdf", 10)))
        .andExpect(status().isUnauthorized());
  }

  /** 승인 대기 계정도 막힌다 — {@code AccountStatusFilter}가 인가보다 먼저 끊는다. */
  @Test
  void pendingAccountsCannotUpload() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-ap", "ap@khu.ac.kr", "20250003"));

    mockMvc
        .perform(jsonPost(applicant, NOTES + "/upload-url", uploadUrlBody("정리본.pdf", 10)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  /** CSRF 토큰 없이는 쓰기가 통과하지 못한다. */
  @Test
  void writesNeedACsrfToken() throws Exception {
    mockMvc
        .perform(
            sessions
                .as(me, post(NOTES + "/upload-url"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadUrlBody("정리본.pdf", 10)))
        .andExpect(status().isForbidden());
  }

  /** 발급 응답은 요청한 순서·이름 그대로 짝을 맞춰 준다 — 여러 개를 올릴 때 화면이 짝을 잃지 않아야 한다. */
  @Test
  void issuedUploadsKeepTheRequestedOrderAndNames() throws Exception {
    String body =
        """
        {"files":[{"originalName":"첫번째.pdf","sizeBytes":10},
                  {"originalName":"두번째.png","sizeBytes":20}]}
        """;

    String json =
        mockMvc
            .perform(jsonPost(me, NOTES + "/upload-url", body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode uploads = objectMapper.readTree(json).path("uploads");
    assertThat(
            List.of(
                uploads.get(0).path("originalName").asText(),
                uploads.get(1).path("originalName").asText()))
        .containsExactly("첫번째.pdf", "두번째.png");
    assertThat(uploads.get(0).path("key").asText()).endsWith(".pdf");
    assertThat(uploads.get(1).path("key").asText()).endsWith(".png");
    assertThat(uploads.get(0).path("url").asText()).isNotBlank();
  }
}
