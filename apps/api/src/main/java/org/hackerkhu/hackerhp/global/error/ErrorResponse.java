package org.hackerkhu.hackerhp.global.error;

/**
 * 오류 응답 본문. 형식은 spec/5-TESTING.md §5-4가 원본이다.
 *
 * <pre>{@code
 * { "code": "PENDING_APPROVAL", "message": "승인 대기 중인 계정입니다." }
 * }</pre>
 *
 * <p>필드를 더하지 않는다. 웹은 이 두 개만 읽는다.
 */
public record ErrorResponse(String code, String message) {

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage());
  }

  public static ErrorResponse of(ErrorCode errorCode, String message) {
    return new ErrorResponse(errorCode.name(), message);
  }
}
