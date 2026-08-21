package org.hackerkhu.hackerhp.domain.user.dto;

/**
 * 계정이 사라진 자리에 넣는 문구 (spec 2-2 §2-2-4, 3-2 §3-2-2).
 *
 * <p><b>서버가 채운다</b> (MUST). {@code null}을 내려보내고 화면이 채우게 하면 <b>화면마다 문구가 갈리고</b>, 문구를 바꾸려면 웹을 배포해야
 * 한다.
 *
 * <p>여기 한 곳에 둔다. 자료·공지·활동사진이 같은 문구를 써야 하는데 각자 적으면 <b>한 곳만 고쳐진다.</b>
 */
public final class WithdrawnMember {

  /** 원본은 [2-2 §2-2-4]다. */
  public static final String NAME = "탈퇴한 회원";

  private WithdrawnMember() {}
}
