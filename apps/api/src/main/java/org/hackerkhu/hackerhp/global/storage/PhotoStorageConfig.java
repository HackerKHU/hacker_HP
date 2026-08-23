package org.hackerkhu.hackerhp.global.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 활동사진(#57) 전용 S3 클라이언트 빈. 자격 증명은 {@link PhotoStorageProperties#hasCustomEndpoint()}로 갈린다.
 *
 * <p><b>자료(#207)의 {@code StorageConfig}가 만드는 {@code S3Client}·{@code S3Presigner}와 별개다.</b> 그쪽은
 * 로컬에서도 MinIO 같은 엔드포인트 오버라이드를 지원하지 않는다 — 사진은 서버가 원본을 내려받아 리사이즈해야 해서 로컬에서도 실제로 검증할 S3 호환 서버가 필요하다
 * (자세한 이유는 {@link PhotoStorageProperties} 참고). 빈 이름이 겹치면 어느 쪽을 주입할지 알 수 없으므로 {@code @Qualifier}로 갈라
 * 둔다. 두 클라이언트를 하나로 합칠지는 #213에서 정한다.
 *
 * <p><b>운영은 정적 키를 쓰지 않는다</b> (docs/ops/infra.md MUST). {@code accessKey}·{@code secretKey}를 비워 두면
 * AWS SDK 기본 자격 증명 체인이 ECS Task Role을 찾아 쓴다. 로컬(MinIO)만 {@code endpointOverride}와 정적 키가 필요하다 —
 * MinIO는 IAM 역할이 없는 독립 서버라 그것 말고는 인증할 방법이 없다.
 */
@Configuration
@EnableConfigurationProperties(PhotoStorageProperties.class)
public class PhotoStorageConfig {

  static final String QUALIFIER = "photoStorage";

  @Bean
  @Qualifier(QUALIFIER)
  public S3Client photoS3Client(PhotoStorageProperties properties) {
    return configure(S3Client.builder(), properties).build();
  }

  @Bean
  @Qualifier(QUALIFIER)
  public S3Presigner photoS3Presigner(PhotoStorageProperties properties) {
    S3Presigner.Builder builder = S3Presigner.builder().region(Region.of(properties.region()));
    if (properties.hasCustomEndpoint()) {
      // S3Client와 마찬가지로 path-style이어야 한다 — 안 그러면 프리사인 URL이
      // {bucket}.{endpoint}처럼 존재하지 않는 호스트로 만들어진다.
      builder
          .endpointOverride(URI.create(properties.endpoint()))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
          .credentialsProvider(staticCredentials(properties));
    }
    return builder.build();
  }

  private S3ClientBuilder configure(S3ClientBuilder builder, PhotoStorageProperties properties) {
    builder.region(Region.of(properties.region()));
    if (properties.hasCustomEndpoint()) {
      // MinIO는 버킷 이름을 호스트가 아니라 경로에 담아야 한다(path-style). 실제 S3는 둘 다
      // 받지만 MinIO는 virtual-hosted-style을 기본으로 지원하지 않는다.
      builder
          .endpointOverride(URI.create(properties.endpoint()))
          .forcePathStyle(true)
          .credentialsProvider(staticCredentials(properties));
    }
    return builder;
  }

  private StaticCredentialsProvider staticCredentials(PhotoStorageProperties properties) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
  }
}
