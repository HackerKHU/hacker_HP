package org.hackerkhu.hackerhp.domain.note.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.dto.NoteFilterOptions;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSearch;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSort;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSummaryResponse;
import org.hackerkhu.hackerhp.domain.note.dto.Uploader;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.repository.BookmarkRepository;
import org.hackerkhu.hackerhp.domain.note.repository.NoteLikeRepository;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.note.repository.NoteSpecifications;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 자료 조회 (spec 2-1 §2-1-1, 3-2 §3-2-4). 이 서비스는 읽기만 한다 — 등록·수정은 #53·#54가 얹는다. */
@Service
@Transactional(readOnly = true)
public class NoteQueryService {

  private final NoteRepository notes;
  private final UserRepository users;
  private final BookmarkRepository bookmarks;
  private final NoteLikeRepository likes;

  public NoteQueryService(
      NoteRepository notes,
      UserRepository users,
      BookmarkRepository bookmarks,
      NoteLikeRepository likes) {
    this.notes = notes;
    this.users = users;
    this.bookmarks = bookmarks;
    this.likes = likes;
  }

  /**
   * 검색어와 필터를 <b>AND로 함께</b> 걸어 페이지를 준다 (2-1 §2-1-1 MUST).
   *
   * <p><b>업로더 이름은 한 번에 모아 읽는다.</b> 행마다 읽으면 20건에 질의가 20번 붙는다.
   */
  public Page<NoteSummaryResponse> list(
      Long viewerId, NoteSearch search, NoteSort sort, Pageable pageable) {
    Page<Note> page =
        notes.findAll(NoteSpecifications.matching(search, sort), fixedOrder(pageable));
    return toSummaries(page, viewerId, bookmarkedIds(viewerId, page.getContent()));
  }

  /**
   * 내 즐겨찾기 목록 (#56). <b>정렬은 내가 표시한 순서</b>라 검색·필터를 받지 않는다 — 이미 본인이 추린 목록이다.
   *
   * <p>여기도 정렬을 걷어낸다. 그대로 넘기면 {@code ?sort=...}가 질의에 붙어 <b>고정 정렬이 깨지고</b>, 없는 속성 이름 하나에 {@code 500}이
   * 난다.
   */
  public Page<NoteSummaryResponse> myBookmarks(Long viewerId, Pageable pageable) {
    Page<Note> page = bookmarks.findMyNotes(viewerId, fixedOrder(pageable));
    /*
     * 다시 묻지 않는다. 이 목록에 있다는 것이 곧 담겨 있다는 뜻이고, 한 번 더 물으면 그
     * 사이에 해제된 항목이 bookmarked=false로 돌아온다 — "이 목록에서는 언제나 true"라는
     * 계약이 깨진다 (#189 리뷰). 질의도 하나 준다.
     */
    return toSummaries(
        page, viewerId, page.getContent().stream().map(Note::getId).collect(Collectors.toSet()));
  }

  /**
   * 쪽 번호와 크기만 남긴다.
   *
   * <p><b>순서는 서버가 정한다.</b> 자료 목록은 {@code NoteSpecifications}가, 즐겨찾기는 질의문이 만든다. {@code Pageable}의
   * 정렬을 그대로 넘기면 그 순서를 덮어쓰고, 계약에 없는 속성 이름 하나에 {@code 500}이 난다 (#52·#189 리뷰).
   */
  private static Pageable fixedOrder(Pageable pageable) {
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
  }

  /**
   * <b>여기서 조회수를 올리지 않는다</b> (#245). 이 클래스는 통째로 읽기 전용 트랜잭션이라 {@code UPDATE}가 거절당하고, 읽기 전용을 풀더라도 같은
   * 트랜잭션이면 증가 실패가 조회까지 되돌린다. 올리는 것은 {@code NoteViewService}가 이 메서드가 끝난 뒤에 한다.
   *
   * <p>그래서 여기서 만든 응답의 {@code viewCount}는 <b>올리기 전 값</b>이다.
   */
  public NoteDetailResponse get(Long viewerId, Long id) {
    Note note =
        notes
            .findWithFilesById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다."));
    NoteLikeSummary likeSummary =
        likeSummariesOf(viewerId, List.of(id)).getOrDefault(id, NoteLikeSummary.NONE);
    return NoteDetailResponse.of(
        note,
        uploaderOf(note, uploaders(List.of(note))),
        bookmarks.existsByUserIdAndNoteId(viewerId, id),
        note.getViewCount(),
        likeSummary.count(),
        likeSummary.likedByMe());
  }

  /**
   * 페이지를 응답으로 옮긴다.
   *
   * <p><b>업로더 이름·파일 개수를 각각 한 번에 모아 읽는다.</b> 행마다 읽으면 20건에 질의가 20번씩 붙는다.
   *
   * @param bookmarked 이 페이지에서 <b>내가 담은</b> 자료의 id. 부르는 쪽이 정한다 — 즐겨찾기 목록에서는 물어볼 필요가 없다
   */
  private Page<NoteSummaryResponse> toSummaries(
      Page<Note> page, Long viewerId, Set<Long> bookmarked) {
    Map<Long, User> found = uploaders(page.getContent());
    Map<Long, Integer> fileCounts = fileCounts(page.getContent());
    Map<Long, NoteLikeSummary> likeSummaries =
        likeSummariesOf(viewerId, page.getContent().stream().map(Note::getId).toList());
    return page.map(
        note -> {
          NoteLikeSummary like = likeSummaries.getOrDefault(note.getId(), NoteLikeSummary.NONE);
          return NoteSummaryResponse.of(
              note,
              uploaderOf(note, found),
              fileCounts.getOrDefault(note.getId(), 0),
              bookmarked.contains(note.getId()),
              like.count(),
              like.likedByMe());
        });
  }

  private Set<Long> bookmarkedIds(Long viewerId, List<Note> found) {
    List<Long> ids = found.stream().map(Note::getId).toList();
    if (ids.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(bookmarks.findNoteIdsOf(viewerId, ids));
  }

  /**
   * 좋아요 개수와 내 상태를 <b>한 번에</b> 모아 읽는다. 행마다 물으면 페이지 크기만큼 질의가 붙고, 개수와 내 상태를 따로 물으면 스냅샷이 갈려 모순된 응답이 나간다
   * (#367 리뷰, {@link NoteLikeSummary}).
   */
  private Map<Long, NoteLikeSummary> likeSummariesOf(Long viewerId, List<Long> noteIds) {
    if (noteIds.isEmpty()) {
      return Map.of();
    }
    return NoteLikeSummary.byNoteId(likes.countWithMineByNoteIds(viewerId, noteIds));
  }

  /** 필터 옵션은 <b>실제 등록된 값</b>에서 만든다 (2-1 §2-1-1 MUST). */
  public NoteFilterOptions filters() {
    return new NoteFilterOptions(
        notes.findDistinctSubjectNames(),
        notes.findDistinctProfessors(),
        notes.findDistinctYears());
  }

  /**
   * 업로더 id → 계정.
   *
   * <p><b>이름만 뽑아 두지 않는다</b> (#301). 표시 이름은 이름과 학번을 함께 봐야 만들어진다 ({@code DisplayName}) — 여기서 이름만 남기면
   * 그 규칙이 이 도메인에서만 갈린다. 계정을 통째로 들고 있어도 <b>질의는 그대로 한 번</b>이다.
   *
   * <p><b>계정이 사라진 자료는 여기 없다.</b> 그래서 {@link Uploader#of}가 그 자리를 "탈퇴한 회원"으로 채운다 (2-2 §2-2-4).
   */
  private Map<Long, User> uploaders(List<Note> found) {
    Set<Long> ids =
        found.stream()
            .map(Note::getUploaderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return users.findAllById(ids).stream()
        .collect(Collectors.toMap(User::getId, user -> user, (first, second) -> first));
  }

  /** 파일 개수도 한 번에 모아 온다. 행마다 세면 20건에 질의가 20번 붙는다. */
  private Map<Long, Integer> fileCounts(List<Note> found) {
    List<Long> ids = found.stream().map(Note::getId).toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return notes.countFilesByNoteIds(ids).stream()
        .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).intValue()));
  }

  private Uploader uploaderOf(Note note, Map<Long, User> found) {
    Long uploaderId = note.getUploaderId();
    return Uploader.of(uploaderId == null ? null : found.get(uploaderId));
  }
}
