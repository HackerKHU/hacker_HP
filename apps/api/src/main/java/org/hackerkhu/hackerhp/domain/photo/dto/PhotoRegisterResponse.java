package org.hackerkhu.hackerhp.domain.photo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * {@code POST /photos} 응답 (spec 3-2 §3-2-5). 일괄 승인 응답(§3-2-6, {@code ApproveResponse})과 같은 모양이다 —
 * 원본 하나의 실패(원본 없음, 손상된 이미지 등)가 이미 등록된 나머지를 되돌리지 않는다 (apps/api/AGENTS.md).
 *
 * <p><b>건수 필드를 따로 두지 않는다.</b> 배열 길이가 곧 건수다 — {@code registeredCount}를 따로 두면 배열과 어긋날 자리가 생긴다.
 *
 * <p>일부가 실패해도 전체는 {@code 200}이다. 요청 자체의 실패(예: 요청자 권한이 사라짐)는 §3-2-7의 오류 규약을 그대로 쓴다 — 그건 항목 하나의 문제가
 * 아니라 요청 전체가 더 이상 유효하지 않다는 뜻이다.
 */
@Schema(description = "사진 등록 결과. 일부가 실패해도 상태 코드는 200이다")
public record PhotoRegisterResponse(
    @Schema(description = "등록된 사진") List<PhotoResponse> registered,
    @Schema(description = "등록하지 못한 원본과 그 사유") List<Failure> failed) {

  @Schema(description = "등록하지 못한 한 건")
  public record Failure(@Schema(description = "요청에 실려 온 임시 원본 키") String key, Reason reason) {}

  @Schema(description = "등록 실패 사유")
  public enum Reason {
    /** 그 키의 임시 원본이 없다 — 아직 안 올라왔거나, 이미 등록·삭제됐거나, 너무 커서 서버가 지웠다. */
    NOT_FOUND,
    /** 원본이 상한(20MB)을 넘는다. */
    FILE_TOO_LARGE,
    /** 디코딩할 수 없는 바이트다 — 유효한 이미지가 아니다. */
    UNSUPPORTED_FILE_TYPE,
    /** 이 서비스가 발급한 임시 키 형식이 아니다. */
    VALIDATION_ERROR
  }
}
