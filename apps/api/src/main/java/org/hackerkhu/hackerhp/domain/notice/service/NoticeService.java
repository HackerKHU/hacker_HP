package org.hackerkhu.hackerhp.domain.notice.service;

import org.hackerkhu.hackerhp.domain.notice.dto.NoticeResponse;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공지 조회. 등록·수정·삭제·고정 토글은 ADMIN 전용 API가 맡는다 (spec/2-1 §2-1-6) — 여기서 다루지 않는다. */
@Service
@Transactional(readOnly = true)
public class NoticeService {

  /** 정렬은 클라이언트가 고르지 않는다 — 고정 공지가 항상 최상단에 온다 (spec/2-1 §2-1-6 MUST). */
  private static final Sort FIXED_SORT =
      Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("createdAt"));

  private final NoticeRepository noticeRepository;

  public NoticeService(NoticeRepository noticeRepository) {
    this.noticeRepository = noticeRepository;
  }

  public Page<NoticeResponse> list(int page, int size) {
    return noticeRepository
        .findAll(PageRequest.of(page, size, FIXED_SORT))
        .map(NoticeResponse::from);
  }

  public NoticeResponse get(Long id) {
    Notice notice =
        noticeRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return NoticeResponse.from(notice);
  }
}
