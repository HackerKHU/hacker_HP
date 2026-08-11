package org.hackerkhu.hackerhp.global.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 오류 응답을 여기서 만든다. 컨트롤러는 예외를 던지기만 하고 {@code try-catch}로 응답을 조립하지 않는다 (spec/5-TESTING.md §5-4
 * MUST).
 *
 * <p>필터 계층에서 거절되는 요청은 여기 오지 않는다. {@link ErrorResponseWriter}를 참고한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * 서버 오류에만 쓰는 코드. 계약(§3-2-7)에 없으므로 {@link ErrorCode}에도 넣지 않는다 — 그 enum은 계약 표를 그대로 비추는 자리다.
   *
   * <p>웹은 목록에 없는 코드를 {@code INVALID_RESPONSE}로 격리하고 "서버 응답을 해석하지 못했습니다."를 보여준다. 5xx는 사용자가 코드로 분기할
   * 일이 없고 §5-4도 일반 메시지만 요구하므로 그것으로 충분하다.
   */
  private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    return ResponseEntity.status(errorCode.getStatus())
        .body(ErrorResponse.of(errorCode, e.getMessage()));
  }

  /**
   * {@code @Valid} 실패. 어떤 값이 왜 거절됐는지 화면이 보여줘야 입력을 고칠 수 있다 (T-108).
   *
   * <p>메시지는 DTO의 검증 애너테이션에 적은 문장을 그대로 쓴다. 필드명·거절된 값은 담지 않는다 — 내부 이름이고 사용자가 읽을 문장도 아니다.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .filter(it -> it != null && !it.isBlank())
            .findFirst()
            .orElse(ErrorCode.VALIDATION_ERROR.getMessage());
    return respond(ErrorCode.VALIDATION_ERROR, message);
  }

  /**
   * 본문이 JSON으로 읽히지 않거나 타입이 맞지 않는다.
   *
   * <p>예외 메시지에는 파서 위치와 클래스명이 들어 있다. 그대로 내보내면 §5-4가 금지하는 내부 정보 노출이다. 기본 문장만 쓴다.
   */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception e) {
    log.debug("잘못된 요청: {}", e.getMessage());
    return respond(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getMessage());
  }

  /** 매핑된 핸들러가 없는 경로. 잡지 않으면 Spring 기본 형식이 나가고 웹이 사유를 읽지 못한다. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
    return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage());
  }

  /**
   * {@code @PreAuthorize}가 거절한 요청 (spec 3-1 §3-1-3 권한 매트릭스).
   *
   * <p><b>이것이 없으면 권한 부족이 500이 된다.</b> 메서드 보안은 핸들러를 부르는 도중에 예외를 던지므로 {@code DispatcherServlet}이 먼저
   * 잡고, 시큐리티의 {@code ExceptionTranslationFilter}까지 올라가지 못한다. 그대로 두면 아래 포괄 핸들러가 삼켜 사용자 권한 문제가 서버 오류로
   * 기록된다.
   *
   * <p>비로그인은 여기 오지 않는다. 필터 체인이 {@code anyRequest().authenticated()}로 먼저 막아 {@code 401
   * UNAUTHENTICATED}를 낸다.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
    return respond(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage());
  }

  /**
   * 예상하지 못한 오류. 스택 트레이스는 로그에만 남기고 응답에는 일반 메시지만 담는다 (§5-4).
   *
   * <p><b>Spring이 상태를 정해 던진 예외를 여기서 삼키면 안 된다.</b> {@code ExceptionHandlerExceptionResolver}가 {@code
   * DefaultHandlerExceptionResolver}보다 앞에 있어서, 이 핸들러가 먼저 잡는다. 걸러내지 않으면 POST 전용 경로에 GET을 보낸 요청이
   * {@code 405} 대신 {@code 500 INTERNAL_ERROR}를 받고, 클라이언트 실수가 서버 오류로 기록된다.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
    // 이름이 같은 우리 record와 구분하려고 전체 이름을 쓴다.
    if (e instanceof org.springframework.web.ErrorResponse framework) {
      return frameworkStatusOnly(framework);
    }
    log.error("처리하지 못한 예외: {} {}", request.getMethod(), request.getRequestURI(), e);
    return ResponseEntity.internalServerError()
        .body(new ErrorResponse(INTERNAL_ERROR, "서버에 문제가 발생했습니다. 잠시 후 다시 시도해 주세요."));
  }

  /**
   * 405(허용되지 않은 메서드)·406·415처럼 Spring이 상태를 정해 주는 오류. 계약에 코드가 없고 프론트가 만들 수 없는 오류라 본문을 만들지 않고 상태와 헤더만
   * 돌려준다 — 405의 {@code Allow} 헤더가 그 헤더에 들어 있다.
   *
   * <p>클래스 계층으로는 걸러낼 수 없다. {@code HttpRequestMethodNotSupportedException}은 {@code
   * ErrorResponseException}을 상속하지 않고 {@code ErrorResponse} 인터페이스를 구현하는데, 인터페이스는
   * {@code @ExceptionHandler}에 쓸 수 없다.
   *
   * <p>415를 {@code UNSUPPORTED_FILE_TYPE}으로 매핑하지 않는다. 그 코드는 자료 파일의 확장자를 가리키는 것이지 요청 본문의
   * Content-Type이 아니다 (§3-2-7).
   */
  private ResponseEntity<ErrorResponse> frameworkStatusOnly(
      org.springframework.web.ErrorResponse framework) {
    return ResponseEntity.status(framework.getStatusCode()).headers(framework.getHeaders()).build();
  }

  private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode, String message) {
    return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode, message));
  }
}
