package org.hackerkhu.hackerhp.domain.note.dto;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code GET /notes}의 정렬 (spec 3-2 §3-2-4).
 *
 * <p><b>허용 목록이다.</b> 임의 필드 정렬을 열면 인덱스 없는 컬럼으로 전체를 정렬하는 요청이 들어올 수 있고, 계약에도 세 가지뿐이다.
 */
public enum NoteSort {

  /** 기본값. 최신순 (2-1 §2-1-1). */
  LATEST("latest"),

  /** 제목순 (2-1 §2-1-1 MAY). */
  TITLE("title"),

  /**
   * 조회수 많은 순 (2-1 §2-1-1 MUST, #245).
   *
   * <p><b>값을 더해도 낡은 화면이 깨지지 않는다.</b> 모르는 값을 기본값으로 보는 아래 규칙 덕분에, 이 값을 아직 모르는 화면은 그냥 최신순을 받는다 — 학기처럼
   * {@code 400}이 나가지 않아 배포 순서를 타지 않는다 (3-2 §3-2-4).
   */
  VIEWS("views");

  private final String value;

  NoteSort(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** 모르는 값은 기본값으로 본다. 정렬은 화면이 조합해 보내는 값이라 {@code 400}으로 막을 이유가 없다. */
  public static NoteSort from(String value) {
    if (value == null) {
      return LATEST;
    }
    String normalized = value.toLowerCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(sort -> sort.value.equals(normalized))
        .findFirst()
        .orElse(LATEST);
  }
}
