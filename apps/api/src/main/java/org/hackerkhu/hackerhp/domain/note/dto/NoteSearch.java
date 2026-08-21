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
 */
public record NoteSearch(
    @Schema(description = "자료 갈래") Category category,
    @Schema(description = "제목·과목명·교수명 통합 검색어") String q,
    @Schema(description = "과목 필터") String subject,
    @Schema(description = "교수 필터") String professor,
    @Schema(description = "연도 필터") Integer year,
    @Schema(description = "학기 필터") Semester semester,
    @Schema(description = "시험 구분 필터") ExamType examType) {

  /** 공백뿐인 값은 없는 것으로 본다. 그대로 두면 전체 목록에 무의미한 {@code LIKE '%%'}가 걸린다. */
  public NoteSearch {
    q = blankToNull(q);
    subject = blankToNull(subject);
    professor = blankToNull(professor);
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
