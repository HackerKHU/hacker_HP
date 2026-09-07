package org.hackerkhu.hackerhp.domain.note.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.dto.NoteUpdateRequest;
import org.hackerkhu.hackerhp.domain.note.dto.Uploader;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.entity.NoteFile;
import org.hackerkhu.hackerhp.domain.note.repository.BookmarkRepository;
import org.hackerkhu.hackerhp.domain.note.repository.NoteLikeRepository;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자료 수정·삭제 (spec 2-1 §2-1-3 MUST, 3-1 §3-1-3, 3-1 §3-1-7).
 *
 * <p><b>본인 것만 고치고 지운다. {@code ADMIN}은 전체.</b> 화면이 버튼을 숨기는 것과 별개로 서버가 소유자를 확인한다 (§3-1-7 MUST).
 *
 * <p><b>권한 판단의 근거는 잠근 계정 행이다.</b> 세션의 {@code role}은 인가를 지난 뒤 회수됐을 수 있다 — 권한이 회수된 관리자의 대기 중 요청이 남의
 * 자료를 지우면 안 된다.
 *
 * <p><b>S3 정리는 DB 커밋 뒤이고, 실패해도 요청은 성공이다</b> (§2-1-3 SHOULD). 사용자에게 삭제는 이미 끝난 일이고, 여기서 {@code 500}을
 * 주면 재요청해도 자료가 없어 영원히 실패한다.
 */
@Service
public class NoteEditService {

  private static final Logger log = LoggerFactory.getLogger(NoteEditService.class);
  private static final int TITLE_MAX_CODE_POINTS = 50;
  private static final int STORED_TITLE_MAX_CODE_POINTS = 200;

  private final NoteRepository notes;
  private final BookmarkRepository bookmarks;
  private final NoteLikeRepository likes;
  private final UserRepository users;
  private final StagedUploads staged;
  private final NoteUploadPolicy policy;
  private final TransactionTemplate transaction;

  public NoteEditService(
      NoteRepository notes,
      BookmarkRepository bookmarks,
      NoteLikeRepository likes,
      UserRepository users,
      StagedUploads staged,
      NoteUploadPolicy policy,
      PlatformTransactionManager transactionManager) {
    this.notes = notes;
    this.bookmarks = bookmarks;
    this.likes = likes;
    this.users = users;
    this.staged = staged;
    this.policy = policy;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /* ------------------------------------------------------------------ 수정 */

  /**
   * 보낸 것으로 통째로 바꾼다.
   *
   * <table>
   *   <caption>순서와 이유</caption>
   *   <tr><th>①<td>고칠 수 있는 자료인지 <b>먼저</b> 본다<td>남의 자료면 파일을 옮기기도 전에 끊는다
   *   <tr><th>②<td>새 파일을 최종 자리로 옮긴다<td>{@link StagedUploads}가 검사까지 함께 한다
   *   <tr><th>③<td>잠근 채 다시 확인하고 저장<td>①과 ③ 사이에 권한이 바뀔 수 있다
   *   <tr><th>④<td>임시본과 <b>떨어져 나온 파일</b>을 치운다<td>커밋 뒤다. 실패해도 되돌리지 않는다
   * </table>
   */
  public NoteDetailResponse update(Long requesterId, Long noteId, NoteUpdateRequest request) {
    NoteMetadata.requireCategoryMatchesExamType(request.category(), request.examType());
    List<NoteUpdateRequest.FileRef> files = distinctRefs(request.files());

    // ① 옮기기 전에 끊는다. 권한이 없는 요청이 S3를 건드릴 이유가 없다.
    requireEditable(requesterId, noteId, request.title());

    List<StagedUploads.Claim> claims =
        files.stream()
            .filter(NoteUpdateRequest.FileRef::isNew)
            .map(ref -> new StagedUploads.Claim(ref.key(), ref.originalName()))
            .toList();

    // ② 새로 붙일 것만 옮긴다. 그대로 두는 파일은 손대지 않는다.
    List<StagedUploads.Stored> stored = staged.claim(requesterId, claims);

    Applied applied;
    try {
      applied = transaction.execute(ignored -> apply(requesterId, noteId, request, files, stored));
    } catch (RuntimeException e) {
      staged.discardStored(stored.stream().map(StagedUploads.Stored::storedKey).toList(), e);
      throw e;
    }

    // ④ 커밋 뒤다. 여기부터는 무엇이 실패해도 수정을 무르지 않는다.
    staged.discardStaging(stored.stream().map(StagedUploads.Stored::stagingKey).toList());
    staged.discardDetached(applied.detachedKeys());
    return applied.response();
  }

  /** 응답과 <b>떨어져 나온 파일의 키</b>를 함께 돌려준다 — 그것은 커밋 뒤에 지워야 한다. */
  private record Applied(NoteDetailResponse response, List<String> detachedKeys) {}

  private Applied apply(
      Long requesterId,
      Long noteId,
      NoteUpdateRequest request,
      List<NoteUpdateRequest.FileRef> refs,
      List<StagedUploads.Stored> stored) {
    User requester = lockRequester(requesterId);
    Note note = lockNote(noteId);
    requireOwnerOrAdmin(requester, note, requesterId);
    requireAllowedTitleChange(note, request.title());

    Map<Long, NoteFile> existing =
        note.getFiles().stream().collect(Collectors.toMap(NoteFile::getId, Function.identity()));

    /*
     * 남길 파일을 요청 순서대로 모은다. 새로 옮긴 것은 앞에서 이미 순서가 정해져 있으므로
     * 하나씩 꺼내 쓴다 — refs와 stored의 순서가 같다는 전제가 여기 있다.
     */
    List<NoteFile> remaining = new ArrayList<>();
    int next = 0;
    for (NoteUpdateRequest.FileRef ref : refs) {
      if (ref.isExisting()) {
        NoteFile found = existing.get(ref.fileId());
        if (found == null) {
          throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이 자료의 파일이 아닙니다.");
        }
        remaining.add(found);
      } else {
        StagedUploads.Stored file = stored.get(next++);
        remaining.add(
            NoteFile.stored(note, file.originalName(), file.storedKey(), file.sizeBytes()));
      }
    }

    List<String> detached =
        note.getFiles().stream()
            .filter(file -> remaining.stream().noneMatch(kept -> kept == file))
            .map(NoteFile::getStoredPath)
            .toList();

    note.edit(
        request.category(),
        request.title().trim(),
        request.subjectName().trim(),
        NoteMetadata.blankToNull(request.professor()),
        request.year(),
        request.semester(),
        request.examType(),
        Instant.now());
    note.keepOnly(remaining);
    /*
     * save()를 부르지 않는다. note는 이 트랜잭션이 읽어 온 관리 상태라 변경은 저절로 반영되고,
     * id가 있는 엔티티에 save()를 부르면 merge가 된다 — merge는 PERSIST 캐스케이드를 타지 않아
     * 새로 붙인 파일이 주인 없는 행으로 들어가려다 note_id NOT NULL에 걸린다.
     *
     * flush만 부르는 것은 제약 위반을 이 트랜잭션 안에서 보기 위해서다. 나중에 터지면
     * 옮겨 둔 파일을 되돌릴 자리를 지나친 뒤다.
     */
    notes.flush();

    log.info("자료 수정: requesterId={} noteId={} 뗀 파일 {}개", requesterId, noteId, detached.size());
    return new Applied(detailOf(note, requesterId), detached);
  }

  /* ------------------------------------------------------------------ 삭제 */

  /**
   * 자료를 지운다.
   *
   * <p><b>첨부와 즐겨찾기는 DB가 지운다</b> (§2-1-3 MUST) — {@code ON DELETE CASCADE}다. 애플리케이션이 하나씩 지우는 방식은 한
   * 곳만 빠뜨려도 자료 삭제가 FK 위반으로 막힌다.
   */
  public void delete(Long requesterId, Long noteId) {
    List<String> storedKeys = transaction.execute(ignored -> remove(requesterId, noteId));

    /*
     * S3 정리는 커밋 뒤다. 실패해도 요청은 성공이다 (§2-1-3 SHOULD) — 사용자에게 삭제는
     * 이미 끝난 일이고, 여기서 500을 주면 재요청해도 자료가 없어 영원히 실패한다.
     */
    staged.discardDetached(storedKeys);
  }

  private List<String> remove(Long requesterId, Long noteId) {
    User requester = lockRequester(requesterId);
    Note note = lockNote(noteId);
    requireOwnerOrAdmin(requester, note, requesterId);

    List<String> storedKeys = note.getFiles().stream().map(NoteFile::getStoredPath).toList();
    notes.delete(note);
    log.warn("자료 삭제: requesterId={} noteId={} 파일 {}개", requesterId, noteId, storedKeys.size());
    return storedKeys;
  }

  /* ------------------------------------------------------------------ 도구 */

  /**
   * 옮기기 전에 보는 <b>가벼운</b> 확인. 잠그지 않는다.
   *
   * <p>이것만으로는 부족하다 — 확인과 저장 사이에 권한이 바뀔 수 있다. 그래서 저장 트랜잭션이 잠근 행으로 다시 본다. 여기서 먼저 끊는 것은 <b>권한 없는 요청이
   * S3를 건드리지 않게</b> 하기 위해서다.
   */
  private void requireEditable(Long requesterId, Long noteId, String requestedTitle) {
    User requester = users.findById(requesterId).orElse(null);
    Note note = loadNote(noteId);
    requireOwnerOrAdmin(requester, note, requesterId);
    // 새 파일을 최종 자리로 옮기기 전에 명백한 제목 위반도 함께 끊는다.
    requireAllowedTitleChange(note, requestedTitle);
  }

  /**
   * 새로 쓰는 제목은 50자까지다. 다만 이미 DB에 있는 51~200자 제목은 원문을 그대로 보낼 때만 유지할 수 있다 — 자료 수정은 전체 교체라 이 예외가 없으면 파일
   * 하나만 고쳐도 제목부터 줄여야 한다 (spec 3-2 §3-2-4).
   */
  private void requireAllowedTitleChange(Note note, String requestedTitle) {
    String normalized = requestedTitle.trim();
    if (normalized.codePointCount(0, normalized.length()) > STORED_TITLE_MAX_CODE_POINTS) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "기존 제목의 저장 상한을 넘었습니다.");
    }
    if (normalized.equals(note.getTitle())) {
      return;
    }
    if (normalized.codePointCount(0, normalized.length()) > TITLE_MAX_CODE_POINTS) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "제목은 50자까지 쓸 수 있습니다.");
    }
  }

  private User lockRequester(Long requesterId) {
    User requester = users.findByIdForUpdate(requesterId).orElse(null);
    RequesterCheck.requireNoteAccess(requester, requesterId);
    return requester;
  }

  /**
   * <b>같은 자료의 수정·삭제를 한 줄로 세운다</b> (#211 리뷰).
   *
   * <p>잠금 순서는 <b>계정 → 자료</b>로 고정한다. 수정과 삭제가 같은 순서로 잡으므로 서로를 기다리다 엇갈리지 않는다.
   *
   * <p>파일은 잠근 <b>뒤에</b> 읽는다 — {@code FOR UPDATE}는 바깥 조인의 널 쪽에 걸 수 없어 함께 읽어올 수 없다.
   */
  private Note lockNote(Long noteId) {
    Note note =
        notes
            .findByIdForUpdate(noteId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다."));
    note.getFiles().size();
    return note;
  }

  private Note loadNote(Long noteId) {
    return notes
        .findWithFilesById(noteId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다."));
  }

  /**
   * <b>본인 것이거나 {@code ADMIN}이어야 한다</b> (3-1 §3-1-3).
   *
   * <p>업로더가 비어 있는 자료(탈퇴한 회원의 것)는 {@code ADMIN}만 손댈 수 있다 — 주인이 없으므로 "본인"이 성립하지 않는다.
   */
  private void requireOwnerOrAdmin(User requester, Note note, Long requesterId) {
    RequesterCheck.requireNoteAccess(requester, requesterId);
    if (requester.getRole() == Role.ADMIN) {
      return;
    }
    if (!requesterId.equals(note.getUploaderId())) {
      log.info("남의 자료를 고치거나 지우려 했다: requesterId={} noteId={}", requesterId, note.getId());
      throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 올린 자료만 수정·삭제할 수 있습니다.");
    }
  }

  private List<NoteUpdateRequest.FileRef> distinctRefs(List<NoteUpdateRequest.FileRef> refs) {
    refs.forEach(
        ref -> {
          if (ref.isExisting() == ref.isNew()) {
            throw new BusinessException(
                ErrorCode.VALIDATION_ERROR, "남길 파일은 기존 파일이거나 새로 올린 파일이어야 합니다.");
          }
          /*
           * 새로 붙이는 파일에는 이름이 있어야 한다 (#211 리뷰).
           *
           * 없어도 뒤에서 걸리기는 한다 — 빈 확장자가 허용 목록에 없어 415가 나간다. 그런데
           * 이것은 파일 형식 문제가 아니라 요청 형식 문제다. 415로 답하면 화면은 "이 파일은
           * 못 올린다"고 안내하고, 사용자는 멀쩡한 파일을 바꾸려 든다.
           */
          if (ref.isNew() && (ref.originalName() == null || ref.originalName().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "새로 붙이는 파일에는 파일명이 있어야 합니다.");
          }
        });
    return NoteMetadata.distinctByKey(
        refs,
        ref -> ref.isExisting() ? "id:" + ref.fileId() : "key:" + ref.key(),
        policy.maxFileCount());
  }

  /** <b>수정은 조회가 아니다</b> (#245) — 조회수를 올리지 않고 지금 값을 그대로 싣는다. */
  private NoteDetailResponse detailOf(Note note, Long viewerId) {
    Long uploaderId = note.getUploaderId();
    User uploader = uploaderId == null ? null : users.findById(uploaderId).orElse(null);
    /*
     * 개수와 내 상태를 한 문장에서 읽는다 (#367 리뷰). 따로 물으면 READ COMMITTED에서
     * 스냅샷이 갈려 0개인데 내가 눌렀다고 답하는 수정 응답이 나갈 수 있다.
     */
    NoteLikeSummary like =
        NoteLikeSummary.byNoteId(likes.countWithMineByNoteIds(viewerId, List.of(note.getId())))
            .getOrDefault(note.getId(), NoteLikeSummary.NONE);
    return NoteDetailResponse.of(
        note,
        Uploader.of(uploader),
        bookmarks.existsByUserIdAndNoteId(viewerId, note.getId()),
        note.getViewCount(),
        like.count(),
        like.likedByMe());
  }
}
