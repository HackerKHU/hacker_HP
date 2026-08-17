package org.hackerkhu.hackerhp.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** 필터 계층이 쓸 응답 작성기. #21이 {@code AuthenticationEntryPoint}·{@code AccessDeniedHandler}에 꽂는다. */
class ErrorResponseWriterTest {

  private final ErrorResponseWriter writer = new ErrorResponseWriter(new ObjectMapper());

  /* T-126 — 필터에서 나가는 본문도 advice와 같은 형식·상태다. */
  @Test
  void writesSameBodyAndStatusAsAdvice() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.write(response, ErrorCode.UNAUTHENTICATED);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}");
  }

  /*
   * 메시지가 한글이다. 인코딩을 지정하지 않으면 컨테이너 기본값에 따라 깨져서, 화면에 깨진 글자가 뜬다.
   * advice 경로는 Spring이 처리해 주지만 이쪽은 직접 써야 한다.
   */
  @Test
  void declaresUtf8SoKoreanMessageSurvives() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.write(response, ErrorCode.PENDING_APPROVAL);

    assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
  }
}
