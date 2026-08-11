package org.hackerkhu.hackerhp.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/application} 요청 (spec 3-2 §3-2-3).
 *
 * <p><b>둘 다 공백이 아니어야 한다</b> (MUST). PostgreSQL의 {@code NOT NULL}·{@code UNIQUE}는 빈 문자열을 거부하지 않으므로
 * 서버가 막아야 한다. 통과시키면 식별 정보가 없는 계정이 {@code applied_at}을 얻어 승인 대상이 되고, 관리자 부트스트랩까지 지나간다 (T-52).
 *
 * <p>길이는 컬럼과 맞춘다 — {@code student_no varchar(20)}, {@code name varchar(50)} (3-2 §3-2-2). 여기서 막지
 * 않으면 저장할 때 터지고, 그 예외가 무엇이었는지에 따라 엉뚱한 코드로 응답하게 된다.
 *
 * <p>메시지는 화면에 그대로 뜬다. 어떤 값을 고쳐야 하는지 알려준다 (T-108).
 */
public record ApplicationRequest(
    @NotBlank(message = "학번을 입력해 주세요.") @Size(max = 20, message = "학번이 너무 깁니다.") String studentNo,
    @NotBlank(message = "이름을 입력해 주세요.") @Size(max = 50, message = "이름이 너무 깁니다.") String name) {

  /**
   * <b>검증 전에 정규화한다.</b> 레코드의 압축 생성자는 Bean Validation보다 먼저 돌므로, 아래 검사와 저장이 모두 같은 값을 본다.
   *
   * <p>{@code String.trim()}·{@code strip()}·{@code isBlank()}로는 부족하다. 셋 다 NBSP({@code U+00A0})나 폭
   * 없는 문자를 공백으로 보지 않는다. 그대로 두면 두 가지가 뚫린다.
   *
   * <ul>
   *   <li>NBSP만 담아 보내면 {@code @NotBlank}를 통과해 <b>식별 정보가 없는 계정이 {@code applied_at}을 얻는다</b>
   *   <li>정상 학번 사이에 끼워 넣으면 DB가 다른 문자열로 보아 <b>같은 학번으로 계정을 하나 더 만들 수 있다</b>
   * </ul>
   */
  public ApplicationRequest {
    // 학번에는 공백이 들어갈 자리가 없다. 안쪽까지 전부 없애야 "2024 0001"로 유일성을 피해 갈 수 없다.
    studentNo = removeAllSpacing(studentNo);
    // 이름에는 안쪽 공백이 정당하다("홍 길동"). 보이지 않는 문자만 보통 공백으로 바꾸고 앞뒤를 턴다.
    name = collapseSpacing(name);
  }

  /** {@code \p{Z}} 구분자(공백류), {@code \p{C}} 제어·서식 문자, {@code \s} 탭·줄바꿈. */
  private static final String SPACING = "[\\p{Z}\\p{C}\\s]";

  private static String removeAllSpacing(String value) {
    return value == null ? null : value.replaceAll(SPACING + "+", "");
  }

  private static String collapseSpacing(String value) {
    return value == null ? null : value.replaceAll(SPACING + "+", " ").trim();
  }
}
