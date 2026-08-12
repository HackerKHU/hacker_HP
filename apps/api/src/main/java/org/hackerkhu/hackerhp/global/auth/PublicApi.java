package org.hackerkhu.hackerhp.global.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증 없이 열어둔 엔드포인트라고 <b>선언</b>한다.
 *
 * <p>권한을 적지 않은 엔드포인트는 테스트가 잡는다 (spec 3-1 §3-1-3, T-146). 그 검사를 통과하는 길은 두 가지뿐이다 —
 * {@code @PreAuthorize}로 권한을 적거나, 이것으로 <b>일부러 열었다고 말하거나</b>.
 *
 * <p><b>"깜빡한 것"과 "일부러 연 것"을 구별하려고 둔다.</b> 둘 다 애너테이션이 없는 상태로 보이면, 리뷰에서 매번 어느 쪽인지 물어야 하고 언젠가는 놓친다.
 *
 * <p>이것을 붙였다고 실제로 열리지는 않는다. 여는 것은 {@code SecurityConfig}의 {@code permitAll}이다 — 여기는 <b>의도를 적는
 * 자리</b>다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {

  /** 왜 열어두는지. 매트릭스에 없는 경로를 여는 것은 결정이므로 근거를 남긴다. */
  String reason();
}
