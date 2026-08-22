package org.hackerkhu.hackerhp.global.storage;

import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** {@link FileStorage}의 실제 구현. 자격증명은 ECS 태스크 롤에서 온다 (infra/terraform/ecs.tf). */
@Component
public class S3FileStorage implements FileStorage {

  private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

  private static final int NOT_FOUND = 404;
  private static final int PRECONDITION_FAILED = 412;

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

  /**
   * <b>{@code NoSuchKeyException}만 잡으면 부족하다</b> (#207 리뷰).
   *
   * <p>{@code HeadObject}는 응답 본문이 없어 SDK가 오류 코드를 읽을 수 없다 — 그래서 키가 없을 때 {@code NoSuchKeyException}이
   * 아니라 <b>상태 코드 404를 단 밋밋한 {@code S3Exception}</b>이 올라오는 경우가 있다. 그것까지 "없음"으로 보지 않으면, <b>업로드가 끝나기
   * 전에 등록을 부른 흔한 실수가 {@code 400}이 아니라 {@code 500}이 된다.</b>
   *
   * <p>{@code NoSuchKeyException}은 {@code S3Exception}의 하위 타입이라 이 하나로 둘 다 덮인다.
   */
  @Override
  public Optional<StoredObject> describe(String key) {
    try {
      HeadObjectResponse head =
          s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
      return Optional.of(new StoredObject(head.contentLength(), head.eTag()));
    } catch (S3Exception e) {
      if (e.statusCode() == NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /**
   * {@code copySourceIfMatch}로 <b>잰 그 내용일 때만</b> 옮긴다.
   *
   * <p>어긋나면 S3가 {@code 412}로 거절한다. 그 사이에 지워졌으면 {@code 404}다 — 둘 다 "옮길 것이 없다"이므로 같은 답을 준다.
   */
  @Override
  public boolean copyIfUnchanged(String fromKey, String toKey, String expectedEtag) {
    try {
      s3.copyObject(
          CopyObjectRequest.builder()
              .sourceBucket(properties.bucket())
              .sourceKey(fromKey)
              .copySourceIfMatch(expectedEtag)
              .destinationBucket(properties.bucket())
              .destinationKey(toKey)
              .build());
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == PRECONDITION_FAILED || e.statusCode() == NOT_FOUND) {
        log.warn("잰 뒤에 바뀐 오브젝트라 옮기지 않았다: key={} status={}", fromKey, e.statusCode());
        return false;
      }
      throw e;
    }
  }

  /**
   * <b>실패를 삼키지 않는다</b> (#207 리뷰).
   *
   * <p>최종 자리에는 만료 규칙이 없다 — 삼키면 DB 행도 만료 규칙도 없는 오브젝트가 영원히 쌓이고, 부르는 쪽은 그 사실조차 모른다. 삼킬 수 있는 자리(임시본
   * 정리)는 부르는 쪽이 스스로 감싼다.
   *
   * <p>없는 키를 지우는 것은 S3가 성공으로 답한다 — 그것까지 실패로 볼 이유는 없다.
   */
  @Override
  public void delete(String key) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
  }
}
