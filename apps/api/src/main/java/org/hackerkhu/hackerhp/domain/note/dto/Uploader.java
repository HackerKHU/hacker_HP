package org.hackerkhu.hackerhp.domain.note.dto;

import org.hackerkhu.hackerhp.domain.user.dto.WithdrawnMember;

/**
 * 응답에 담는 업로더 (spec 3-2 §3-2-2 "작성자를 내려주는 규칙", #49).
 *
 * <p><b>이름은 절대 {@code null}이 아니다</b> (MUST). 업로더 행이 없으면 서버가 {@code "탈퇴한 회원"}을 넣는다 — {@code null}을
 * 내려보내고 화면이 채우게 하면 <b>화면마다 문구가 갈리고</b>, 문구를 바꾸려면 웹을 배포해야 한다.
 *
 * <p><b>id는 {@code null}이 될 수 있다.</b> "본인 것만 수정·삭제" 판단은 이 id로 한다 (3-1 §3-1-3) — 이름으로 견주면 "탈퇴한 회원"끼리
 * 서로의 자료를 지운다.
 *
 * @param id 탈퇴했으면 {@code null}
 * @param name 탈퇴했으면 {@code "탈퇴한 회원"}
 */
public record Uploader(Long id, String name) {

  /** 계정이 사라졌을 때 그 자리에 넣는 문구. 자료·공지·사진이 같은 문구를 써야 해서 한 곳에 둔다. */
  public static final String WITHDRAWN = WithdrawnMember.NAME;

  public static Uploader of(Long id, String name) {
    return (id == null || name == null) ? new Uploader(null, WITHDRAWN) : new Uploader(id, name);
  }
}
