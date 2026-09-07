package org.hackerkhu.hackerhp.domain.note.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code (회원, 자료)} 복합 키 (spec 3-2 §3-2-2, #344).
 *
 * <p>이 조합이 <b>유일하다</b>는 것이 요구사항이고, 그 보장은 DB의 복합 PK가 한다 — 애플리케이션 검사만으로는 동시에 도착한 두 요청이 모두 통과한다
 * ({@code BookmarkId}와 같은 이유).
 */
public record NoteLikeId(Long userId, Long noteId) implements Serializable {

  /** JPA가 요구하는 기본 생성자 자리. record라 컴팩트 생성자로 대신한다. */
  public NoteLikeId {
    Objects.requireNonNull(userId);
    Objects.requireNonNull(noteId);
  }
}
