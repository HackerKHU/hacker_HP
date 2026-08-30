package org.hackerkhu.hackerhp.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hackerkhu.hackerhp.domain.user.dto.DisplayName;
import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * 응답에 담는 작성자 (spec 3-2 §3-2-2 "작성자를 내려주는 규칙").
 *
 * <p><b>이름은 절대 {@code null}이 아니다</b> (MUST). 작성자 행이 없으면 서버가 {@code "탈퇴한 회원"}을 넣는다 — {@code null}을
 * 내려보내고 화면이 채우게 하면 <b>화면마다 문구가 갈리고</b>, 문구를 바꾸려면 웹을 배포해야 한다.
 *
 * <p><b>id는 {@code null}이 될 수 있다.</b> 나중에 "본인 것만 수정"(#256)을 판단할 때 이 id를 쓴다 — 이름으로 견주면 "탈퇴한 회원"끼리
 * 서로의 글을 고칠 수 있다.
 */
public record PostAuthor(
    @Schema(description = "탈퇴했으면 `null`") Long id,
    @Schema(description = "탈퇴했으면 `\"탈퇴한 회원\"`") String name) {

  /**
   * <b>계정을 통째로 받는다</b> (MUST, #301) — 이유는 {@link DisplayName}에 있다.
   *
   * @param user 탈퇴했으면 {@code null}
   */
  public static PostAuthor of(User user) {
    return new PostAuthor(user == null ? null : user.getId(), DisplayName.of(user));
  }
}
