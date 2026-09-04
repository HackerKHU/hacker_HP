package org.hackerkhu.hackerhp.domain.note.service;

import java.time.Instant;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.dto.NoteCreateRequest;
import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.dto.Uploader;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.entity.NoteFile;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자료 등록 — 흐름의 ③ (spec 2-1 §2-1-2 MUST, 3-2 §3-2-4).
 *
 * <p><b>여기가 진짜 방어선이다.</b> 발급 단계의 검사는 브라우저가 말한 값을 본 것이고, presigned PUT은 용량을 강제하지 못한다 — 붙일 것을 확인하는
 * 규칙은 {@link StagedUploads}에 모여 있다.
 *
 * <p>순서가 규칙이다.
 *
 * <table>
 *   <caption>등록 순서와 이유</caption>
 *   <tr><th>①<td>붙일 파일을 최종 자리로 옮긴다<td>{@link StagedUploads}가 검사까지 함께 한다
 *   <tr><th>②<td>업로더를 잠그고 {@code ACTIVE} 재확인 → 저장<td>인가를 지난 뒤 정지될 수 있다
 *   <tr><th>③<td>임시본 삭제<td>실패해도 라이프사이클이 걷어간다
 * </table>
 *
 * <p><b>커밋 전에 실패한 경우에만 최종본을 지운다</b> (#207 리뷰). 커밋 뒤에 무엇이 잘못돼도 파일은 그대로 둔다 — 이미 저장된 자료가 파일 없는 껍데기가 되면
 * 되돌릴 방법이 없다.
 */
@Service
public class NoteCreateService {

  private final NoteRepository noteRepository;
  private final UserRepository userRepository;
  private final StagedUploads staged;
  private final NoteUploadPolicy policy;
  private final TransactionTemplate transaction;

  public NoteCreateService(
      NoteRepository noteRepository,
      UserRepository userRepository,
      StagedUploads staged,
      NoteUploadPolicy policy,
      PlatformTransactionManager transactionManager) {
    this.noteRepository = noteRepository;
    this.userRepository = userRepository;
    this.staged = staged;
    this.policy = policy;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public NoteDetailResponse create(Long uploaderId, NoteCreateRequest request) {
    NoteMetadata.requireCategoryMatchesExamType(request.category(), request.examType());
    List<NoteCreateRequest.UploadedFile> files =
        NoteMetadata.distinctByKey(
            request.files(), NoteCreateRequest.UploadedFile::key, policy.maxFileCount());

    // ① 검사와 옮기기는 한 곳에 있다.
    List<StagedUploads.Stored> stored =
        staged.claim(
            uploaderId,
            files.stream()
                .map(file -> new StagedUploads.Claim(file.key(), file.originalName()))
                .toList());

    Note saved;
    try {
      // ② 여기서 실패하면 방금 옮긴 것을 도로 지운다.
      saved = transaction.execute(ignored -> persist(uploaderId, request, stored));
    } catch (RuntimeException e) {
      staged.discardStored(stored.stream().map(StagedUploads.Stored::storedKey).toList(), e);
      throw e;
    }

    // ③ 여기부터는 커밋된 뒤다. 무엇이 실패해도 파일을 지우지 않는다.
    staged.discardStaging(stored.stream().map(StagedUploads.Stored::stagingKey).toList());
    return detailOf(saved, uploaderId);
  }

  /**
   * <b>업로더를 잠그고 다시 확인한다</b> (3-1 §3-1-4 MUST, #207 리뷰).
   *
   * <p>인가는 세션 값으로 이루어지고 필터는 매 요청 {@code users}를 읽지 않는다. 그래서 관리자가 방금 정지시킨 사람의 <b>대기 중이던 등록이 그대로
   * 커밋될</b> 수 있다.
   */
  private Note persist(
      Long uploaderId, NoteCreateRequest request, List<StagedUploads.Stored> files) {
    User uploader = userRepository.findByIdForUpdate(uploaderId).orElse(null);
    RequesterCheck.requireNoteAccess(uploader, uploaderId);

    Instant now = Instant.now();
    Note note =
        Note.upload(
            request.category(),
            request.title().trim(),
            request.subjectName().trim(),
            NoteMetadata.blankToNull(request.professor()),
            request.year(),
            request.semester(),
            request.examType(),
            uploaderId,
            now);
    files.forEach(
        file ->
            note.attach(
                NoteFile.stored(note, file.originalName(), file.storedKey(), file.sizeBytes())));
    return noteRepository.save(note);
  }

  /**
   * 방금 등록한 사람이 곧 업로더다. 즐겨찾기·좋아요는 아직 없다.
   *
   * <p><b>등록은 조회가 아니다</b> (#245) — 조회수를 올리지 않는다. 갓 만든 자료라 {@code 0}이다.
   */
  private NoteDetailResponse detailOf(Note note, Long uploaderId) {
    User uploader = userRepository.findById(uploaderId).orElse(null);
    return NoteDetailResponse.of(
        note, Uploader.of(uploader), false, note.getViewCount(), 0L, false);
  }
}
