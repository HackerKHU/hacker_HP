package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hackerkhu.hackerhp.domain.note.entity.NoteFile;

/**
 * 자료에 딸린 파일 (spec 3-2 §3-2-4).
 *
 * <p><b>S3 키({@code stored_path})를 담지 않는다.</b> 버킷이 비공개라 키를 알아도 열 수 없고, 키 구조를 밖에 드러낼 이유가 없다. 파일을 받는
 * 길은 presigned URL을 발급하는 {@code GET /notes/{id}/files/{fileId}}뿐이다 (#55) — 그래서 {@code id}가 필요하다.
 */
public record NoteFileResponse(
    @Schema(description = "파일 id. 다운로드 URL 발급에 쓴다") Long id,
    @Schema(description = "업로드 당시 파일명") String originalName,
    @Schema(description = "바이트 크기") long sizeBytes) {

  public static NoteFileResponse from(NoteFile file) {
    return new NoteFileResponse(file.getId(), file.getOriginalName(), file.getSizeBytes());
  }
}
