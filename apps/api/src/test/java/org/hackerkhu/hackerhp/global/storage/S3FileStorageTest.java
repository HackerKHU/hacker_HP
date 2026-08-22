package org.hackerkhu.hackerhp.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3가 <b>실제로 어떤 예외를 던지는지</b>에 걸려 있는 부분만 본다 (#207 리뷰).
 *
 * <p>{@code FakeFileStorage}는 {@code Optional.empty()}를 직접 돌려주므로 <b>이 차이를 드러내지 못한다.</b> "없는 키"가
 * {@code 400}이 아니라 {@code 500}이 되는 회귀는 여기서만 잡힌다.
 *
 * <p>Testcontainers도 LocalStack도 쓰지 않는다 — 확인하려는 것이 <b>예외를 어떻게 읽는가</b>뿐이라 클라이언트를 흉내내는 것으로 충분하다.
 */
class S3FileStorageTest {

  private S3Client s3;
  private S3FileStorage storage;

  @BeforeEach
  void setUp() {
    s3 = mock(S3Client.class);
    storage =
        new S3FileStorage(
            s3,
            mock(S3Presigner.class),
            new StorageProperties("test-bucket", "ap-northeast-2", Duration.ofMinutes(5)));
  }

  private static S3Exception withStatus(int status) {
    return (S3Exception)
        S3Exception.builder()
            .statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().errorMessage("무응답 본문").build())
            .message("S3 오류")
            .build();
  }

  /**
   * <b>{@code HeadObject}의 404는 {@code NoSuchKeyException}이 아닐 수 있다.</b>
   *
   * <p>{@code HEAD} 응답에는 본문이 없어 SDK가 오류 코드를 읽지 못하고, 밋밋한 {@code S3Exception}으로 올라온다. 그것을 "없음"으로 보지
   * 않으면 <b>업로드가 끝나기 전에 등록을 부른 흔한 실수가 {@code 500}이 된다.</b>
   */
  @Test
  void aPlain404FromHeadObjectMeansTheObjectIsAbsent() {
    when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(withStatus(404));

    assertThat(storage.describe("notes/uploads/1/x.pdf")).isEmpty();
  }

  /** 타입이 붙어 오는 경우도 같다 — 둘 다 덮여야 한다. */
  @Test
  void aTypedNoSuchKeyAlsoMeansAbsent() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).message("없다").build());

    assertThat(storage.describe("notes/uploads/1/x.pdf")).isEmpty();
  }

  /** <b>그 밖의 실패는 삼키지 않는다.</b> 권한 오류를 "없음"으로 보면 원인이 사라진다. */
  @Test
  void otherFailuresAreNotSwallowed() {
    when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(withStatus(403));

    assertThatThrownBy(() -> storage.describe("notes/uploads/1/x.pdf"))
        .isInstanceOf(S3Exception.class);
  }

  @Test
  void describeCarriesSizeAndEtag() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().contentLength(2048L).eTag("\"abc\"").build());

    assertThat(storage.describe("notes/uploads/1/x.pdf"))
        .contains(new FileStorage.StoredObject(2048L, "\"abc\""));
  }

  /** 잰 뒤에 바뀌었으면 S3가 {@code 412}로 거절한다 — 옮기지 않고 그 사실을 알린다. */
  @Test
  void aChangedSourceIsNotCopied() {
    when(s3.copyObject(any(CopyObjectRequest.class))).thenThrow(withStatus(412));

    assertThat(storage.copyIfUnchanged("from", "to", "\"abc\"")).isFalse();
  }

  @Test
  void anUnchangedSourceIsCopied() {
    when(s3.copyObject(any(CopyObjectRequest.class)))
        .thenReturn(CopyObjectResponse.builder().build());

    assertThat(storage.copyIfUnchanged("from", "to", "\"abc\"")).isTrue();
  }

  /** 복사의 그 밖의 실패도 삼키지 않는다 — "안 바뀌었는데 못 옮겼다"를 못 알아채면 안 된다. */
  @Test
  void otherCopyFailuresAreNotSwallowed() {
    when(s3.copyObject(any(CopyObjectRequest.class))).thenThrow(withStatus(500));

    assertThatThrownBy(() -> storage.copyIfUnchanged("from", "to", "\"abc\""))
        .isInstanceOf(S3Exception.class);
  }
}
