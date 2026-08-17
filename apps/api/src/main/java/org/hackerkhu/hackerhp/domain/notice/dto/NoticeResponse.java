package org.hackerkhu.hackerhp.domain.notice.dto;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;

/**
 * 공지 응답. 필드는 {@code apps/web/src/api/notices.ts}의 {@code Notice}와 1:1로 맞춘다.
 *
 * <p>레코드 컴포넌트명이 곧 JSON 키다 — {@code isPinned}로 선언해 웹이 기대하는 camelCase 키를 그대로 낸다 (spec/3-2 §3-2-2).
 */
public record NoticeResponse(
    Long id, String title, String content, boolean isPinned, Instant createdAt, Instant updatedAt) {

  public static NoticeResponse from(Notice notice) {
    return new NoticeResponse(
        notice.getId(),
        notice.getTitle(),
        notice.getContent(),
        notice.isPinned(),
        notice.getCreatedAt(),
        notice.getUpdatedAt());
  }
}
