package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.entity.Category;
import org.hackerkhu.hackerhp.domain.note.entity.ExamType;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.entity.Semester;

/**
 * 자료 상세 (spec 3-2 §3-2-4).
 *
 * <p>목록과 달리 <b>파일 목록을 담는다.</b> 상세 화면이 "어떤 파일이 있나"를 보여주고, 각 파일의 내려받기 버튼이 그 {@code id}로 presigned
 * URL을 요청한다 (#55).
 */
public record NoteDetailResponse(
    Long id,
    Category category,
    String title,
    String subjectName,
    @Schema(description = "없을 수 있다") String professor,
    int year,
    Semester semester,
    @Schema(description = "category=EXAM에만 있다") ExamType examType,
    Uploader uploader,
    List<NoteFileResponse> files,
    @Schema(description = "내가 즐겨찾기했는지") boolean bookmarked,
    Instant createdAt,
    Instant updatedAt) {

  public static NoteDetailResponse of(Note note, Uploader uploader, boolean bookmarked) {
    return new NoteDetailResponse(
        note.getId(),
        note.getCategory(),
        note.getTitle(),
        note.getSubjectName(),
        note.getProfessor(),
        note.getYear(),
        note.getSemester(),
        note.getExamType(),
        uploader,
        note.getFiles().stream().map(NoteFileResponse::from).toList(),
        bookmarked,
        note.getCreatedAt(),
        note.getUpdatedAt());
  }
}
