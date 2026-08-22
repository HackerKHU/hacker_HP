package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;

/**
 * 내려받기 URL (spec 2-1 §2-1-4 MUST, 3-2 §3-2-4, #55).
 *
 * <p><b>S3 키를 담지 않는다</b> (§3-2-4 MUST). 버킷이 비공개라 키를 알아도 열 수 없지만, 키 구조를 밖에 드러낼 이유가 없다.
 *
 * <p><b>파일명은 URL 안에 이미 서명돼 있다.</b> 그래도 함께 내려주는 것은 화면이 "무엇을 받는 중인지" 안내할 수 있어야 하기 때문이다 — 브라우저가 저장할
 * 이름을 프론트가 정하라는 뜻이 아니다 (그 힌트는 다른 오리진 링크에서 무시된다).
 *
 * @param url 이 주소를 열면 곧바로 내려받기가 시작된다. 서버를 거치지 않는다
 * @param originalName 저장될 이름. S3가 {@code Content-Disposition}으로 직접 내려준다
 * @param expiresAt 이 시각이 지나면 다시 발급받아야 한다. <b>이미 시작된 전송은 끊기지 않는다</b>
 */
public record DownloadUrlResponse(
    @Schema(description = "이 주소를 열면 내려받기가 시작된다") URI url,
    @Schema(description = "저장될 이름") String originalName,
    Instant expiresAt) {}
