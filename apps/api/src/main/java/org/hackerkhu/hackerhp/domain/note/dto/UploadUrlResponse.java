package org.hackerkhu.hackerhp.domain.note.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * 발급 결과 (spec 2-1 §2-1-2).
 *
 * <p>브라우저는 각 {@code url}에 파일을 {@code PUT}으로 올리고, 끝나면 {@code key}를 모아 {@code POST /notes}에 낸다.
 *
 * <p><b>여기서 주는 key는 임시 자리다.</b> 등록되지 않으면 하루 뒤에 사라진다 — 창을 닫거나 마음을 바꾼 업로드가 버킷에 영원히 쌓이지 않게 하는 장치다.
 */
public record UploadUrlResponse(List<Upload> uploads) {

  /**
   * @param originalName 요청에 담아 보낸 이름 그대로. 여러 개를 발급받았을 때 짝을 맞추는 열쇠다
   * @param key 등록할 때 그대로 돌려보낸다
   * @param url 이 주소로 {@code PUT} 한 번이면 업로드가 끝난다
   * @param expiresAt 이 시각이 지나면 다시 발급받아야 한다
   */
  public record Upload(
      String originalName,
      @Schema(description = "등록(`POST /notes`)에 그대로 돌려보낼 값") String key,
      @Schema(description = "이 주소로 PUT 한다. 서버를 거치지 않는다") URI url,
      Instant expiresAt) {}
}
