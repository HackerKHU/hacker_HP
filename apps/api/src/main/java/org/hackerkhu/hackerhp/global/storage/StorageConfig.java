package org.hackerkhu.hackerhp.global.storage;

import org.hackerkhu.hackerhp.domain.note.service.NoteUploadPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트.
 *
 * <p><b>자격증명을 코드에서 다루지 않는다.</b> 기본 체인이 ECS 태스크 롤에서 가져온다 (infra/terraform/ecs.tf) — 키를 환경변수나 설정에 두면
 * 그 순간 시크릿 관리 대상이 하나 늘고, 롤이 이미 하는 일을 다시 하는 셈이다.
 *
 * <p>빈을 만드는 시점에는 자격증명을 찾지 않는다(요청할 때 찾는다). 그래서 <b>자격증명 없는 환경에서도 컨텍스트는 뜬다</b> — 테스트가 {@link
 * FileStorage} 를 가짜로 갈아끼울 수 있는 것도 이 덕분이다.
 */
@Configuration
// 업로드 정책도 여기서 등록한다 — "무엇을 어디에 담나"는 함께 읽어야 뜻이 통한다.
@EnableConfigurationProperties({StorageProperties.class, NoteUploadPolicy.class})
public class StorageConfig {

  @Bean
  S3Client s3Client(StorageProperties properties) {
    return S3Client.builder().region(Region.of(properties.region())).build();
  }

  @Bean
  S3Presigner s3Presigner(StorageProperties properties) {
    return S3Presigner.builder().region(Region.of(properties.region())).build();
  }
}
