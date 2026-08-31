package org.hackerkhu.hackerhp.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * {@link OrphanObjectCleanupJob}의 나열·삭제 왕복을 실제 S3 호환 서버(MinIO)로 본다 (#339).
 *
 * <p>판단 규칙 자체(무엇을 지울지)는 {@code OrphanObjectCleanupJobTest}가 MinIO 없이 이미 잰다 — 여기서는 <b>그 규칙이 실제
 * `ListObjectsV2`·`DeleteObject` 호출로 제대로 이어지는지</b>만 본다. {@code PhotoApiIntegrationTest}와 같은 이유로 진짜
 * S3 호환 서버를 쓴다.
 *
 * <p>{@code app.storage.orphan-cleanup.safety-margin}을 {@code 0s}로 낮춘다 — 방금 올린 오브젝트도 곧바로 "안전 여유를
 * 지났다"로 판단하게 해서, 실제 시간을 기다리지 않고 삭제 경로를 검증한다. 안전 여유 자체의 경계 판단은 단위 테스트가 이미 본다.
 */
@SpringBootTest
class OrphanObjectCleanupJobIntegrationTest extends AbstractIntegrationTest {

  private static final String BUCKET = "hacker-uploads-cleanup-test";

  private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

  static {
    MINIO.start();
  }

  @DynamicPropertySource
  static void storageProperties(DynamicPropertyRegistry registry) {
    registry.add("app.storage.bucket", () -> BUCKET);
    registry.add("app.storage.region", () -> "us-east-1");
    registry.add("app.storage.endpoint", MINIO::getS3URL);
    registry.add("app.storage.access-key", MINIO::getUserName);
    registry.add("app.storage.secret-key", MINIO::getPassword);
    // 실제 시간을 기다리지 않고 "안전 여유를 지났다" 경로를 곧바로 검증한다.
    registry.add("app.storage.orphan-cleanup.safety-margin", () -> "0s");
  }

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

  @Autowired private OrphanObjectCleanupJob job;
  @Autowired private S3Client s3;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void wipe() {
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    jdbcTemplate.update("DELETE FROM photos");
  }

  @AfterEach
  void clear() {
    wipe();
  }

  private void put(String key) {
    s3.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromString("x"));
  }

  private boolean exists(String key) {
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }

  private void insertNote(String storedPath) {
    Timestamp now = Timestamp.from(Instant.now());
    long noteId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO notes (category, title, subject_name, year, semester, uploader_id,
                                created_at, updated_at)
            VALUES ('SUBJECT', '제목', '과목', 2025, 'SPRING', NULL, ?, ?)
            RETURNING id
            """,
            Long.class,
            now,
            now);
    jdbcTemplate.update(
        "INSERT INTO note_files (note_id, original_name, stored_path, size_bytes)"
            + " VALUES (?, '원본.pdf', ?, 1)",
        noteId,
        storedPath);
  }

  private void insertPhoto(String storedPath) {
    jdbcTemplate.update(
        "INSERT INTO photos (caption, stored_path, uploader_id, created_at) VALUES (NULL, ?, NULL, ?)",
        storedPath,
        Timestamp.from(Instant.now()));
  }

  /** 참조를 잃은 최종 위치 오브젝트는 실제로 S3에서 사라진다 — 이 정리 작업이 존재하는 이유. */
  @Test
  void deletesAnUnreferencedFinalLocationObject() {
    put("notes/orphaned-uuid.pdf");

    job.run();

    assertThat(exists("notes/orphaned-uuid.pdf")).isFalse();
  }

  /** {@code note_files.stored_path}가 가리키는 오브젝트는 살아남는다. */
  @Test
  void keepsAnObjectReferencedByANoteFile() {
    put("notes/referenced-uuid.pdf");
    insertNote("notes/referenced-uuid.pdf");

    job.run();

    assertThat(exists("notes/referenced-uuid.pdf")).isTrue();
  }

  /** 사진 본 이미지뿐 아니라, 거기서 유도한 썸네일 키도 함께 살아남는다. */
  @Test
  void keepsAPhotoAndItsDerivedThumbnail() {
    String storedPath = "photos/1/uuid.jpg";
    put(storedPath);
    put("photos/1/thumb/uuid.jpg");
    insertPhoto(storedPath);

    job.run();

    assertThat(exists("photos/1/uuid.jpg")).isTrue();
    assertThat(exists("photos/1/thumb/uuid.jpg")).isTrue();
  }

  /** 참조를 잃은 사진·썸네일은 둘 다 지워진다. */
  @Test
  void deletesAnUnreferencedPhotoAndItsThumbnail() {
    put("photos/2/orphan.jpg");
    put("photos/2/thumb/orphan.jpg");

    job.run();

    assertThat(exists("photos/2/orphan.jpg")).isFalse();
    assertThat(exists("photos/2/thumb/orphan.jpg")).isFalse();
  }

  /** 임시 위치는 참조가 없어도, 안전 여유를 지났어도 건드리지 않는다 — lifecycle 규칙의 몫이다. */
  @Test
  void leavesTemporaryLocationsAlone() {
    put("notes/uploads/1/staged.pdf");
    put("photos/uploads/staged.jpg");

    job.run();

    assertThat(exists("notes/uploads/1/staged.pdf")).isTrue();
    assertThat(exists("photos/uploads/staged.jpg")).isTrue();
  }
}
