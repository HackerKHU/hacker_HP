package org.hackerkhu.hackerhp.global.storage;

import java.net.URI;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** {@link FileStorage}의 실제 구현. 자격증명은 ECS 태스크 롤에서 온다 (infra/terraform/ecs.tf). */
@Component
public class S3FileStorage implements FileStorage {

  private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final StorageProperties properties;

  public S3FileStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
    this.s3 = s3;
    this.presigner = presigner;
    this.properties = properties;
  }

  @Override
  public URI presignPut(String key) {
    PutObjectPresignRequest request =
        PutObjectPresignRequest.builder()
            .signatureDuration(properties.presignTtl())
            .putObjectRequest(
                PutObjectRequest.builder().bucket(properties.bucket()).key(key).build())
            .build();
    return URI.create(presigner.presignPutObject(request).url().toString());
  }

  @Override
  public OptionalLong sizeOf(String key) {
    try {
      return OptionalLong.of(
          s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build())
              .contentLength());
    } catch (NoSuchKeyException e) {
      return OptionalLong.empty();
    }
  }

  @Override
  public void copy(String fromKey, String toKey) {
    s3.copyObject(
        CopyObjectRequest.builder()
            .sourceBucket(properties.bucket())
            .sourceKey(fromKey)
            .destinationBucket(properties.bucket())
            .destinationKey(toKey)
            .build());
  }

  @Override
  public void delete(String key) {
    /*
     * S3의 DeleteObject는 없는 키에도 성공을 돌려준다. 그래도 감싸 두는 것은 권한·네트워크
     * 실패까지 조용히 지나가면 안 되기 때문이다 — 정리는 실패해도 본 작업을 되돌릴 일이
     * 아니므로 알리고 넘어간다 (고아는 라이프사이클 규칙이 하루 뒤에 걷어간다).
     */
    try {
      s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    } catch (RuntimeException e) {
      log.error("오브젝트 삭제 실패: key={}", key, e);
    }
  }
}
