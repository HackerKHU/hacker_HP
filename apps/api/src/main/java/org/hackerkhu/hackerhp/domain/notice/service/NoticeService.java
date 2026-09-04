package org.hackerkhu.hackerhp.domain.notice.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hackerkhu.hackerhp.domain.notice.dto.NoticeRequest;
import org.hackerkhu.hackerhp.domain.notice.dto.NoticeResponse;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeLikeRepository;
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
  private final NoticeLikeRepository noticeLikeRepository;

  public NoticeService(
      NoticeRepository noticeRepository,
      UserRepository userRepository,
      NoticeLikeRepository noticeLikeRepository) {
    this.noticeRepository = noticeRepository;
    this.userRepository = userRepository;
    this.noticeLikeRepository = noticeLikeRepository;
  }

  /**
   * 정렬은 항상 {@link #FIXED_SORT}다 — {@code pageable}의 {@code sort}는 무시한다. {@code page}·{@code size}는
   * {@code spring.data.web.pageable}(§3-2-8)이 검증·상한을 이미 처리했으므로 여기서 다시 확인하지 않는다.
   */
  public Page<NoticeResponse> list(Pageable pageable, Long viewerId, boolean liked) {
    PageRequest request =
        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), FIXED_SORT);
    /*
     * 내가 좋아요한 공지만 (#355, 3-3 결정 28). 정렬은 두 갈래가 같은 FIXED_SORT를 쓴다 —
     * 필터를 켠다고 고정 공지가 위로 오는 규칙까지 달라지면 화면이 목록을 두 벌로 다뤄야 한다.
     */
    Page<Notice> page =
        liked ? noticeRepository.findLikedBy(viewerId, request) : noticeRepository.findAll(request);
    List<Long> ids = page.getContent().stream().map(Notice::getId).toList();
    Map<Long, Long> counts = likeCountsOf(ids);
    Set<Long> likedByMe = likedIdsOf(viewerId, ids);
    return page.map(
        notice ->
            NoticeResponse.from(
                notice,
                counts.getOrDefault(notice.getId(), 0L),
                likedByMe.contains(notice.getId())));
  }

  public NoticeResponse get(Long id, Long viewerId) {
    Notice notice =
        noticeRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return withLikeInfo(notice, viewerId);
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
    // 방금 만든 공지라 좋아요가 있을 수 없다 — 조회하지 않고 0/false로 바로 응답한다.
    return NoticeResponse.from(notice, 0L, false);
  }

  @Transactional
  public NoticeResponse update(Long id, NoticeRequest request, Long viewerId) {
    Notice notice =
        noticeRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    notice.edit(request.title(), request.content());
    return withLikeInfo(notice, viewerId);
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
  public NoticeResponse togglePin(Long id, Long viewerId) {
    Notice notice =
        noticeRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (notice.isPinned()) {
      notice.unpin();
    } else {
      notice.pin();
    }
    return withLikeInfo(notice, viewerId);
  }

  /** 공지 하나에 좋아요 정보를 붙인다 — {@link #get}·{@link #update}·{@link #togglePin}이 공유한다. */
  private NoticeResponse withLikeInfo(Notice notice, Long viewerId) {
    long count = noticeLikeRepository.countByNoticeId(notice.getId());
    boolean liked = noticeLikeRepository.existsByUserIdAndNoticeId(viewerId, notice.getId());
    return NoticeResponse.from(notice, count, liked);
  }

  /** 좋아요 개수를 <b>한 번에</b> 모아 읽는다. 행마다 물으면 페이지 크기만큼 질의가 붙는다. */
  private Map<Long, Long> likeCountsOf(List<Long> noticeIds) {
    if (noticeIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, Long> counts = new HashMap<>();
    for (Object[] row : noticeLikeRepository.countByNoticeIds(noticeIds)) {
      counts.put((Long) row[0], (Long) row[1]);
    }
    return counts;
  }

  private Set<Long> likedIdsOf(Long viewerId, List<Long> noticeIds) {
    if (noticeIds.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(noticeLikeRepository.findLikedNoticeIdsOf(viewerId, noticeIds));
  }
}
