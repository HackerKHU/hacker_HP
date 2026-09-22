package org.hackerkhu.hackerhp.domain.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.imageio.ImageIO;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.photo.entity.Photo;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.storage.FileStorage;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code POST /photos/upload-url}, {@code POST /photos}, {@code GET /photos}, {@code DELETE
 * /photos/{id}} — #57, spec 3-2 §3-2-5.
 *
 * <p>진짜 S3 흐름(presigned PUT으로 직접 올리고, 서버가 읽어 리사이즈하고, 정리하는 것)을 MinIO로 검증한다 — {@code
 * AbstractIntegrationTest}가 Postgres를 Testcontainers로 검증하는 것과 같은 이유다. <b>컨테이너를 JVM 전체에서 하나만
 * 쓴다</b>(정적 초기화로 한 번만 띄운다) — 같은 이유로 {@code @Testcontainers}·{@code @Container}는 쓰지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PhotoApiIntegrationTest extends AbstractIntegrationTest {

  private static final String BUCKET = "hacker-uploads-test";

  /**
   * MinIO 컨테이너.
   *
   * <p><b>{@code quay.io}에서 받고 태그를 고정한다</b> (2026-09-22). 예전에는 {@code minio/minio:latest}였는데,
   * Docker Hub의 그 저장소가 사라져 <b>CI가 이미지를 받지 못하고 통째로 실패했다</b> — 코드를 한 줄도 건드리지 않은 PR에서도 그랬다. MinIO의 공식
   * 배포처는 {@code quay.io}다.
   *
   * <p><b>{@code latest}로 두지 않는다.</b> 그것이 이번 고장의 원인이다 — 태그가 떠 있으면 <b>우리가 아무것도 안 해도 어느 날 깨진다.</b> 올릴
   * 때는 여기 적힌 태그를 실제로 받아 보고 바꾼다.
   *
   * <p>{@code asCompatibleSubstituteFor}가 필요한 이유는 Testcontainers가 {@code MinIOContainer}에 기대하는 이름이
   * {@code minio/minio}라서다. 레지스트리만 다르고 같은 이미지다.
   */
  private static final MinIOContainer MINIO =
      new MinIOContainer(
          DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
              .asCompatibleSubstituteFor("minio/minio"));

  static {
    MINIO.start();
  }

  /**
   * {@code app.storage}는 자료(#207)와 공용이다(#213 통합) — 자료는 {@code endpoint}를 안 쓰므로 이 오버라이드가 자료 테스트에 영향을
   * 주지 않는다.
   */
  @DynamicPropertySource
  static void storageProperties(DynamicPropertyRegistry registry) {
    registry.add("app.storage.bucket", () -> BUCKET);
    registry.add("app.storage.region", () -> "us-east-1");
    registry.add("app.storage.endpoint", MINIO::getS3URL);
    registry.add("app.storage.access-key", MINIO::getUserName);
    registry.add("app.storage.secret-key", MINIO::getPassword);
  }

  /** MinIO는 버킷을 미리 만들어주지 않는다 — 앱이 뜨기 전에 한 번만 만든다. */
  @BeforeAll
  static void createBucket() {
    try (S3Client client =
        S3Client.builder()
            .region(Region.of("us-east-1"))
            .endpointOverride(URI.create(MINIO.getS3URL()))
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
            .build()) {
      client.createBucket(b -> b.bucket(BUCKET));
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private PhotoRepository photoRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * S3 왕복 <b>도중</b>을 만들기 위한 spy다. {@code MockitoBean}이 아니라 spy인 이유는, 이 테스트가 진짜 MinIO와 주고받는 흐름 전체를
   * 재기 때문이다 — 통째로 갈아끼우면 리사이즈·서명·다운로드가 함께 사라진다.
   */
  @MockitoSpyBean private FileStorage storage;

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private User admin;
  private User member;

  @BeforeEach
  void createAccounts() {
    photoRepository.deleteAll();
    userRepository.deleteAll();
    /*
     * 이름을 명시한다. 아래에서 응답의 `uploaderName`을 단언하므로 헬퍼의 기본 이름에
     * 기대면 그 기본값이 바뀔 때 이 테스트가 딸려 깨진다 — 실제로 그랬다 (#224).
     */
    admin =
        userRepository.saveAndFlush(
            Accounts.admin("sub-admin", "admin@khu.ac.kr", "20240001", "본명"));
    member =
        userRepository.saveAndFlush(
            Accounts.approved("sub-member", "member@khu.ac.kr", "20240002"));
  }

  @AfterEach
  void clear() {
    photoRepository.deleteAll();
    userRepository.deleteAll();
  }

  private MockHttpServletRequestBuilder write(
      User user, MockHttpServletRequestBuilder builder, String body) {
    return Csrf.with(sessions.as(user, builder))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static byte[] image(int width, int height, String format) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, format, out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 발급받은 presigned PUT URL로 실제 바이트를 올린다 — 브라우저가 하는 일을 그대로 재현한다.
   *
   * <p><b>Content-Type을 서명 발급 때와 똑같이 실어야 한다.</b> presigned URL의 서명에 그 헤더 값이 포함되므로, 다른 값을 보내면 (또는 아예
   * 안 보내면) MinIO/S3가 서명이 안 맞는다며 {@code 400}으로 거부한다.
   */
  private static void putToPresignedUrl(String url, byte[] content, String contentType)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", contentType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .build();
    HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private static String contentTypeOf(String extension) {
    return "png".equals(extension) ? "image/png" : "image/jpeg";
  }

  /** presigned PUT URL 발급 → S3 직접 업로드까지 끝낸 원본 키 하나를 만든다. */
  private String uploadOriginal(byte[] content, String extension) throws Exception {
    return uploadOriginal(admin, content, extension);
  }

  /** 올리는 사람을 받는다 — 업로드가 부원 전체에게 열려(#400) 관리자 말고도 이 흐름을 탄다. */
  private String uploadOriginal(User uploader, byte[] content, String extension) throws Exception {
    String uploadUrlBody = "{\"extensions\":[\"%s\"]}".formatted(extension);
    String responseBody =
        mockMvc
            .perform(write(uploader, post("/api/v1/photos/upload-url"), uploadUrlBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode first = objectMapper.readTree(responseBody).get(0);
    putToPresignedUrl(first.get("uploadUrl").asText(), content, contentTypeOf(extension));
    return first.get("key").asText();
  }

  @Test
  void adminCanIssueUploadUrls() throws Exception {
    mockMvc
        .perform(
            write(admin, post("/api/v1/photos/upload-url"), "{\"extensions\":[\"jpg\",\"png\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].key").isNotEmpty())
        .andExpect(jsonPath("$[0].uploadUrl").isNotEmpty());
  }

  /**
   * <b>일반 부원도 발급받는다</b> (2026-09-03, #400).
   *
   * <p>예전에는 정반대를 단언했다 — {@code memberCannotIssueUploadUrls}가 {@code 403 FORBIDDEN}을 기대했다. 소모임장이 사진을
   * 올릴 수 있게 하려고 업로드를 부원 전체에게 열면서 뒤집혔다 (3-3 결정 30).
   *
   * <p>권한이 갈리는 지점 전체는 {@code PhotoWritePermissionIntegrationTest}가 본다 (T-604 ~ T-612). 여기서는 이 API의
   * 겉모습만 확인한다.
   */
  @Test
  void aMemberCanIssueUploadUrls() throws Exception {
    mockMvc
        .perform(write(member, post("/api/v1/photos/upload-url"), "{\"extensions\":[\"jpg\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].uploadUrl").isNotEmpty());
  }

  @Test
  void uploadUrlWithDisallowedExtensionIsRejected() throws Exception {
    mockMvc
        .perform(write(admin, post("/api/v1/photos/upload-url"), "{\"extensions\":[\"gif\"]}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
  }

  /*
   * 전체 업로드 흐름을 한 번 관통한다: presigned URL 발급 → S3 직접 업로드 → 등록(서버가
   * 리사이즈) → 목록 조회 → 삭제. 각 단계가 이전 단계의 산출물을 실제로 쓰는지까지 본다.
   */
  /**
   * T-604 (등록 경로) — <b>일반 부원이 원본을 올리고 실제로 등록한다.</b>
   *
   * <p>{@code /upload-url}만 두드리는 것으로는 부족하다 (#402 리뷰). 권한이 바뀐 자리는 {@code registerOne}이고, 거기서 {@code
   * requireWritable}이 <b>두 번</b> 불린다 — S3 왕복 앞뒤로 한 번씩이다. 발급만 재면 그 두 자리가 사라져도 통과한다.
   *
   * <p><b>{@code uploader_id}가 그 부원이어야 한다.</b> 인증 주체에서만 정해지므로, 여기가 어긋나면 남의 이름으로 사진이 올라간다.
   */
  @Test
  void aPlainMemberRegistersAPhotoAndDeletesTheirOwn() throws Exception {
    String key = uploadOriginal(member, image(800, 600, "png"), "png");

    String body =
        mockMvc
            .perform(
                write(
                    member,
                    post("/api/v1/photos"),
                    "{\"photos\":[{\"key\":\"%s\",\"caption\":\"소모임 사진\"}]}".formatted(key)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registered[0].uploaderId").value(member.getId()))
            .andExpect(jsonPath("$.failed.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long photoId = objectMapper.readTree(body).get("registered").get(0).get("id").asLong();

    Photo saved = photoRepository.findById(photoId).orElseThrow();
    assertThat(saved.getStoredPath()).as("완결된 최종 키다").startsWith("photos/" + photoId + "/");

    mockMvc
        .perform(Csrf.with(sessions.as(member, delete("/api/v1/photos/{id}", photoId))))
        .andExpect(status().isNoContent());

    assertThat(photoRepository.existsById(photoId)).isFalse();
  }

  /**
   * T-611 — <b>S3 왕복 <i>도중에</i> 정지되면 완결되지 않은 행이 남지 않는다</b> (MUST).
   *
   * <p>{@code registerOne}은 ① 자리표시자 행을 커밋하고 ② S3에 올린 뒤 ③ 최종 키를 반영하며 <b>다시 한번</b> 상태를 확인한다. ②가 도는 동안
   * 잠금은 풀려 있어 그 사이에 정지될 수 있고, 그때 ①이 만든 행을 지우지 않으면 <b>완결되지 않은 행이 영영 남는다.</b>
   *
   * <p><b>등록 전에 정지시키는 것으로는 이 자리를 재지 못한다</b> (#402 리뷰). 그러면 ①의 확인에서 걸려 ③은 불리지도 않는다 — 실제로 ③을 지워 보니 그
   * 방식의 사례는 그대로 통과했다. 그래서 <b>업로드가 시작되는 순간</b>(②)에 정지시킨다.
   */
  @Test
  void aSuspensionMidUploadLeavesNoRow() throws Exception {
    String key = uploadOriginal(member, image(400, 300, "png"), "png");

    // ②가 시작될 때 정지시킨다 — ①은 이미 통과해 자리표시자 행이 커밋된 뒤다.
    doAnswer(
            invocation -> {
              jdbcTemplate.update(
                  "UPDATE users SET status = 'SUSPENDED' WHERE id = ?", member.getId());
              return invocation.callRealMethod();
            })
        .when(storage)
        .upload(anyString(), any(byte[].class), anyString());

    mockMvc
        .perform(
            write(
                member,
                post("/api/v1/photos"),
                "{\"photos\":[{\"key\":\"%s\",\"caption\":null}]}".formatted(key)))
        .andExpect(status().isForbidden());

    assertThat(photoRepository.count()).as("완결되지 않은 행이 남지 않는다").isZero();
  }

  @Test
  void adminCanUploadListAndDeletePhoto() throws Exception {
    String key = uploadOriginal(image(800, 600, "png"), "png");

    String registerBody = "{\"photos\":[{\"key\":\"%s\",\"caption\":\"엠티 사진\"}]}".formatted(key);
    String createdBody =
        mockMvc
            .perform(write(admin, post("/api/v1/photos"), registerBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registered[0].caption").value("엠티 사진"))
            .andExpect(jsonPath("$.registered[0].url").isNotEmpty())
            .andExpect(jsonPath("$.registered[0].thumbnailUrl").isNotEmpty())
            .andExpect(jsonPath("$.registered[0].uploaderId").value(admin.getId()))
            // 표시 이름이라 학번 끝 두 자리가 붙는다 (#301). 갤러리도 같은 규칙이다 (T-431).
            .andExpect(jsonPath("$.registered[0].uploaderName").value("본명01"))
            .andExpect(jsonPath("$.failed.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var registered = objectMapper.readTree(createdBody).get("registered").get(0);
    Long photoId = registered.get("id").asLong();

    // T-507. 화면은 이 URL을 보관해 lazy-load·확대에 쓰므로 자료 다운로드의 1분을 공유하면
    // 정상 탐색 중에 만료된다. 실제 MinIO 서명까지 관통해 10분 계약을 고정한다.
    assertThat(registered.get("url").asText()).contains("X-Amz-Expires=600");
    assertThat(registered.get("thumbnailUrl").asText()).contains("X-Amz-Expires=600");

    assertThat(photoRepository.existsById(photoId)).isTrue();
    Photo saved = photoRepository.findById(photoId).orElseThrow();
    assertThat(saved.getStoredPath()).startsWith("photos/" + photoId + "/");

    mockMvc
        .perform(sessions.as(member, get("/api/v1/photos")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(photoId))
        .andExpect(jsonPath("$.page.totalElements").value(1));

    mockMvc
        .perform(Csrf.with(sessions.as(admin, delete("/api/v1/photos/{id}", photoId))))
        .andExpect(status().isNoContent());

    assertThat(photoRepository.existsById(photoId)).isFalse();
  }

  /* 기준(가로 1920px)을 넘는 원본은 리사이즈되어 저장된다 (spec 2-1 §2-1-7 MUST). */
  @Test
  void largeOriginalIsResizedOnRegister() throws Exception {
    String key = uploadOriginal(image(3840, 2160, "png"), "png");

    mockMvc
        .perform(
            write(admin, post("/api/v1/photos"), "{\"photos\":[{\"key\":\"%s\"}]}".formatted(key)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registered.length()").value(1));

    Photo saved = photoRepository.findAll().get(0);
    // 리사이즈되면 JPEG로 바뀐다 — 원본 png 확장자가 아니라 jpg여야 한다.
    assertThat(saved.getStoredPath()).endsWith(".jpg");
  }

  /*
   * 원본 하나가 없어도(NOT_FOUND) 요청 전체가 실패하지 않는다 — 함께 보낸 다른 원본은 그대로
   * 등록되고, 실패한 항목은 failed 배열에 사유와 함께 담긴다 (apps/api/AGENTS.md, #186 리뷰).
   */
  @Test
  void unknownKeyFailsOnlyThatItemNotTheWholeRequest() throws Exception {
    String key = uploadOriginal(image(100, 100, "jpg"), "jpg");
    String body =
        """
        {"photos":[{"key":"%s"},{"key":"photos/uploads/never-uploaded.jpg"}]}
        """
            .formatted(key);

    mockMvc
        .perform(write(admin, post("/api/v1/photos"), body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registered.length()").value(1))
        .andExpect(jsonPath("$.failed.length()").value(1))
        .andExpect(jsonPath("$.failed[0].key").value("photos/uploads/never-uploaded.jpg"))
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_FOUND"));
  }

  /* {@code @Valid}는 리스트 자체는 보되 null 원소는 거르지 않는다 — 원소에 건 @NotNull이 대신 막는다 (#186 리뷰). */
  @Test
  void registerWithNullItemIsRejected() throws Exception {
    mockMvc
        .perform(write(admin, post("/api/v1/photos"), "{\"photos\":[null]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /*
   * 등록이 끝나지 않은 자리표시자 행(storedPath가 아직 임시 키인 상태)은 목록에 보이지 않는다
   * (#186 리뷰) — 두 번째 트랜잭션이 끝내 실패하면 이런 행이 영구히 남을 수 있는데, 그래도 본
   * 이미지 URL이 리사이즈되지 않은 원본을, 썸네일 URL이 존재하지 않는 오브젝트를 가리키는 응답이
   * 나가서는 안 된다.
   */
  @Test
  void incompletePlaceholderRowIsHiddenFromList() throws Exception {
    Photo placeholder = Photo.upload(null, "photos/uploads/never-finished.jpg", admin);
    photoRepository.saveAndFlush(placeholder);

    mockMvc
        .perform(sessions.as(member, get("/api/v1/photos")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  @Test
  void memberCannotDeletePhoto() throws Exception {
    String key = uploadOriginal(image(100, 100, "jpg"), "jpg");
    String body =
        mockMvc
            .perform(
                write(
                    admin,
                    post("/api/v1/photos"),
                    "{\"photos\":[{\"key\":\"%s\"}]}".formatted(key)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long photoId = objectMapper.readTree(body).get("registered").get(0).get("id").asLong();

    mockMvc
        .perform(Csrf.with(sessions.as(member, delete("/api/v1/photos/{id}", photoId))))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymousCannotListPhotos() throws Exception {
    mockMvc
        .perform(get("/api/v1/photos"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }
}
