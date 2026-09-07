package org.hackerkhu.hackerhp.domain.post.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hackerkhu.hackerhp.domain.post.dto.PostAuthor;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;

/**
 * 작성자를 <b>한 번에 모아 읽는다.</b> 행마다 읽으면 N건에 질의가 N번 붙는다. {@code Post}·{@code PostComment}가 작성자를 연관관계 없이
 * id로만 갖는 것과 같은 이유로, 조회 시점에 배치로 붙인다.
 *
 * <p>{@code PostService}·{@code PostCommentService}가 이 로직을 각자 두면 한쪽만 고쳐지는 자리가 생긴다 — 두 서비스가 여기로 모은다.
 *
 * <p><b>계정이 사라진 글·댓글은 결과에 없다.</b> {@link PostAuthor#of}가 그 자리를 "탈퇴한 회원"으로 채운다 (2-2 §2-2-4).
 */
final class AuthorLookup {

  private AuthorLookup() {}

  static <T> Map<Long, User> of(List<T> found, Function<T, Long> authorIdOf, UserRepository users) {
    Set<Long> ids =
        found.stream().map(authorIdOf).filter(Objects::nonNull).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return users.findAllById(ids).stream()
        .collect(Collectors.toMap(User::getId, user -> user, (first, second) -> first));
  }

  static PostAuthor authorOf(Long authorId, Map<Long, User> found) {
    return PostAuthor.of(authorId == null ? null : found.get(authorId));
  }
}
