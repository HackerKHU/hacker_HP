package org.hackerkhu.hackerhp.domain.photo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /photos} 요청 (spec 3-2 §3-2-5). {@code POST /photos/upload-url}로 원본을 S3에 올린 뒤, 그 키 목록을
 * 여기 담아 보낸다 — 서버가 각 키를 S3에서 읽어 리사이즈한 뒤 최종 위치로 옮기고 사진마다 행을 만든다.
 *
 * <p>원소에 {@code @NotNull}을 건다. {@code @Valid}는 리스트 자체는 검증해도 {@code null} 원소까지 거르지 않으므로, {@code
 * {"photos":[null]}}이 그대로 {@code register()}에 들어가 {@code item.key()}에서 {@code
 * NullPointerException}(→ {@code 500})이 난다.
 */
public record PhotoRegisterRequest(
    @NotEmpty(message = "등록할 사진이 없습니다.") @Size(max = 20, message = "한 번에 20장까지 등록할 수 있습니다.")
        List<@NotNull(message = "항목이 비어 있습니다.") @Valid Item> photos) {

  public record Item(
      @NotBlank(message = "key가 비어 있습니다.") String key,
      @Size(max = 200, message = "설명이 너무 깁니다.") String caption) {}
}
