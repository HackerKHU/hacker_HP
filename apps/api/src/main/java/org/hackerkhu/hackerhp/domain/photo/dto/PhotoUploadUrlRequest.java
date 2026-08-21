package org.hackerkhu.hackerhp.domain.photo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /photos/upload-url} 요청 (spec 3-2 §3-2-5). 올릴 원본 개수만큼 확장자를 하나씩 담는다 — 서버가 그 확장자로 임시 키를
 * 만들고 파일별 presigned PUT URL을 하나씩 내려준다.
 *
 * <p>개수 상한(20)은 계약이 정한 값이 아니라 방어적 상한이다 — 한 번에 수천 개를 요청해 presigned URL을 낭비하는 것을 막는다.
 *
 * <p><b>확장자(jpg/jpeg/png) 자체는 여기서 검증하지 않는다.</b> {@code @Pattern}으로 걸면 {@code 400 VALIDATION_ERROR}가
 * 되는데, "지원하지 않는 형식"은 계약상 {@code 415 UNSUPPORTED_FILE_TYPE}이다 (§3-2-7). 그 판단은 {@code PhotoService}가
 * 한다 — 여기서는 비었는지·개수만 본다.
 */
public record PhotoUploadUrlRequest(
    @NotEmpty(message = "올릴 파일이 없습니다.") @Size(max = 20, message = "한 번에 20장까지 올릴 수 있습니다.")
        List<String> extensions) {}
