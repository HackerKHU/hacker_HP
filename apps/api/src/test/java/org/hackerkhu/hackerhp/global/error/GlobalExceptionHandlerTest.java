package org.hackerkhu.hackerhp.global.error;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.testsupport.web.ErrorHandlingTestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** advice가 실제 요청 경로에서 계약(spec/3-2 §3-2-7, spec/5-TESTING §5-4)대로 응답하는지 확인한다. */
@WebMvcTest
@Import(GlobalExceptionHandlerTest.TestControllerConfig.class)
class GlobalExceptionHandlerTest {

  @TestConfiguration
  static class TestControllerConfig {
    @Bean
    ErrorHandlingTestController errorHandlingTestController() {
      return new ErrorHandlingTestController();
    }
  }

  @Autowired private MockMvc mockMvc;

  /* T-117 — BusinessException이 ErrorCode의 상태·코드·기본 메시지로 나간다. */
  @Test
  void businessExceptionUsesErrorCodeStatusAndDefaultMessage() throws Exception {
    mockMvc
        .perform(get("/api/v1/__test/business"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.message").value("승인 대기 중인 계정입니다."));
  }

  /* T-118 — 응답 본문은 code·message 두 개뿐이다. 필드가 늘면 웹의 파싱 계약이 흔들린다. */
  @Test
  void errorBodyHasOnlyCodeAndMessage() throws Exception {
    mockMvc.perform(get("/api/v1/__test/business")).andExpect(jsonPath("$").value(aMapWithSize(2)));
  }

  /* T-119 — 상황 설명이 필요하면 메시지를 덮어쓰되 코드는 그대로다. */
  @Test
  void businessExceptionCanOverrideMessage() throws Exception {
    mockMvc
        .perform(get("/api/v1/__test/business-custom"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_STUDENT_NO"))
        .andExpect(jsonPath("$.message").value("이미 다른 계정이 쓰고 있는 학번입니다."));
  }

  /*
   * T-120 — @Valid 실패는 400 VALIDATION_ERROR다. 메시지는 DTO에 적은 문장이 그대로 나온다.
   * 화면이 사유를 보여줘야 사용자가 입력을 고칠 수 있다 (T-108).
   */
  @Test
  void beanValidationFailureBecomesValidationError() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/__test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\": \"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("학번을 입력해 주세요."));
  }

  /*
   * T-121 — 깨진 JSON도 계약 형식으로 나간다. 잡지 않으면 Spring 기본 형식이 나가고
   * 웹은 INVALID_RESPONSE로 격리해 사유를 보여주지 못한다.
   */
  @Test
  void malformedJsonBecomesValidationError() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/__test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("입력값을 확인해 주세요."));
  }

  /* T-122 — 경로변수 타입이 맞지 않아도 같은 코드로 나간다. */
  @Test
  void pathVariableTypeMismatchBecomesValidationError() throws Exception {
    mockMvc
        .perform(get("/api/v1/__test/type-mismatch/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /* T-123 — 매핑되지 않은 경로는 404 NOT_FOUND다. */
  @Test
  void unmappedPathBecomesNotFound() throws Exception {
    mockMvc
        .perform(get("/api/v1/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /*
   * T-124 — 예상하지 못한 예외는 500이고 응답에 내부 정보가 없다 (§5-4).
   * 예외 메시지에 담긴 접속 문자열이 그대로 나가면 안 된다.
   */
  @Test
  void unexpectedExceptionHidesInternalDetail() throws Exception {
    mockMvc
        .perform(get("/api/v1/__test/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("서버에 문제가 발생했습니다. 잠시 후 다시 시도해 주세요."));
  }
}
