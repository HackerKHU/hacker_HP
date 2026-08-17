package org.hackerkhu.hackerhp.global.config;

import java.util.Arrays;
import java.util.Optional;

/**
 * 구글 콜백이 실패했을 때 로그인 화면에 알리는 사유 (spec 3-2 §3-2-3).
 *
 * <p>콜백은 브라우저 전체가 이동한 흐름이라 JSON을 반환하지 않는다 (MUST). 상태 코드가 아니라 {@code /login?error={코드}} 리다이렉트로 알린다.
 *
 * <p><b>여기 없는 사유는 전부 {@link #FAILED}다.</b> Spring이 내부적으로 쓰는 오류 코드({@code invalid_state}, {@code
 * authorization_request_not_found} 등)를 그대로 쿼리에 실으면 주소창·브라우저 기록·리퍼러에 남고, 이용자가 스스로 고칠 수 있는 정보도 아니다
 * (MUST).
 */
public enum LoginErrorCode {

  /** 허용 도메인이 아닌 계정. 화면은 "khu.ac.kr 계정으로 로그인하세요"를 보여준다. */
  DOMAIN("domain"),

  /** {@code email_verified}가 거짓. 구글에서 이메일 인증을 마치라고 안내한다. */
  UNVERIFIED("unverified"),

  /**
   * 정지된 계정. 세션을 발급하지 않고 로그인 화면으로 되돌린다 (spec 3-1 §3-1-5).
   *
   * <p>이 화면은 <b>이용 중 정지된 세션</b>이 도착하는 경로(쿼리 없는 {@code /login})와 같은 문구를 쓴다 (MUST). 사용자에게는 같은 사실이고,
   * 도달 경로는 구별할 수도 알 필요도 없는 내부 사정이다.
   */
  SUSPENDED("suspended"),

  /** 그 외 — state 불일치, 토큰 교환 실패 등. 원인을 자세히 알리지 않는다. */
  FAILED("failed");

  private final String value;

  LoginErrorCode(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** 알려진 사유면 그것을, 아니면 {@link #FAILED}를 준다. */
  public static LoginErrorCode from(String value) {
    return Optional.ofNullable(value)
        .flatMap(it -> Arrays.stream(values()).filter(code -> code.value.equals(it)).findFirst())
        .orElse(FAILED);
  }
}
