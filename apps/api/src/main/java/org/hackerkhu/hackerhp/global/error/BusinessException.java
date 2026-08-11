package org.hackerkhu.hackerhp.global.error;

/**
 * 계약(spec/3-2 §3-2-7)에 정의된 코드로 응답해야 하는 상황에 던진다.
 *
 * <p>코드마다 예외 클래스를 두지 않는다 — MVP에는 코드로 {@code catch}를 가르는 자리가 없다. 응답을 가르는 것은 클래스가 아니라 {@link
 * ErrorCode}다.
 *
 * <p>대부분은 {@code new BusinessException(PENDING_APPROVAL)}처럼 기본 메시지를 쓰고, 상황 설명이 필요할 때만 메시지를 덮어쓴다.
 * 덮어쓴 문장도 사용자에게 보이므로 내부 정보를 담지 않는다.
 */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }

  public BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
