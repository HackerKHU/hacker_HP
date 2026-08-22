package org.hackerkhu.hackerhp.global.error;

import org.springframework.http.HttpStatus;

/**
 * 응답으로 나가는 에러 코드. 원본은 spec/3-2-DESIGN-CONTRACT.md §3-2-7 표다.
 *
 * <p>여기에 코드를 더하거나 빼면 그 표와 {@code apps/web/src/api/types.ts}의 {@code ERROR_CODES}를 같은 PR에서 갱신한다
 * (spec/5-TESTING.md §5-4 MUST). 웹은 목록에 없는 코드를 {@code INVALID_RESPONSE}로 격리하므로, 한쪽만 고치면 서버가 보낸 사유가
 * 화면에서 사라진다.
 *
 * <p>{@code message}는 사용자에게 그대로 보여줄 수 있는 문장이어야 한다. 스택 트레이스·SQL·내부 경로를 담지 않는다.
 */
public enum ErrorCode {
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  PENDING_APPROVAL(HttpStatus.FORBIDDEN, "승인 대기 중인 계정입니다."),
  SUSPENDED(HttpStatus.FORBIDDEN, "정지된 계정입니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
  DUPLICATE_STUDENT_NO(HttpStatus.CONFLICT, "이미 등록된 학번입니다."),
  CONCURRENT_CHANGE(HttpStatus.CONFLICT, "다른 관리자가 방금 이 회원을 바꿨습니다. 목록을 새로고침하고 다시 시도해 주세요."),
  FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일 용량이 너무 큽니다."),
  UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "허용되지 않는 파일 형식입니다.");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }
}
