package org.hackerkhu.hackerhp.global.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoRepository;
import org.hackerkhu.hackerhp.domain.photo.service.PhotoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * 참조를 잃은 S3 오브젝트를 지운다 (#339).
 *
 * <p><b>왜 필요한가.</b> 등록 롤백·자료 수정·사진 삭제가 보상하는 S3 삭제에 실패하면({@code StagedUploads}·{@code PhotoService})
 * 그 오브젝트는 로그 한 줄만 남기고 영원히 남는다 — 최종 위치({@code notes/…}, {@code photos/…})에는 S3 lifecycle 규칙이 없다 (임시
 * 위치 {@code notes/uploads/}·{@code photos/uploads/}만 하루 뒤 걷어간다, {@code
 * infra/terraform/storage.tf}).
 *
 * <p><b>임시 위치는 건드리지 않는다.</b> 그쪽은 이미 lifecycle 규칙이 정리하고, 등록이 진행 중인 원본이 섞여 있어 여기서 또 판단하면 규칙이 두 벌이 된다.
 *
 * <p><b>참조 판단은 DB가 기준이다.</b> {@link NoteRepository#findAllFileStoredPaths}·{@link
 * PhotoRepository#findAllStoredPaths}가 돌려주는 키(사진은 {@link PhotoService#thumbnailKeyOf}로 썸네일 키까지 유도)
 * 밖에 있는 오브젝트만 후보다.
 *
 * <p><b>안전 여유를 둔다.</b> 막 올라온 오브젝트는 등록 트랜잭션이 아직 커밋 전일 수 있다 — {@link
 * OrphanCleanupProperties#safetyMargin()}보다 최근 것은 참조가 없어도 지우지 않는다.
 *
 * <p><b>여러 태스크가 동시에 돌 때를 대비한다.</b> 평소 ECS 태스크는 하나지만(infra/terraform/ecs.tf) 배포 중 잠깐 겹칠 수 있다 —
 * {@code pg_advisory_xact_lock}으로 한 번에 하나만 정리하게 한다 (#144와 같은 패턴).
 */
@Component
public class OrphanObjectCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(OrphanObjectCleanupJob.class);

  /** 이 작업 전용 자문 잠금 키. 이슈 번호를 그대로 쓴다 — #144의 관례. */
  private static final long LOCK_KEY = 339L;

  /** 정리 대상이 되는 최종 위치 접두사. 각각의 임시 접두사({@code +"uploads/"})는 건드리지 않는다. */
  private static final List<String> FINAL_PREFIXES = List.of("notes/", "photos/");

  private final S3Client s3;
  private final StorageProperties storageProperties;
  private final OrphanCleanupProperties cleanupProperties;
  private final NoteRepository notes;
  private final PhotoRepository photos;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transaction;

  public OrphanObjectCleanupJob(
      S3Client s3,
      StorageProperties storageProperties,
      OrphanCleanupProperties cleanupProperties,
      NoteRepository notes,
      PhotoRepository photos,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this.s3 = s3;
    this.storageProperties = storageProperties;
    this.cleanupProperties = cleanupProperties;
    this.notes = notes;
    this.photos = photos;
    this.jdbcTemplate = jdbcTemplate;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * <b>시간대를 명시한다</b> (#342 리뷰). 컨테이너에는 {@code TZ}가 없어 JVM 기본 시간대가 UTC다 — 적지 않으면 "새벽 4시"로 적은 cron이
   * <b>한국 시간 오후 1시</b>에 돌아, 사용량이 적은 시간을 고른 의도와 정반대로 S3 전체 나열·삭제가 한낮 요청과 겹친다.
   */
  @Scheduled(cron = "${app.storage.orphan-cleanup.cron}", zone = "Asia/Seoul")
  public void run() {
    Boolean acted =
        transaction.execute(
            status -> {
              /*
               * pg_advisory_xact_lock이 아니라 try 버전을 쓴다 — 이 작업은 다음 주기에
               * 다시 돌면 그만이므로, 다른 태스크가 이미 돌고 있으면 기다리지 않고 건너뛴다.
               * 트랜잭션이 끝나면 잡았든 못 잡았든 자동으로 풀린다(_xact_).
               */
              Boolean locked =
                  jdbcTemplate.queryForObject(
                      "SELECT pg_try_advisory_xact_lock(" + LOCK_KEY + ")", Boolean.class);
              if (!Boolean.TRUE.equals(locked)) {
                log.info("다른 인스턴스가 이미 고아 오브젝트를 정리하고 있어 이번 주기는 건너뛴다");
                return false;
              }
              cleanup();
              return true;
            });
    if (acted == null) {
      log.warn("고아 오브젝트 정리 트랜잭션이 결과 없이 끝났다");
    }
  }

  private void cleanup() {
    Set<String> referenced = referencedKeys();
    Instant cutoff = Instant.now().minus(cleanupProperties.safetyMargin());
    int deleted = 0;
    int skipped = 0;

    for (String prefix : FINAL_PREFIXES) {
      String tempPrefix = prefix + "uploads/";
      for (S3Object object : listUnderPrefix(prefix)) {
        Verdict verdict =
            classify(object.key(), object.lastModified(), tempPrefix, referenced, cutoff);
        if (verdict == Verdict.SKIP_TOO_FRESH) {
          skipped++;
        }
        if (verdict != Verdict.DELETE) {
          continue;
        }
        s3.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(object.key())
                .build());
        log.warn("참조를 잃은 오브젝트를 지웠다: key={} lastModified={}", object.key(), object.lastModified());
        deleted++;
      }
    }
    log.info("고아 오브젝트 정리 완료: 지움={}건 안전여유로유보={}건", deleted, skipped);
  }

  /** {@link #cleanup}의 판단 하나하나. 순수 함수라 MinIO 없이도 경계를 딱 잘라 잴 수 있다. */
  enum Verdict {
    DELETE,
    SKIP_TEMPORARY,
    SKIP_REFERENCED,
    SKIP_TOO_FRESH
  }

  /**
   * 오브젝트 하나를 지울지 판단한다.
   *
   * <p>순서가 뜻을 담는다 — <b>임시 위치인지</b>를 가장 먼저 본다. 그다음 참조 여부, 마지막이 안전 여유다. 참조된 임시 오브젝트란 있을 수 없지만(임시 키는
   * DB에 저장되지 않는다), 순서를 지켜 두면 그 사실이 바뀌어도 임시 위치가 여전히 먼저 걸러진다.
   */
  static Verdict classify(
      String key, Instant lastModified, String tempPrefix, Set<String> referenced, Instant cutoff) {
    if (key.startsWith(tempPrefix)) {
      return Verdict.SKIP_TEMPORARY;
    }
    if (referenced.contains(key)) {
      return Verdict.SKIP_REFERENCED;
    }
    if (lastModified.isAfter(cutoff)) {
      return Verdict.SKIP_TOO_FRESH;
    }
    return Verdict.DELETE;
  }

  /** 지금 DB가 참조하는 최종 위치 키 전체. 사진은 본 이미지 키에서 썸네일 키를 유도해 함께 넣는다. */
  private Set<String> referencedKeys() {
    Set<String> keys = new HashSet<>(notes.findAllFileStoredPaths());
    for (String storedPath : photos.findAllStoredPaths()) {
      keys.add(storedPath);
      keys.add(PhotoService.thumbnailKeyOf(storedPath));
    }
    return keys;
  }

  private List<S3Object> listUnderPrefix(String prefix) {
    List<S3Object> found = new ArrayList<>();
    String continuationToken = null;
    do {
      ListObjectsV2Request.Builder request =
          ListObjectsV2Request.builder().bucket(storageProperties.bucket()).prefix(prefix);
      if (continuationToken != null) {
        request.continuationToken(continuationToken);
      }
      ListObjectsV2Response response = s3.listObjectsV2(request.build());
      found.addAll(response.contents());
      continuationToken = response.nextContinuationToken();
    } while (continuationToken != null);
    return found;
  }
}
