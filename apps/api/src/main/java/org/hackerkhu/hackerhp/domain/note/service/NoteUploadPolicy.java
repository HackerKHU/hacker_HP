package org.hackerkhu.hackerhp.domain.note.service;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * 무엇을 얼마나 받을지 (spec 2-1 §2-1-2 MUST — "허용 확장자와 최대 용량은 설정값으로 제한한다").
 *
 * <p><b>코드에 박지 않는 이유는 바뀔 값이기 때문이다.</b> hwp를 더 받거나 20MB를 늘리는 일은 배포가 아니라 설정으로 끝나야 한다.
 *
 * @param allowedExtensions 점 없이 소문자로 적는다. 비교도 소문자로 한다
 * @param maxFileSize 파일 하나의 상한
 * @param maxFileCount 자료 하나에 붙일 수 있는 파일 수
 */
@Validated
@ConfigurationProperties(prefix = "app.notes.upload")
public record NoteUploadPolicy(
    @NotEmpty List<String> allowedExtensions, DataSize maxFileSize, @Positive int maxFileCount) {

  public NoteUploadPolicy {
    allowedExtensions = allowedExtensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).toList();
  }

  public boolean allows(String extension) {
    return allowedExtensions.contains(extension);
  }

  public boolean tooLarge(long sizeBytes) {
    return sizeBytes > maxFileSize.toBytes();
  }

  /** 화면과 오류 문구가 같은 목록을 쓰게 한다. */
  public Set<String> extensions() {
    return Set.copyOf(allowedExtensions);
  }
}
