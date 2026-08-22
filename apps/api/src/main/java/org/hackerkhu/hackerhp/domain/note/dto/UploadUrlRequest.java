package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * presigned URL 발급 요청 (spec 2-1 §2-1-2, #53 D4).
 *
 * <p><b>파일을 한 번에 받는다.</b> 최대 10개인데 하나씩 발급하면 왕복이 10번이고, 개수 상한 검사도 서버가 매번 "지금까지 몇 개였는지"를 알 수 없어 걸 자리가
 * 없다.
 *
 * <p>{@code sizeBytes}는 <b>브라우저가 말하는 값이라 믿지 않는다.</b> 여기서 걸러 <b>올리기 전에</b> 알려 주는 것이 목적이고, 실제 강제는 등록
 * 단계에서 S3에 올라온 오브젝트를 직접 재서 한다 (§2-1-2 MUST).
 */
public record UploadUrlRequest(
    @Schema(description = "올릴 파일들. 1개 이상, 정책 상한 이하")
        @NotEmpty(message = "파일을 하나 이상 선택해 주세요.")
        @Size(max = 100, message = "파일이 너무 많습니다.")
        @Valid
        List<@NotNull(message = "파일 정보가 비었습니다.") File> files) {

  /**
   * @param originalName 확장자를 여기서 뽑는다
   * @param sizeBytes 브라우저가 재서 보낸 크기. 참고값이다
   */
  public record File(
      @Schema(description = "업로드 당시 파일명")
          @NotBlank(message = "파일명이 비었습니다.")
          @Size(max = 255, message = "파일명이 너무 깁니다.")
          String originalName,
      @Schema(description = "바이트 크기") @Positive(message = "파일 크기가 올바르지 않습니다.") long sizeBytes) {}
}
