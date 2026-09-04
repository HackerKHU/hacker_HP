package org.hackerkhu.hackerhp.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.hackerkhu.hackerhp.global.storage.OrphanObjectCleanupJob.Verdict;
import org.junit.jupiter.api.Test;

/**
 * {@link OrphanObjectCleanupJob#classify}만 본다 (#339).
 *
 * <p><b>MinIO 없이 순수 함수로 잰다.</b> 확인하려는 것이 "S3를 실제로 지우는가"가 아니라 <b>어떤 오브젝트를 지울지 판단하는 규칙</b>이다 — 그 규칙은
 * S3 없이도 잴 수 있다. 실제 나열·삭제 왕복은 {@code OrphanObjectCleanupJobIntegrationTest}가 MinIO로 본다.
 */
class OrphanObjectCleanupJobTest {

  private static final String TEMP_PREFIX = "notes/uploads/";
  private static final Instant NOW = Instant.parse("2026-08-31T04:00:00Z");
  private static final Instant CUTOFF = NOW.minus(1, ChronoUnit.HOURS);

  /** 임시 위치는 참조 여부·나이와 무관하게 언제나 건드리지 않는다 — lifecycle 규칙의 몫이다. */
  @Test
  void temporaryLocationIsNeverTouched() {
    assertThat(
            OrphanObjectCleanupJob.classify(
                "notes/uploads/1/x.pdf",
                CUTOFF.minus(1, ChronoUnit.DAYS),
                TEMP_PREFIX,
                Set.of(),
                CUTOFF))
        .isEqualTo(Verdict.SKIP_TEMPORARY);
  }

  /** 임시 위치 판단이 참조 판단보다 먼저다 — 순서 자체가 이 클래스의 계약이다. */
  @Test
  void temporaryLocationWinsEvenIfSomehowReferenced() {
    String key = "notes/uploads/1/x.pdf";
    assertThat(
            OrphanObjectCleanupJob.classify(
                key, CUTOFF.minus(1, ChronoUnit.DAYS), TEMP_PREFIX, Set.of(key), CUTOFF))
        .isEqualTo(Verdict.SKIP_TEMPORARY);
  }

  /** DB가 참조하는 최종 위치 키는 나이와 무관하게 지우지 않는다. */
  @Test
  void referencedFinalLocationSurvives() {
    String key = "notes/x.pdf";
    assertThat(
            OrphanObjectCleanupJob.classify(
                key, CUTOFF.minus(1, ChronoUnit.DAYS), TEMP_PREFIX, Set.of(key), CUTOFF))
        .isEqualTo(Verdict.SKIP_REFERENCED);
  }

  /** 참조가 없고 안전 여유 이내(막 올라옴)면 지우지 않는다 — 등록 트랜잭션이 진행 중일 수 있다. */
  @Test
  void unreferencedButFreshObjectSurvives() {
    Instant justInside = CUTOFF.plus(1, ChronoUnit.SECONDS);
    assertThat(
            OrphanObjectCleanupJob.classify(
                "notes/x.pdf", justInside, TEMP_PREFIX, Set.of(), CUTOFF))
        .isEqualTo(Verdict.SKIP_TOO_FRESH);
  }

  /** 참조가 없고 안전 여유를 지났으면 지운다 — 이 클래스가 존재하는 이유. */
  @Test
  void unreferencedAndOldEnoughIsDeleted() {
    Instant justOutside = CUTOFF.minus(1, ChronoUnit.SECONDS);
    assertThat(
            OrphanObjectCleanupJob.classify(
                "notes/x.pdf", justOutside, TEMP_PREFIX, Set.of(), CUTOFF))
        .isEqualTo(Verdict.DELETE);
  }

  /** 경계 그 자체(정확히 cutoff)는 "여유 이내"로 본다 — {@code isAfter}는 같은 시각을 뒤로 보지 않는다. */
  @Test
  void exactlyAtTheCutoffIsStillDeleted() {
    assertThat(
            OrphanObjectCleanupJob.classify("notes/x.pdf", CUTOFF, TEMP_PREFIX, Set.of(), CUTOFF))
        .isEqualTo(Verdict.DELETE);
  }
}
