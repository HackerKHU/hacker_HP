package org.hackerkhu.hackerhp.domain.note.dto;

import org.hackerkhu.hackerhp.domain.user.dto.DisplayName;
import org.hackerkhu.hackerhp.domain.user.dto.WithdrawnMember;
import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * 응답에 담는 업로더 (spec 3-2 §3-2-2 "작성자를 내려주는 규칙", #49).
 *
 * <p><b>이름은 절대 {@code null}이 아니다</b> (MUST). 업로더 행이 없으면 서버가 {@code "탈퇴한 회원"}을 넣는다 — {@code null}을
 * 내려보내고 화면이 채우게 하면 <b>화면마다 문구가 갈리고</b>, 문구를 바꾸려면 웹을 배포해야 한다.
 *
 * <p><b>id는 {@code null}이 될 수 있다.</b> "본인 것만 수정·삭제" 판단은 이 id로 한다 (3-1 §3-1-3) — 이름으로 견주면 "탈퇴한 회원"끼리
 * 서로의 자료를 지운다.
 *
 * <p><b>{@code name}은 계정 이름이 아니라 표시 이름이다</b> (#301) — 이름 + 학번 끝 두 자리다 ({@link DisplayName}).
 *
 * @param id 탈퇴했으면 {@code null}
 * @param name 탈퇴했으면 {@code "탈퇴한 회원"}
 */
public record Uploader(Long id, String name) {

  /** 계정이 사라졌을 때 그 자리에 넣는 문구. 자료·공지·사진이 같은 문구를 써야 해서 한 곳에 둔다. */
  public static final String WITHDRAWN = WithdrawnMember.NAME;

  /**
   * <b>계정을 통째로 받는다</b> (MUST, #301). 이름과 학번을 따로 받으면 학번을 빼먹는 호출이 컴파일되고, 그러면 그 도메인만 표시가 달라진다.
   *
   * @param user 탈퇴했으면 {@code null}
   */
  public static Uploader of(User user) {
    return new Uploader(user == null ? null : user.getId(), DisplayName.of(user));
  }
}
