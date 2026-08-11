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
