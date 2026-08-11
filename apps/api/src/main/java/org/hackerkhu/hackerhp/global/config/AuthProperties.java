package org.hackerkhu.hackerhp.global.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 인증 관련 설정. 허용 이메일 도메인은 이 키 하나로 관리한다 (spec 3-1 §3-1-4).
 *
 * <p><b>값이 없으면 기동에 실패한다.</b> 기본값을 코드에 심어두면 설정 누락이 조용히 지나가고 도메인 제한이 무력해진다 — 아무 구글 계정이나 가입할 수 있는 상태로
 * 떠 있게 된다. 배포가 실패하는 편이 낫다 (docs/ops/infra.md).
 *
 * @param allowedEmailDomain 가입·로그인을 허용할 이메일 도메인. 예: {@code khu.ac.kr}
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(@NotBlank String allowedEmailDomain) {}
