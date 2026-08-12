package org.hackerkhu.testsupport.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 규칙이 실제로 어떻게 도는지 보기 위한 테스트 전용 핸들러.
 *
 * <p>MVP의 관리자 API는 아직 없다 — 회원 관리는 #29~#31, 공지는 #32~#34다. 그런데 <b>규칙의 조합이 맞는지는 그 API들이 들어오기 전에 확인해야
 * 한다.</b> 먼저 쓰는 사람이 틀린 조합을 고르면 뒤따르는 것들이 그것을 베낀다.
 *
 * <p><b>경로가 {@code /api/v1/admin} 아래인 것이 중요하다.</b> 실제 관리자 API가 올 자리이고, {@code SecurityConfig}가 그
 * 접두사에 거는 규칙을 이 핸들러가 그대로 받는다. 다른 자리에 두면 규칙이 아니라 애너테이션만 시험하게 된다.
 *
 * <p>쓰기도 함께 둔다. <b>MVC는 메서드를 부르기 전에 본문을 역직렬화하고 {@code @Valid}를 돌리므로</b>, {@code @PreAuthorize}에만
 * 기대면 권한 없는 사람이 깨진 본문을 보냈을 때 {@code 403}이 아니라 {@code 400}을 받는다 — 조회만으로는 그 차이가 드러나지 않는다.
 *
 * <p><b>{@code @RestController}가 붙어 있지만 스캔 기준 패키지 밖이라</b> 운영 컨텍스트에는 올라오지 않는다 — 자세한 이유는 {@link
 * ErrorHandlingTestController}에 적어 두었다.
 */
@RestController
@RequestMapping("/api/v1/admin/__test")
public class AdminOnlyTestController {

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  String read() {
    return "ok";
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  String write(@Valid @RequestBody AdminCommand command) {
    return command.value();
  }

  public record AdminCommand(@NotBlank String value) {}
}
