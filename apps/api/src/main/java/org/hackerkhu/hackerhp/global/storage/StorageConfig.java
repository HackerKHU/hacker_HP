package org.hackerkhu.hackerhp.global.storage;

import java.net.URI;
import org.hackerkhu.hackerhp.domain.note.service.NoteUploadPolicy;
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
 * S3 클라이언트. 자료(#207)·활동사진(#57)이 함께 쓴다 — 원래 각자 만들었는데, 같은 버킷을 가리켜 하나로 합쳤다(#213, 예전 {@code
 * PhotoStorageConfig}).
 *
 * <p><b>운영은 자격증명을 코드에서 다루지 않는다.</b> 기본 체인이 ECS 태스크 롤에서 가져온다 (infra/terraform/ecs.tf) — 키를 환경변수나 설정에
 * 두면 그 순간 시크릿 관리 대상이 하나 늘고, 롤이 이미 하는 일을 다시 하는 셈이다. {@code StorageProperties#endpoint}가 비어 있으면(운영은
 * 항상 비어 있다) 이 체인만 쓴다.
 *
 * <p><b>로컬은 MinIO를 붙인다.</b> 활동사진은 서버가 원본을 내려받아 리사이즈해야 해서 로컬에서도 실제로 검증할 S3 호환 서버가 필요하다 — 자료는 바이트를
 * 만지지 않아 이 오버라이드 없이도(자리표시자 버킷 값으로) 기동만 확인한다. MinIO는 IAM 역할이 없는 독립 서버라 정적 키로만 인증한다.
 *
 * <p>빈을 만드는 시점에는 자격증명을 찾지 않는다(요청할 때 찾는다, MinIO 분기 제외). 그래서 <b>자격증명 없는 환경에서도 컨텍스트는 뜬다</b> — 테스트가
 * {@link FileStorage}를 가짜로 갈아끼울 수 있는 것도 이 덕분이다.
 */
@Configuration
// 업로드 정책도 여기서 등록한다 — "무엇을 어디에 담나"는 함께 읽어야 뜻이 통한다.
@EnableConfigurationProperties({StorageProperties.class, NoteUploadPolicy.class})
public class StorageConfig {

  @Bean
  S3Client s3Client(StorageProperties properties) {
    S3ClientBuilder builder = S3Client.builder().region(Region.of(properties.region()));
    if (properties.hasCustomEndpoint()) {
      // MinIO는 버킷 이름을 호스트가 아니라 경로에 담아야 한다(path-style). 실제 S3는 둘 다
      // 받지만 MinIO는 virtual-hosted-style을 기본으로 지원하지 않는다.
      builder
          .endpointOverride(URI.create(properties.endpoint()))
          .forcePathStyle(true)
          .credentialsProvider(staticCredentials(properties));
    }
    return builder.build();
  }

  @Bean
  S3Presigner s3Presigner(StorageProperties properties) {
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

  private StaticCredentialsProvider staticCredentials(StorageProperties properties) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
  }
}
