package org.hackerkhu.hackerhp.global.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3 저장소 설정. 자료(#207)·활동사진(#57)이 함께 쓴다 — 원래 각자 {@code StorageProperties}·{@code
 * PhotoStorageProperties}를 따로 뒀는데, 물리적으로 같은 버킷을 가리켜 하나로 합쳤다(#213).
 *
 * <p>{@code bucket}이 없으면 기동에 실패한다. 기본값을 심으면 <b>설정 누락이 조용히 지나가고 업로드가 엉뚱한 버킷을 가리킨다</b> — 운영 값은 태스크
 * 정의가 {@code S3_BUCKET}으로 항상 주입한다 (infra/terraform/ecs.tf).
 *
 * @param bucket 자료·사진이 들어가는 버킷. <b>퍼블릭 액세스가 차단돼 있다</b> — presigned URL 말고는 여는 길이 없다
 * @param region 버킷 리전
 * @param presignTtl 업로드 URL의 수명. <b>짧게 둔다</b> — 새어나가도 창이 좁고, 만료되면 다시 발급받으면 된다 (#53 D5)
 * @param downloadPresignTtl 다운로드·조회 URL의 수명. <b>업로드보다 더 짧다</b> (#55 D3) — 업로드는 파일 여럿을 순차로 올리는 동안 살아
 *     있어야 하지만, 다운로드·조회는 발급받자마자 브라우저가 연다(활동사진의 {@code <img src>}도 같다). <b>전송이 이보다 오래 걸려도 끊기지 않는다</b>
 *     — S3는 요청이 시작될 때 서명을 보고, 그 뒤 전송은 만료와 무관하게 이어진다
 * @param endpoint MinIO 등 S3 호환 엔드포인트. <b>로컬 전용이다</b> — 비어 있으면 실제 AWS S3를 쓴다. 활동사진은 서버가 원본을 내려받아
 *     리사이즈해 다시 올려야 해서 로컬에서도 실제로 검증할 S3 호환 서버가 필요하다(#213 이전에는 이 셋을 {@code PhotoStorageProperties}가
 *     따로 가졌다). 자료는 바이트를 만지지 않아 로컬에서 이 값을 쓰지 않는다
 * @param accessKey 로컬 전용 정적 자격 증명. 운영에서는 쓰지 않는다
 * @param secretKey 로컬 전용 정적 자격 증명. 운영에서는 쓰지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
    @NotBlank String bucket,
    @NotBlank String region,
    @NotNull Duration presignTtl,
    @NotNull Duration downloadPresignTtl,
    String endpoint,
    String accessKey,
    String secretKey) {

  public boolean hasCustomEndpoint() {
    return endpoint != null && !endpoint.isBlank();
  }
}
