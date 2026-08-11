package org.hackerkhu.testsupport.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GlobalExceptionHandler}를 검증하기 위한 테스트 전용 핸들러.
 *
 * <p>MVP에는 아직 컨트롤러가 하나도 없다. advice가 실제 요청 경로에서 동작하는지 보려면 예외를 던지는 핸들러가 있어야 한다.
 *
 * <p><b>패키지가 {@code org.hackerkhu.testsupport}인 이유가 있다.</b> 컴포넌트 스캔 기준은 {@code
 * HackerHpApiApplication}이 있는 {@code org.hackerkhu.hackerhp}이고, 테스트 클래스도 그 아래 있으면 스캔에 걸린다. 그러면
 * {@code @SpringBootTest} 컨텍스트에까지 이 엔드포인트가 열린다. 기준 패키지 밖에 두고 필요한 테스트에서 {@code @Bean}으로 명시해 등록한다.
 */
@RestController
@RequestMapping("/api/v1/__test")
public class ErrorHandlingTestController {

  @GetMapping("/business")
  String business() {
    throw new BusinessException(ErrorCode.PENDING_APPROVAL);
  }

  @GetMapping("/business-custom")
  String businessWithCustomMessage() {
    throw new BusinessException(ErrorCode.DUPLICATE_STUDENT_NO, "이미 다른 계정이 쓰고 있는 학번입니다.");
  }

  @PostMapping("/validation")
  String validation(@Valid @RequestBody ApplicationRequest request) {
    return request.studentNo();
  }

  @GetMapping("/type-mismatch/{id}")
  String typeMismatch(@PathVariable Long id) {
    return String.valueOf(id);
  }

  @GetMapping("/boom")
  String boom() {
    // 계약에 없는 예외. 메시지에 내부 사정이 들어 있다 — 응답에 새어나가면 안 된다.
    throw new IllegalStateException("jdbc:postgresql://prod-db:5432 connection pool exhausted");
  }

  public record ApplicationRequest(@NotBlank(message = "학번을 입력해 주세요.") String studentNo) {}
}
