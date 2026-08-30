package org.hackerkhu.hackerhp.domain.notice.dto;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.user.dto.DisplayName;
import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * 공지 응답. 필드는 {@code apps/web/src/api/notices.ts}의 {@code Notice}와 1:1로 맞춘다.
 *
 * <p>레코드 컴포넌트명이 곧 JSON 키다 — {@code isPinned}로 선언해 웹이 기대하는 camelCase 키를 그대로 낸다 (spec/3-2 §3-2-2).
 *
 * <p><b>{@code authorName}은 절대 {@code null}이 아니다</b> (3-2 §3-2-2 MUST, #58). 작성자를 지워도 공지는 남으므로(2-2
 * §2-2-4) 그 자리가 빌 수 있는데, {@code null}을 내려보내고 화면이 채우게 하면 화면마다 문구가 갈린다.
 *
 * <p><b>{@code authorId}는 {@code null}이 될 수 있다.</b> 소유자 판단은 이름이 아니라 이 id로 한다 — 이름으로 견주면 "탈퇴한 회원"끼리
 * 서로의 글을 고치게 된다.
 */
public record NoticeResponse(
    Long id,
    String title,
    String content,
    boolean isPinned,
    Long authorId,
    String authorName,
    Instant createdAt,
    Instant updatedAt) {

  public static NoticeResponse from(Notice notice) {
    User author = notice.getAuthor();
    return new NoticeResponse(
        notice.getId(),
        notice.getTitle(),
        notice.getContent(),
        notice.isPinned(),
        author == null ? null : author.getId(),
        DisplayName.of(author),
        notice.getCreatedAt(),
        notice.getUpdatedAt());
  }
}
