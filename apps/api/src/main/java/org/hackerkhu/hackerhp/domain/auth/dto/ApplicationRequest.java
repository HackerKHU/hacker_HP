package org.hackerkhu.hackerhp.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hackerkhu.hackerhp.global.validation.CodePointSize;

/**
 * {@code POST /auth/application} 요청 (spec 3-2 §3-2-3).
 *
 * <p><b>둘 다 공백이 아니어야 한다</b> (MUST). PostgreSQL의 {@code NOT NULL}·{@code UNIQUE}는 빈 문자열을 거부하지 않으므로
 * 서버가 막아야 한다. 통과시키면 식별 정보가 없는 계정이 {@code applied_at}을 얻어 승인 대상이 되고, 관리자 부트스트랩까지 지나간다 (T-52).
 *
 * <p><b>{@code studentNo}는 숫자만 받는다</b> (MUST, 2026-08-31 #328). 학교 학번이 전부 숫자임을 확인하고 정한 규칙이다.
 *
 * <p>한때는 형식을 걸지 않았다 — <i>"편입·교환학생·대학원처럼 형태가 다른 학번이 실제로 있다"</i> 는 이유였다 (#38). <b>그 전제가 확인 결과 사실이
 * 아니었다.</b> 형식이 없으면 {@code "편입2025"}·{@code "😀A"} 같은 값이 그대로 저장되고, 그것이 표시 이름의 학번 뒷자리로 화면에 나간다 (3-2
 * §3-2-2).
 *
 * <p><b>자릿수는 고정하지 않는다.</b> 폼 예시가 10자리지만 그것이 실제 자릿수라는 근거가 없다 — 확인되지 않은 가정을 규칙으로 굳히면 그 길이가 아닌 학번을 가진
 * 사람이 가입하지 못하고, 승인 뒤에는 정정할 경로도 없다 (#178).
 *
 * <p><b>{@code name}이 없다</b> (#224). 이름은 신청서 입력이 아니라 구글 계정에 저장된 값이다 — 화면이 읽기 전용으로 보여줄 뿐이고, 서버는
 * {@code users.name}을 그대로 쓴다. 필드를 남겨 두고 무시하면 <b>보낸 값이 반영된 줄 아는 호출자가 생긴다.</b> 여기 없으면 본문에 담아 보내도 역직렬화
 * 단계에서 버려진다.
 *
 * <p>길이는 컬럼과 맞춘다 — {@code student_no varchar(20)}, {@code department varchar(50)} (3-2 §3-2-2). 여기서
 * 막지 않으면 저장할 때 터지고, 그 예외가 무엇이었는지에 따라 엉뚱한 코드로 응답하게 된다.
 *
 * <p>{@code department}는 정해진 목록에서만 고른다 (MUST) — 목록에 있는지는 이 레코드가 아니라 {@code User.submitApplication}이
 * {@link org.hackerkhu.hackerhp.domain.user.entity.Department#isValid}로 확인한다. 여기서는 길이·공백만 본다.
 *
 * <p>메시지는 화면에 그대로 뜬다. 어떤 값을 고쳐야 하는지 알려준다 (T-108).
 */
public record ApplicationRequest(
    /*
     * NotNull과 Pattern의 문구가 같다. Pattern은 null을 통과시키므로 NotNull이 함께 있어야
     * 본문에서 아예 빠뜨린 요청이 막힌다.
     *
     * 그런데 GlobalExceptionHandler는 실패한 것 중 findFirst()로 하나만 골라 내보내고
     * 그 순서는 정해져 있지 않다. 문구가 다르면 같은 입력에 다른 안내가 나가므로 하나로 맞춘다.
     */
    @Schema(description = "유니코드 공백류·제어·서식 문자를 제거해 정규화한 뒤 ASCII 숫자 20자 이하. raw 길이에는 상한이 없다")
        @NotNull(message = "학번을 숫자로 입력해 주세요.")
        @Pattern(regexp = "[0-9]+", message = "학번을 숫자로 입력해 주세요.")
        /*
         * @Size는 springdoc에 raw maxLength를 만든다. 실제 계약은 compact constructor에서
         * 공백류를 제거한 뒤의 상한이므로, OpenAPI에 거짓 제약을 내지 않는 커스텀 검증을 쓴다.
         * 정규화 뒤에는 ASCII 숫자만 남아 코드포인트 수와 DB varchar 길이가 같다.
         */
        @CodePointSize(max = 20, message = "학번이 너무 깁니다.")
        String studentNo,
    @NotBlank(message = "학과를 선택해 주세요.") @Size(max = 50, message = "학과가 너무 깁니다.")
        String department) {

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
    // 학과명은 정확히 목록의 문자열과 일치해야 한다("환경학 및 환경공학과"처럼 안쪽 공백이 있는 항목도 있다).
    // 보이지 않는 문자만 보통 공백으로 바꾸고 앞뒤를 턴다.
    department = collapseSpacing(department);
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
