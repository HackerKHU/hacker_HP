package org.hackerkhu.hackerhp.domain.note.service;

import java.util.Locale;
import java.util.UUID;

/**
 * 자료 파일의 S3 키 (#53 D2·D3).
 *
 * <table>
 *   <caption>두 자리</caption>
 *   <tr><th>자리<th>키<th>수명
 *   <tr><td>올린 직후<td>{@code notes/uploads/{userId}/{uuid}.{ext}}<td><b>하루</b> — 라이프사이클 규칙이 걷어간다
 *   <tr><td>등록된 뒤<td>{@code notes/{uuid}.{ext}}<td>자료를 지울 때까지
 * </table>
 *
 * <p><b>왜 두 자리인가.</b> 브라우저가 파일만 올리고 등록(③)을 부르지 않는 일은 흔하다 — 창을 닫거나, 네트워크가 끊기거나, 그냥 마음을 바꾼다. 한 자리만 쓰면
 * 그렇게 남은 파일과 멀쩡히 등록된 파일이 <b>같은 프리픽스에 섞여 구분할 수 없다.</b> 임시 자리를 따로 두면 만료 규칙이 그것만 골라 걷어간다.
 *
 * <p><b>왜 키에 업로더를 박는가.</b> 등록은 "올라온 키 목록"을 그대로 받는다 — 키 문자열만 알면 <b>남이 올린 파일을 자기 자료로 등록할 수 있다.</b> 키에
 * 업로더를 박아 두면 등록 시점에 대조 한 번으로 막힌다. 서명 키를 따로 관리할 필요도 없다.
 */
public final class NoteObjectKey {

  private static final String STAGING_ROOT = "notes/uploads/";
  private static final String STORED_ROOT = "notes/";

  private NoteObjectKey() {}

  /** 그 사람만 쓸 수 있는 임시 자리. */
  public static String staging(Long uploaderId, String extension) {
    return STAGING_ROOT + uploaderId + "/" + UUID.randomUUID() + "." + extension;
  }

  /** 그 사람이 올린 키인가. 아니면 등록을 거절한다. */
  public static boolean stagedBy(String key, Long uploaderId) {
    return key != null && key.startsWith(STAGING_ROOT + uploaderId + "/");
  }

  /**
   * 최종 자리의 키. <b>등록할 때마다 새로 뽑는다</b> (#207 리뷰).
   *
   * <p>임시 키에서 이름을 물려받으면 <b>같은 임시 키가 두 번 등록될 때 최종 키가 겹친다.</b> 첫 등록이 임시본을 지워도 발급한 presigned URL은 만료까지
   * 살아 있어, 같은 자리에 다른 파일을 올리고 다시 등록할 수 있다 — 그러면 두 번째 복사가 <b>첫 자료의 파일을 덮어쓰고</b>, 첫 자료는 DB에 적힌 이름·크기와
   * 실제 내용이 어긋난 채 남는다.
   *
   * <p>이름을 물려받으면 임시와 최종을 눈으로 이어 볼 수 있어 장애 추적이 편하지만, <b>그 편의가 자료를 조용히 바꿔치기당하는 값을 하지는 않는다.</b> 추적은
   * 로그가 맡는다.
   */
  public static String stored(String extension) {
    return STORED_ROOT + UUID.randomUUID() + "." + extension;
  }

  /**
   * 파일명에서 확장자를 뽑는다. 소문자로 돌려준다.
   *
   * <p>점이 없거나 끝이 점이면 빈 문자열이다 — 허용 목록에 빈 값이 없으므로 그대로 거절된다.
   */
  public static String extensionOf(String originalName) {
    if (originalName == null) {
      return "";
    }
    int dot = originalName.lastIndexOf('.');
    return dot < 0 ? "" : originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
