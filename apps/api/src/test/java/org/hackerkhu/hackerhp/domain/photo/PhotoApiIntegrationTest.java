package org.hackerkhu.hackerhp.domain.photo;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MinIOContainer;
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

  private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

  static {
    MINIO.start();
  }

  @DynamicPropertySource
  static void storageProperties(DynamicPropertyRegistry registry) {
    registry.add("app.photo-storage.bucket", () -> BUCKET);
    registry.add("app.photo-storage.region", () -> "us-east-1");
    registry.add("app.photo-storage.endpoint", MINIO::getS3URL);
    registry.add("app.photo-storage.access-key", MINIO::getUserName);
    registry.add("app.photo-storage.secret-key", MINIO::getPassword);
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

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private User admin;
  private User member;

  @BeforeEach
  void createAccounts() {
    photoRepository.deleteAll();
    userRepository.deleteAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-admin", "admin@khu.ac.kr", "20240001"));
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
    String uploadUrlBody = "{\"extensions\":[\"%s\"]}".formatted(extension);
    String responseBody =
        mockMvc
            .perform(write(admin, post("/api/v1/photos/upload-url"), uploadUrlBody))
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

  @Test
  void memberCannotIssueUploadUrls() throws Exception {
    mockMvc
        .perform(write(member, post("/api/v1/photos/upload-url"), "{\"extensions\":[\"jpg\"]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
            .andExpect(jsonPath("$.registered[0].uploaderName").value("본명"))
            .andExpect(jsonPath("$.failed.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long photoId = objectMapper.readTree(createdBody).get("registered").get(0).get("id").asLong();

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
