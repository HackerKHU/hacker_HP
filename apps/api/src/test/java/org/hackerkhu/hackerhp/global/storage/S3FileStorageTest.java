package org.hackerkhu.hackerhp.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

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
  private S3Presigner presigner;
  private S3FileStorage storage;

  @BeforeEach
  void setUp() {
    s3 = mock(S3Client.class);
    presigner = mock(S3Presigner.class);
    storage =
        new S3FileStorage(
            s3,
            presigner,
            new StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                null,
                null,
                null));
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

  /* ------------------------------------------------- 내려받기 서명 (#55) */

  /**
   * <b>파일명을 서명에 담는다.</b>
   *
   * <p>S3 키는 {@code uuid}라, 담지 않으면 사용자 디스크에 알아볼 수 없는 이름으로 저장된다. 프론트의 {@code <a download="…">}로는 고칠
   * 수 없다 — 그 힌트는 다른 오리진 링크에서 무시된다.
   */
  @Test
  void theDownloadSignatureCarriesTheOriginalName() {
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(presignedGet());

    storage.presignGet("notes/uuid.pdf", "정리본.pdf");

    ArgumentCaptor<GetObjectPresignRequest> captured =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(presigner).presignGetObject(captured.capture());
    String disposition = captured.getValue().getObjectRequest().responseContentDisposition();

    assertThat(disposition).startsWith("attachment; filename*=UTF-8''");
    // 한글은 퍼센트 인코딩된다 — 옛 filename="…" 형식으로는 안전하게 담기지 않는다.
    assertThat(disposition).contains(URLEncoder.encode("정리본.pdf", StandardCharsets.UTF_8));
  }

  /**
   * <b>공백은 {@code +}가 아니라 {@code %20}이다.</b>
   *
   * <p>{@code URLEncoder}는 폼 인코딩이라 공백을 {@code +}로 바꾸는데, RFC 5987은 그것을 더하기 기호로 읽는다 — 파일명에 {@code +}가
   * 박힌 채 저장된다.
   */
  @Test
  void spacesAreEncodedForRfc5987NotForForms() {
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(presignedGet());

    storage.presignGet("notes/uuid.pdf", "운영체제 정리본.pdf");

    ArgumentCaptor<GetObjectPresignRequest> captured =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(presigner).presignGetObject(captured.capture());
    String disposition = captured.getValue().getObjectRequest().responseContentDisposition();

    assertThat(disposition).contains("%20").doesNotContain("+");
  }

  /**
   * <b>{@code *}도 인코딩한다</b> (#208 리뷰).
   *
   * <p>{@code URLEncoder}는 {@code *}를 그대로 두는데, RFC 5987의 {@code attr-char}에는 없는 글자다. 엄격한 클라이언트는
   * <b>파라미터를 통째로 무시</b>하고, 그러면 사용자는 uuid 이름으로 받는다. Linux·macOS에서는 이런 이름의 파일을 만들 수 있다.
   */
  @Test
  void asterisksAreEncodedToo() {
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(presignedGet());

    storage.presignGet("notes/uuid.pdf", "중간고사*정리.pdf");

    ArgumentCaptor<GetObjectPresignRequest> captured =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(presigner).presignGetObject(captured.capture());
    String disposition = captured.getValue().getObjectRequest().responseContentDisposition();

    // 앞머리의 filename* 은 파라미터 이름이라 그대로다. 값 안에 별표가 남으면 안 된다.
    assertThat(disposition.substring(disposition.indexOf("UTF-8''"))).doesNotContain("*");
    assertThat(disposition).contains("%2A");
  }

  /** 남겨 두어야 하는 글자까지 건드리지 않는다 — {@code . - _}는 {@code attr-char}에 있다. */
  @Test
  void charactersAllowedByRfc5987AreLeftAlone() {
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(presignedGet());

    storage.presignGet("notes/uuid.pdf", "os-final_2025.pdf");

    ArgumentCaptor<GetObjectPresignRequest> captured =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(presigner).presignGetObject(captured.capture());

    assertThat(captured.getValue().getObjectRequest().responseContentDisposition())
        .endsWith("os-final_2025.pdf");
  }

  /** 내려받기는 업로드보다 짧은 수명을 쓴다 (#55 D3). */
  @Test
  void theDownloadUrlUsesTheShorterTtl() {
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(presignedGet());

    storage.presignGet("notes/uuid.pdf", "정리본.pdf");

    ArgumentCaptor<GetObjectPresignRequest> captured =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(presigner).presignGetObject(captured.capture());

    assertThat(captured.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(1));
  }

  private static Answer<PresignedGetObjectRequest> presignedGet() {
    return invocation -> {
      PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
      when(presigned.url()).thenReturn(new URL("https://bucket.s3.test/notes/uuid.pdf?sig=1"));
      return presigned;
    };
  }

  /* ------------------------------------------------------- 조회 서명, inline (활동사진 #57, #213 통합) */

  /**
   * <b>{@code Content-Disposition}을 담지 않는다.</b> {@code <img src>}가 바로 그려야 하는 자리라 "받는" 동작을 강제하면 안
   * 된다.
   */
  @Test
  void inlinePresignGetCarriesNoDisposition() {
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(presignedGet());

    storage.presignGet("photos/1/uuid.jpg");

    ArgumentCaptor<GetObjectPresignRequest> captured =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(presigner).presignGetObject(captured.capture());
    assertThat(captured.getValue().getObjectRequest().responseContentDisposition()).isNull();
  }

  /* ------------------------------------------------------- 올리기 서명 (활동사진 #57, #213 통합) */

  private static Answer<PresignedPutObjectRequest> presignedPut() {
    return invocation -> {
      PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
      when(presigned.url())
          .thenReturn(new URL("https://bucket.s3.test/photos/uploads/uuid.jpg?sig=1"));
      return presigned;
    };
  }

  /**
   * <b>{@code Content-Type}을 서명에 실을 수 있다</b> — 활동사진은 확장자별로 정해진 형식을 강제한다. 자료(#207)는 {@code null}을 넘겨
   * 강제하지 않는다({@link #presignPutWithoutAContentTypeOmitsIt}).
   */
  @Test
  void presignPutCarriesTheGivenContentType() {
    when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenAnswer(presignedPut());

    storage.presignPut("photos/uploads/uuid.jpg", "image/jpeg");

    ArgumentCaptor<PutObjectPresignRequest> captured =
        ArgumentCaptor.forClass(PutObjectPresignRequest.class);
    verify(presigner).presignPutObject(captured.capture());
    assertThat(captured.getValue().putObjectRequest().contentType()).isEqualTo("image/jpeg");
  }

  /** {@code null}이면 서명에 아무 형식도 싣지 않는다 — 자료(#207)는 올리는 파일 형식이 다양해 강제하지 않는다. */
  @Test
  void presignPutWithoutAContentTypeOmitsIt() {
    when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenAnswer(presignedPut());

    storage.presignPut("notes/uploads/1/uuid.pdf", null);

    ArgumentCaptor<PutObjectPresignRequest> captured =
        ArgumentCaptor.forClass(PutObjectPresignRequest.class);
    verify(presigner).presignPutObject(captured.capture());
    assertThat(captured.getValue().putObjectRequest().contentType()).isNull();
  }
}
