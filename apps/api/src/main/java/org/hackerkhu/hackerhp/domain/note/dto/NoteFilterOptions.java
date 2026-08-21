package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * {@code GET /notes/filters}의 응답 (spec 2-1 §2-1-1, 3-2 §3-2-4).
 *
 * <p><b>실제 등록된 값에서 만든다</b> (MUST). 목록에 없는 과목을 고를 수 있으면 결과가 늘 0건이고, 등록된 과목이 빠지면 찾을 방법이 사라진다.
 *
 * <p><b>학기·시험 구분은 담지 않는다.</b> 값이 enum으로 고정이라 등록 현황과 무관하고, 화면이 이미 안다.
 */
public record NoteFilterOptions(
    @Schema(description = "등록된 과목명. 가나다순") List<String> subjects,
    @Schema(description = "등록된 교수명. 값이 있는 자료에서만 모은다") List<String> professors,
    @Schema(description = "등록된 연도. 최신순") List<Integer> years) {}
