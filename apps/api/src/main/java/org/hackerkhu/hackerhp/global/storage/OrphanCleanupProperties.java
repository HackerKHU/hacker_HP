package org.hackerkhu.hackerhp.global.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3 고아 오브젝트 정리 작업 설정 (#339).
 *
 * <p>코드에 박지 않는 이유는 자료(#207)의 업로드 정책과 같다 — 안전 여유·주기를 바꾸는 일은 배포가 아니라 설정으로 끝나야 한다.
 *
 * @param safetyMargin 이보다 최근에 생성된 오브젝트는 참조가 없어도 지우지 않는다. 등록 트랜잭션이 진행 중인 원본을 고아로 오판하는 사고를 막는 여유다
 * @param cron 정리 작업이 도는 주기. {@code @Scheduled}의 cron 표현식 그대로 받는다
 */
@Validated
@ConfigurationProperties(prefix = "app.storage.orphan-cleanup")
public record OrphanCleanupProperties(@NotNull Duration safetyMargin, @NotBlank String cron) {}
