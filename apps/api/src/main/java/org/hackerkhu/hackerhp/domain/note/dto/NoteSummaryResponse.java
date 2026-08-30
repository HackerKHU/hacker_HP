package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.hackerkhu.hackerhp.domain.note.entity.Category;
import org.hackerkhu.hackerhp.domain.note.entity.ExamType;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.entity.Semester;

/**
 * 목록의 한 행 (spec 3-2 §3-2-4).
 *
 * <p><b>파일은 개수만 담는다.</b> 목록에서 쓰는 것은 "첨부가 있나"뿐인데, 파일 목록을 전부 실으면 20건 × N개가 되어 응답이 커진다. 내용은 상세에서 준다
 * ({@link NoteDetailResponse}).
 *
 * <p><b>{@code bookmarked}는 목록에서 별표를 채울지 비울지 정한다</b> (2-1 §2-1-5 — 목록에서도 추가·해제한다). 이것이 없으면 화면이
 * {@code GET /bookmarks}를 통째로 받아 대조해야 한다 (#56).
 */
public record NoteSummaryResponse(
    Long id,
    Category category,
    String title,
    String subjectName,
    @Schema(description = "없을 수 있다") String professor,
    int year,
    Semester semester,
    @Schema(description = "category=EXAM에만 있다") ExamType examType,
    Uploader uploader,
    @Schema(description = "딸린 파일 개수") int fileCount,
    @Schema(description = "내가 즐겨찾기했는지") boolean bookmarked,
    @Schema(description = "상세를 연 횟수. **목록을 여는 것은 세지 않는다**") long viewCount,
    Instant createdAt) {

  public static NoteSummaryResponse of(
      Note note, Uploader uploader, int fileCount, boolean bookmarked) {
    return new NoteSummaryResponse(
        note.getId(),
        note.getCategory(),
        note.getTitle(),
        note.getSubjectName(),
        note.getProfessor(),
        note.getYear(),
        note.getSemester(),
        note.getExamType(),
        uploader,
        fileCount,
        bookmarked,
        note.getViewCount(),
        note.getCreatedAt());
  }
}
