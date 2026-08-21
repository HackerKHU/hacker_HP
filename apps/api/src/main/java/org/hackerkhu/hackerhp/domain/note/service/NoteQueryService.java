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

  public NoteQueryService(
      NoteRepository notes, UserRepository users, BookmarkRepository bookmarks) {
    this.notes = notes;
    this.users = users;
    this.bookmarks = bookmarks;
  }

  /**
   * 검색어와 필터를 <b>AND로 함께</b> 걸어 페이지를 준다 (2-1 §2-1-1 MUST).
   *
   * <p><b>업로더 이름은 한 번에 모아 읽는다.</b> 행마다 읽으면 20건에 질의가 20번 붙는다.
   */
  public Page<NoteSummaryResponse> list(
      Long viewerId, NoteSearch search, NoteSort sort, Pageable pageable) {
    /*
     * 정렬을 Pageable에서 걷어낸다. 계약의 sort는 latest|title이지 Spring Data의 속성 정렬이 아닌데,
     * 그대로 넘기면 ?sort=bogus가 "Note에 그런 속성이 없다"로 500이 된다 — 화면이 보내는 값 하나에
     * 서버가 터지는 셈이다. 순서는 NoteSpecifications가 만든다 (회원 목록과 같은 처리다).
     */
    Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    Page<Note> page = notes.findAll(NoteSpecifications.matching(search, sort), unsorted);
    Map<Long, String> names = uploaderNames(page.getContent());
    return toSummaries(viewerId, page);
  }

  /** 내 즐겨찾기 목록 (#56). <b>정렬은 내가 표시한 순서</b>라 검색·필터를 받지 않는다 — 이미 본인이 추린 목록이다. */
  public Page<NoteSummaryResponse> myBookmarks(Long viewerId, Pageable pageable) {
    return toSummaries(viewerId, bookmarks.findMyNotes(viewerId, pageable));
  }

  public NoteDetailResponse get(Long viewerId, Long id) {
    Note note =
        notes
            .findWithFilesById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다."));
    return NoteDetailResponse.of(
        note,
        uploaderOf(note, uploaderNames(List.of(note))),
        bookmarks.existsByUserIdAndNoteId(viewerId, id));
  }

  /**
   * 페이지를 응답으로 옮긴다.
   *
   * <p><b>업로더 이름·파일 개수·즐겨찾기 여부를 각각 한 번에 모아 읽는다.</b> 행마다 읽으면 20건에 질의가 20번씩 붙는다.
   */
  private Page<NoteSummaryResponse> toSummaries(Long viewerId, Page<Note> page) {
    Map<Long, String> names = uploaderNames(page.getContent());
    Map<Long, Integer> fileCounts = fileCounts(page.getContent());
    Set<Long> bookmarked = bookmarkedIds(viewerId, page.getContent());
    return page.map(
        note ->
            NoteSummaryResponse.of(
                note,
                uploaderOf(note, names),
                fileCounts.getOrDefault(note.getId(), 0),
                bookmarked.contains(note.getId())));
  }

  private Set<Long> bookmarkedIds(Long viewerId, List<Note> found) {
    List<Long> ids = found.stream().map(Note::getId).toList();
    if (ids.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(bookmarks.findNoteIdsOf(viewerId, ids));
  }

  /** 필터 옵션은 <b>실제 등록된 값</b>에서 만든다 (2-1 §2-1-1 MUST). */
  public NoteFilterOptions filters() {
    return new NoteFilterOptions(
        notes.findDistinctSubjectNames(),
        notes.findDistinctProfessors(),
        notes.findDistinctYears());
  }

  /**
   * 업로더 id → 이름.
   *
   * <p><b>계정이 사라진 자료는 여기 없다.</b> 그래서 {@link Uploader#of}가 그 자리를 "탈퇴한 회원"으로 채운다 (2-2 §2-2-4).
   */
  private Map<Long, String> uploaderNames(List<Note> found) {
    Set<Long> ids =
        found.stream()
            .map(Note::getUploaderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return users.findAllById(ids).stream()
        .collect(Collectors.toMap(User::getId, User::getName, (first, second) -> first));
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

  private Uploader uploaderOf(Note note, Map<Long, String> names) {
    Long uploaderId = note.getUploaderId();
    return Uploader.of(uploaderId, uploaderId == null ? null : names.get(uploaderId));
  }
}
