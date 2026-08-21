package org.hackerkhu.hackerhp.global.storage;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 도메인에 속하지 않는 S3 오브젝트 조작 (#57). 버킷은 하나뿐이라 인자로 받지 않고 {@link PhotoStorageProperties}에서 읽는다.
 *
 * <p>키 네이밍(어디에 무엇을 두는지)은 이 클래스의 책임이 아니다 — 그건 호출하는 도메인(예: {@code PhotoService})이 정한다. 여기는 순수하게 "그 키로
 * 무엇을 할 수 있는가"만 안다.
 *
 * <p>{@code @Qualifier}로 받는 이유는 {@link PhotoStorageConfig#QUALIFIER}를 참고 — 자료(#207)가 만드는
 * {@code S3Client}·{@code S3Presigner}와 빈 타입이 같아서 그냥 두면 어느 것을 주입할지 알 수 없다.
 */
@Service
public class S3StorageService {

  /** presigned URL 유효시간. 짧게 두되(spec 2-1 §2-1-4) 업로드·조회 한 번을 끝내기엔 충분한 값이다. */
  private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final String bucket;

  public S3StorageService(
      @Qualifier(PhotoStorageConfig.QUALIFIER) S3Client s3Client,
      @Qualifier(PhotoStorageConfig.QUALIFIER) S3Presigner s3Presigner,
      PhotoStorageProperties properties) {
    this.s3Client = s3Client;
    this.s3Presigner = s3Presigner;
    this.bucket = properties.bucket();
  }

  public String presignPut(String key, String contentType) {
    PutObjectRequest request =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(PRESIGN_TTL)
            .putObjectRequest(request)
            .build();
    return s3Presigner.presignPutObject(presignRequest).url().toString();
  }

  public String presignGet(String key) {
    GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(PRESIGN_TTL)
            .getObjectRequest(request)
            .build();
    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }

  public byte[] download(String key) {
    GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
    return s3Client.getObject(request, ResponseTransformer.toBytes()).asByteArray();
  }

  public void upload(String key, byte[] content, String contentType) {
    PutObjectRequest request =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
    s3Client.putObject(request, RequestBody.fromBytes(content));
  }

  public void delete(String key) {
    s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }
}
