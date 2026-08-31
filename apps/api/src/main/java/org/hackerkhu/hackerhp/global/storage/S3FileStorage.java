package org.hackerkhu.hackerhp.global.storage;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
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
  public URI presignPut(String key, String contentType) {
    PutObjectRequest.Builder put = PutObjectRequest.builder().bucket(properties.bucket()).key(key);
    if (contentType != null) {
      put.contentType(contentType);
    }
    PutObjectPresignRequest request =
        PutObjectPresignRequest.builder()
            .signatureDuration(properties.presignTtl())
            .putObjectRequest(put.build())
            .build();
    return URI.create(presigner.presignPutObject(request).url().toString());
  }

  /**
   * 내려받기 URL. <b>파일명과 {@code attachment}를 서명에 담는다</b> (#55).
   *
   * <p>서명에 들어가므로 <b>받는 쪽이 바꿀 수 없다.</b> 쿼리스트링을 손대면 서명이 깨져 S3가 거절한다.
   *
   * <p>파일명은 RFC 5987의 {@code filename*}로만 싣는다. 한글·공백·따옴표가 들어간 이름이 흔한데, 옛 {@code filename="…"} 형식은
   * 그것을 안전하게 담지 못한다 — 지금 브라우저는 모두 {@code filename*}을 읽는다.
   */
  @Override
  public URI presignGet(String key, String originalName) {
    GetObjectPresignRequest request =
        GetObjectPresignRequest.builder()
            .signatureDuration(properties.downloadPresignTtl())
            .getObjectRequest(
                GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .responseContentDisposition(contentDisposition(originalName))
                    .build())
            .build();
    return URI.create(presigner.presignGetObject(request).url().toString());
  }

  /**
   * 활동사진(#57)의 {@code <img src>}가 쓴다 — {@code Content-Disposition}을 담지 않아 브라우저가 바로 그린다 (#213 통합).
   */
  @Override
  public URI presignGet(String key) {
    GetObjectPresignRequest request =
        GetObjectPresignRequest.builder()
            .signatureDuration(properties.downloadPresignTtl())
            .getObjectRequest(
                GetObjectRequest.builder().bucket(properties.bucket()).key(key).build())
            .build();
    return URI.create(presigner.presignGetObject(request).url().toString());
  }

  /**
   * {@code attachment; filename*=UTF-8''%EC%A0%95%EB%A6%AC%EB%B3%B8.pdf} 꼴로 만든다.
   *
   * <p><b>{@code URLEncoder}를 그대로 쓸 수 없다.</b> 그것은 폼 인코딩이고 RFC 5987이 요구하는 것과 두 군데가 어긋난다.
   *
   * <table>
   *   <caption>손봐야 하는 두 글자</caption>
   *   <tr><th>글자<th>{@code URLEncoder}<th>왜 안 되나
   *   <tr><td>공백<td>{@code +}<td>RFC 5987은 {@code +}를 <b>더하기 기호 그대로</b> 읽는다 — 이름에 {@code +}가 박힌 채 저장된다
   *   <tr><td>{@code *}<td>그대로 둠<td>{@code attr-char}에 없다 — <b>엄격한 클라이언트는 이 파라미터를 통째로 무시</b>하고, 사용자는 uuid 이름으로 받는다
   * </table>
   *
   * <p>나머지 예약 문자({@code . - _})는 {@code attr-char}에 있어 그대로 두어도 된다.
   */
  private static String contentDisposition(String originalName) {
    String encoded =
        URLEncoder.encode(originalName, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A");
    return "attachment; filename*=UTF-8''" + encoded;
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

  /**
   * 활동사진(#57)의 리사이즈가 원본을 읽을 때 쓴다 (#213 통합). 없는 키는 그대로 {@link S3Exception}을 던진다 — 부르는 쪽이 {@link
   * #describe}로 먼저 존재를 확인했어야 한다.
   */
  @Override
  public byte[] download(String key) {
    return s3.getObject(
            GetObjectRequest.builder().bucket(properties.bucket()).key(key).build(),
            ResponseTransformer.toBytes())
        .asByteArray();
  }

  /** 활동사진(#57)의 리사이즈 결과·썸네일을 최종 키에 쓸 때 쓴다 (#213 통합). */
  @Override
  public void upload(String key, byte[] content, String contentType) {
    s3.putObject(
        PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(key)
            .contentType(contentType)
            .build(),
        RequestBody.fromBytes(content));
  }
}
