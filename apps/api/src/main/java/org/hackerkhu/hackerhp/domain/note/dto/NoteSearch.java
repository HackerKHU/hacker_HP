package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hackerkhu.hackerhp.domain.note.entity.Category;
import org.hackerkhu.hackerhp.domain.note.entity.ExamType;
import org.hackerkhu.hackerhp.domain.note.entity.Semester;

/**
 * {@code GET /notes}의 조회 조건 (spec 3-2 §3-2-4). 값이 {@code null}이면 그 조건으로 거르지 않는다.
 *
 * <p><b>검색어와 필터는 AND로 함께 걸린다</b> (2-1 §2-1-1 MUST).
 *
 * <p><b>{@code category}와 {@code examType}의 짝은 여기서 검사하지 않는다.</b> {@code
 * category=SUBJECT&examType=MIDTERM}은 결과가 0건일 뿐 오류가 아니다 — 조회에 검증을 넣으면 화면이 필터를 조합하는 순간마다 {@code
 * 400}을 받는다. 그 짝을 강제하는 것은 등록 경로와 DB의 CHECK 제약이다 (3-2 §3-2-2).
 *
 * @param q 제목·과목명·교수명 <b>통합</b> 검색어. 필드를 나눠 받지 않는다 (2-1 §2-1-1 MUST)
 * @param mine 켜면 <b>내가 올린 자료만</b> 본다 (#353, 3-3 결정 28). <b>업로더 id를 요청으로 받지 않는다</b> (MUST) — 받으면 남의
 *     것을 "내 것"으로 조회할 수 있다. 누구의 것인지는 서비스가 인증 주체로만 정한다
 */
public record NoteSearch(
    @Schema(description = "자료 갈래") Category category,
    @Schema(description = "제목·과목명·교수명 통합 검색어") String q,
    @Schema(description = "과목 필터") String subject,
    @Schema(description = "교수 필터") String professor,
    @Schema(description = "연도 필터") Integer year,
    @Schema(description = "학기 필터") Semester semester,
    @Schema(description = "시험 구분 필터") ExamType examType,
    @Schema(description = "내가 올린 자료만") Boolean mine) {

  /**
   * 공백뿐인 값은 없는 것으로 본다. 그대로 두면 전체 목록에 무의미한 {@code LIKE '%%'}가 걸린다.
   *
   * <p><b>{@code mine}은 래퍼 타입이어야 한다</b> (#353). 원시 {@code boolean}으로 두면 파라미터를 보내지 않은 요청에서 {@code
   * null}을 넣지 못해 <b>바인딩이 실패하고 목록 전체가 {@code 400}이 된다</b> — 필터 하나를 더하려다 기본 조회를 깨뜨린다. 여기서 {@code
   * false}로 정규화해 두면 뒤쪽은 널을 다시 신경 쓰지 않아도 된다.
   */
  public NoteSearch {
    q = blankToNull(q);
    subject = blankToNull(subject);
    professor = blankToNull(professor);
    mine = mine != null && mine;
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
