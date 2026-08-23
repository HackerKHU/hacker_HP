package org.hackerkhu.hackerhp.domain.photo.dto;

/**
 * {@code POST /photos/upload-url} 응답의 항목 하나. {@code key}는 그대로 {@code POST /photos} 요청에 담아 돌려줘야 한다 —
 * 서버가 그 키로 원본을 찾아 리사이즈한다.
 */
public record PhotoUploadUrlResponse(String key, String uploadUrl) {}
