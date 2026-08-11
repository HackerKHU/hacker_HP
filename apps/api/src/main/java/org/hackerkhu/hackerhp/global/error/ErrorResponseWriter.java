package org.hackerkhu.hackerhp.global.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * {@link ErrorResponse}를 서블릿 응답에 직접 쓴다.
 *
 * <p>{@code @RestControllerAdvice}는 {@code DispatcherServlet} 안에서만 동작한다. 그보다 앞에 있는 필터에서 거절되는 요청 —
 * 미인증({@code UNAUTHENTICATED}), CSRF 토큰 불일치({@code FORBIDDEN}), 세션 상태 검사({@code
 * PENDING_APPROVAL}·{@code SUSPENDED}) — 은 advice에 도달하지 않는다.
 *
 * <p>그 경로도 같은 본문 형식을 내보내야 화면이 코드로 분기할 수 있다 (spec/5-TESTING.md T-02·T-32·T-36·T-37). Security를 붙이는
 * #21에서 {@code AuthenticationEntryPoint}와 {@code AccessDeniedHandler}가 이것을 호출한다. 형식을 각자 만들면 계약이 두
 * 갈래로 갈라진다.
 */
@Component
public class ErrorResponseWriter {

  private final ObjectMapper objectMapper;

  public ErrorResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    // 메시지가 한글이다. 인코딩을 지정하지 않으면 컨테이너 기본값에 따라 깨진다.
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}
