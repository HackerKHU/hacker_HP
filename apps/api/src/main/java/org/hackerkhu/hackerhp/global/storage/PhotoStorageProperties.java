package org.hackerkhu.hackerhp.global.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 활동사진(#57) 전용 S3 설정. {@code bucket}·{@code region}은 값이 없으면 기동에 실패한다 — {@code
 * app.auth.allowed-email-domain}과 같은 이유다: 기본값을 심어두면 설정 누락이 조용히 지나간다.
 *
 * <p><b>자료(#207)의 {@link StorageProperties}와 따로 둔다.</b> 자료는 서버가 바이트를 절대 만지지 않고(presigned PUT →
 * 서버사이드 COPY) 로컬에서도 실제 버킷 없이 자리표시자 값으로 기동만 확인하지만, 사진은 서버가 원본을 내려받아 리사이즈해 다시 올려야 해서 로컬에서도 실제로 검증할 S3
 * 호환 서버(MinIO)가 필요하다 — 그래서 여기만 {@code endpoint}·{@code accessKey}·{@code secretKey}를 갖는다. 두 추상화를
 * 하나로 합칠지는 #213에서 별도로 정한다.
 *
 * <p>{@code endpoint}·{@code accessKey}·{@code secretKey}는 로컬(MinIO) 전용이다. 운영에서는 비워 둔다 — 그러면 {@link
 * PhotoStorageConfig}가 AWS SDK 기본 자격 증명 체인(ECS Task Role)을 그대로 쓴다. 로컬·운영이 같은 코드 경로를 타되 설정값만 갈리는
 * 구조다.
 *
 * @param bucket S3 버킷 이름. 운영은 ECS가 {@code S3_BUCKET} 환경변수로 주입한다 (infra/terraform/ecs.tf)
 * @param region 버킷 리전
 * @param endpoint MinIO 등 S3 호환 엔드포인트. 비어 있으면 실제 AWS S3를 쓴다
 * @param accessKey 로컬 전용 정적 자격 증명. 운영에서는 쓰지 않는다
 * @param secretKey 로컬 전용 정적 자격 증명. 운영에서는 쓰지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "app.photo-storage")
public record PhotoStorageProperties(
    @NotBlank String bucket,
    @NotBlank String region,
    String endpoint,
    String accessKey,
    String secretKey) {

  public boolean hasCustomEndpoint() {
    return endpoint != null && !endpoint.isBlank();
  }
}
