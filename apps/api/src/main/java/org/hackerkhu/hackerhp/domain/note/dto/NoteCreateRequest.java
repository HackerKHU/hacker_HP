package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.entity.Category;
import org.hackerkhu.hackerhp.domain.note.entity.ExamType;
import org.hackerkhu.hackerhp.domain.note.entity.Semester;

/**
 * 자료 등록 — 흐름의 ③ (spec 2-1 §2-1-2, 3-2 §3-2-4).
 *
 * <p><b>업로더를 받지 않는다</b> (MUST). 인증 주체의 id로만 정한다 — 본문으로 받으면 다른 사람 이름으로 자료를 올릴 수 있다 (공지 등록과 같은 규칙이다).
 *
 * <p>{@code examType}과 {@code category}의 짝은 <b>DB의 CHECK 제약이 최종적으로 강제한다</b> (§3-2-2). 여기서 먼저 보는 것은
 * 그 위반이 {@code 500}으로 새지 않게 하기 위해서다.
 */
public record NoteCreateRequest(
    @NotNull(message = "분류를 선택해 주세요.") Category category,
    @NotBlank(message = "제목을 입력해 주세요.") @Size(max = 200, message = "제목이 너무 깁니다.") String title,
    @NotBlank(message = "과목명을 입력해 주세요.") @Size(max = 100, message = "과목명이 너무 깁니다.")
        String subjectName,
    @Schema(description = "없어도 된다") @Size(max = 50, message = "교수명이 너무 깁니다.") String professor,
    @Min(value = 2000, message = "연도를 확인해 주세요.") @Max(value = 2100, message = "연도를 확인해 주세요.")
        int year,
    @NotNull(message = "학기를 선택해 주세요.") Semester semester,
    @Schema(description = "`category=EXAM`이면 필수, `SUBJECT`면 비워 둔다") ExamType examType,
    @Schema(description = "발급받아 업로드를 마친 파일들")
        @NotEmpty(message = "파일을 하나 이상 올려 주세요.")
        @Size(max = 100, message = "파일이 너무 많습니다.")
        @Valid
        List<UploadedFile> files) {

  /**
   * @param key {@code POST /notes/upload-url}이 준 값 그대로
   * @param originalName 화면에 보여줄 이름. <b>키에서 뽑지 않는다</b> — 키는 uuid라 사람이 읽을 이름이 없다
   */
  public record UploadedFile(
      @NotBlank(message = "업로드 정보가 올바르지 않습니다.") @Size(max = 500) String key,
      @NotBlank(message = "파일명이 비었습니다.") @Size(max = 255, message = "파일명이 너무 깁니다.")
          String originalName) {}
}
