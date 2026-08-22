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
 * 자료 수정 (spec 2-1 §2-1-3, 3-2 §3-2-4, #54).
 *
 * <p><b>보낸 것으로 통째로 바꾼다.</b> 경로는 계약대로 {@code PATCH}지만 동작은 전체 교체다 — 부분 수정이면 {@code professor}를
 * <b>지우려는 의도({@code null})와 건드리지 않는 의도를 JSON으로 구별</b>해야 하는데, 자료 수정은 폼 하나를 통째로 다시 내는 화면이라 그 구별이 필요
 * 없다.
 *
 * <p><b>업로더는 받지 않는다</b> (MUST). 관리자가 남의 자료를 고쳐도 업로더는 그대로다.
 */
public record NoteUpdateRequest(
    @NotNull(message = "분류를 선택해 주세요.") Category category,
    @NotBlank(message = "제목을 입력해 주세요.") @Size(max = 200, message = "제목이 너무 깁니다.") String title,
    @NotBlank(message = "과목명을 입력해 주세요.") @Size(max = 100, message = "과목명이 너무 깁니다.")
        String subjectName,
    @Schema(description = "없어도 된다") @Size(max = 50, message = "교수명이 너무 깁니다.") String professor,
    @Min(value = 2000, message = "연도를 확인해 주세요.") @Max(value = 2100, message = "연도를 확인해 주세요.")
        int year,
    @NotNull(message = "학기를 선택해 주세요.") Semester semester,
    @Schema(description = "`category=EXAM`이면 필수, `SUBJECT`면 비워 둔다") ExamType examType,
    @Schema(description = "**수정 뒤에 남을 첨부 전부.** 여기 없는 기존 파일은 삭제된다")
        @NotEmpty(message = "파일을 하나 이상 남겨 주세요.")
        @Size(max = 100, message = "파일이 너무 많습니다.")
        @Valid
        List<@NotNull(message = "파일 정보가 비었습니다.") FileRef> files) {

  /**
   * 남길 파일 하나.
   *
   * <p><b>둘 중 정확히 하나만 채운다.</b> 이미 있는 파일은 {@code fileId}로 가리키고, 새로 올린 파일은 {@code key}로 가리킨다. 둘 다
   * 비었거나 둘 다 차 있으면 <b>무엇을 뜻하는지 서버가 정할 수 없으므로</b> 거절한다.
   *
   * @param fileId 그대로 둘 기존 파일. 이때 {@code originalName}은 쓰이지 않는다
   * @param key {@code POST /notes/upload-url}이 준 값. 이때 {@code originalName}이 필요하다
   */
  public record FileRef(
      @Schema(description = "그대로 둘 기존 파일의 id") Long fileId,
      @Schema(description = "새로 올린 파일의 키") @Size(max = 500) String key,
      @Schema(description = "새로 올린 파일의 이름. `key`와 함께 온다") @Size(max = 255, message = "파일명이 너무 깁니다.")
          String originalName) {

    public boolean isExisting() {
      return fileId != null;
    }

    public boolean isNew() {
      return key != null && !key.isBlank();
    }
  }
}
