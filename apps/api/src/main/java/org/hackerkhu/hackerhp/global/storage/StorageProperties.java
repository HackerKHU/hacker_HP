package org.hackerkhu.hackerhp.global.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3 저장소 설정.
 *
 * <p>{@code bucket}이 없으면 기동에 실패한다. 기본값을 심으면 <b>설정 누락이 조용히 지나가고 업로드가 엉뚱한 버킷을 가리킨다</b> — 운영 값은 태스크
 * 정의가 {@code S3_BUCKET}으로 항상 주입한다 (infra/terraform/ecs.tf).
 *
 * @param bucket 자료·사진이 들어가는 버킷. <b>퍼블릭 액세스가 차단돼 있다</b> — presigned URL 말고는 여는 길이 없다
 * @param region 버킷 리전
 * @param presignTtl 발급한 URL의 수명. <b>짧게 둔다</b> — 새어나가도 창이 좁고, 만료되면 다시 발급받으면 된다 (#53 D5)
 */
@Validated
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
    @NotBlank String bucket, @NotBlank String region, @NotNull Duration presignTtl) {}
