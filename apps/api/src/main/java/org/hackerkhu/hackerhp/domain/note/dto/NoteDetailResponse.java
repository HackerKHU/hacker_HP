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
 *
 * <p><b>{@code viewCount}만 엔티티에서 가져오지 않는다</b> (#245). 상세를 여는 것이 곧 조회수 1회라, 응답에 실을 숫자는 <b>올린 뒤의
 * 값</b>이어야 한다 (3-2 §3-2-4 MUST). 읽어 온 엔티티는 올리기 전 값을 들고 있으므로 부르는 쪽이 계산해 넘긴다 — 증가에 실패했으면 증가 전 값 그대로다.
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
    @Schema(description = "이 조회를 **포함한** 횟수") long viewCount,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * @param viewCount <b>이 조회를 반영한 값</b>이다. 엔티티의 값을 그대로 넘기면 목록으로 돌아갔을 때 1 큰 숫자가 보여 두 화면이 어긋난다
   */
  public static NoteDetailResponse of(
      Note note, Uploader uploader, boolean bookmarked, long viewCount) {
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
        viewCount,
        note.getCreatedAt(),
        note.getUpdatedAt());
  }

  /**
   * 조회수를 1 올린 사본 (#245).
   *
   * <p><b>증가가 실제로 성공했을 때만 쓴다.</b> 읽어 온 값에 1을 더하는 것이라, 증가가 실패했는데 이것을 쓰면 <b>DB에 없는 숫자가 나가</b> 직후 목록과
   * 어긋난다.
   *
   * <p>같은 자료를 동시에 연 사람이 있으면 DB는 이미 더 크다. 그래도 <b>맞추려고 다시 읽지 않는다</b> (3-2 §3-2-4) — 조회마다 질의가 하나 늘고,
   * 그래도 다음 순간이면 또 어긋난다. 이 숫자는 "그 순간의 정확한 총합"이 아니라 <b>내 조회를 반영한 값</b>이다.
   */
  public NoteDetailResponse counted() {
    return new NoteDetailResponse(
        id,
        category,
        title,
        subjectName,
        professor,
        year,
        semester,
        examType,
        uploader,
        files,
        bookmarked,
        viewCount + 1,
        createdAt,
        updatedAt);
  }
}
