package org.hackerkhu.testsupport.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 규칙이 실제로 어떻게 도는지 보기 위한 테스트 전용 핸들러.
 *
 * <p>MVP의 관리자 API는 아직 없다 — 회원 관리는 #29~#31, 공지는 #32~#34다. 그런데 <b>규칙의 조합이 맞는지는 그 API들이 들어오기 전에 확인해야
 * 한다.</b> 먼저 쓰는 사람이 틀린 조합을 고르면 뒤따르는 것들이 그것을 베낀다.
 *
 * <p>매트릭스의 {@code ADMIN} 열은 <b>{@code ADMIN}이면서 {@code ACTIVE}</b>다. 그런데 여기에는 {@code
 * hasRole('ADMIN')}만 적는다 — {@code ACTIVE} 조건은 {@link
 * org.hackerkhu.hackerhp.global.auth.AccountStatusFilter}가 인가보다 먼저 보장하기 때문이다. 두 곳에 같은 규칙을 두면 한쪽만
 * 고쳐지는 자리가 생긴다.
 *
 * <p><b>{@code @RestController}가 붙어 있지만 스캔 기준 패키지 밖이라</b> 운영 컨텍스트에는 올라오지 않는다 — 자세한 이유는 {@link
 * ErrorHandlingTestController}에 적어 두었다.
 */
@RestController
@RequestMapping("/api/v1/__test/admin")
public class AdminOnlyTestController {

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  String adminOnly() {
    return "ok";
  }
}
