package org.hackerkhu.hackerhp.domain.photo.dto;

import java.time.Instant;

/**
 * 활동사진 응답 (spec 3-2 §3-2-2 "작성자를 내려주는 규칙").
 *
 * <p>{@code uploaderName}은 <b>절대 null이 아니다</b> (MUST) — 업로더가 탈퇴했으면({@code uploaderId == null})
 * {@code "탈퇴한 회원"}을 담는다. {@code uploaderId}는 본인 것만 수정·삭제 같은 판단에 쓰라고 함께 내려주는 값이라 null일 수 있다.
 *
 * <p>{@code url}은 버킷이 완전 비공개라 매번 새로 발급하는 presigned GET URL이다 — 응답을 캐시해서 재사용하면 안 된다.
 *
 * <p><b>{@code likeCount}·{@code likedByMe}는 항상 함께 온다</b> (#346, 3-3 결정 27). 개수만 보이고 내가 눌렀는지 모르면
 * 화면이 좋아요 버튼을 채울지 비울지 정할 수 없다.
 */
public record PhotoResponse(
    Long id,
    String caption,
    String url,
    String thumbnailUrl,
    Long uploaderId,
    String uploaderName,
    Instant createdAt,
    long likeCount,
    boolean likedByMe) {}
