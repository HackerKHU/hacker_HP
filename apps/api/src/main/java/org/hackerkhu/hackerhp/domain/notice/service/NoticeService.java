package org.hackerkhu.hackerhp.domain.notice.service;

import org.hackerkhu.hackerhp.domain.notice.dto.NoticeRequest;
import org.hackerkhu.hackerhp.domain.notice.dto.NoticeResponse;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공지 조회·등록·수정·삭제·고정 토글 (spec 2-1 §2-1-6, 2-2 §2-2-6). */
@Service
@Transactional(readOnly = true)
public class NoticeService {

  /** 정렬은 클라이언트가 고르지 않는다 — 고정 공지가 항상 최상단에 온다 (spec/2-1 §2-1-6 MUST). */
  private static final Sort FIXED_SORT =
      Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("createdAt"));

  private final NoticeRepository noticeRepository;
  private final UserRepository userRepository;

  public NoticeService(NoticeRepository noticeRepository, UserRepository userRepository) {
    this.noticeRepository = noticeRepository;
    this.userRepository = userRepository;
  }

  /**
   * 정렬은 항상 {@link #FIXED_SORT}다 — {@code pageable}의 {@code sort}는 무시한다. {@code page}·{@code size}는
   * {@code spring.data.web.pageable}(§3-2-8)이 검증·상한을 이미 처리했으므로 여기서 다시 확인하지 않는다.
   */
  public Page<NoticeResponse> list(Pageable pageable) {
    return noticeRepository
        .findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), FIXED_SORT))
        .map(NoticeResponse::from);
  }

  public NoticeResponse get(Long id) {
    Notice notice =
        noticeRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return NoticeResponse.from(notice);
  }

  /**
   * 작성자는 인증 주체의 id로만 정한다 — 요청 본문으로 받지 않는다. 그러면 다른 사람 이름으로 공지를 등록할 수 있다.
   *
   * <p>참조만 필요하므로 {@code getReferenceById}를 쓴다. 작성자 정보를 이 요청에서 쓰지 않는데 굳이 조회할 이유가 없다.
   */
  @Transactional
  public NoticeResponse create(Long authorId, NoticeRequest request) {
    User author = userRepository.getReferenceById(authorId);
    Notice notice = Notice.write(request.title(), request.content(), author);
    noticeRepository.save(notice);
    return NoticeResponse.from(notice);
  }

  @Transactional
  public NoticeResponse update(Long id, NoticeRequest request) {
    Notice notice =
        noticeRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    notice.edit(request.title(), request.content());
    return NoticeResponse.from(notice);
  }

  @Transactional
  public void delete(Long id) {
    if (!noticeRepository.existsById(id)) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    noticeRepository.deleteById(id);
  }

  /** 고정 개수 상한은 두지 않는다 (spec 2-1 §2-1-6). 여러 개가 고정되면 {@link #FIXED_SORT}가 등록일 최신순으로 가른다. */
  @Transactional
  public NoticeResponse togglePin(Long id) {
    Notice notice =
        noticeRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (notice.isPinned()) {
      notice.unpin();
    } else {
      notice.pin();
    }
    return NoticeResponse.from(notice);
  }
}
