package org.hackerkhu.hackerhp.domain.note.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.hackerkhu.hackerhp.domain.note.dto.NoteCreateRequest;
import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.dto.Uploader;
import org.hackerkhu.hackerhp.domain.note.entity.Category;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.entity.NoteFile;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자료 등록 — 흐름의 ③ (spec 2-1 §2-1-2 MUST, 3-2 §3-2-4).
 *
 * <p><b>여기가 진짜 방어선이다.</b> 발급 단계의 검사는 브라우저가 말한 값을 본 것이고, presigned PUT은 용량을 강제하지 못한다 — <b>실제로 올라온
 * 오브젝트를 직접 재서</b> 20MB를 넘으면 지우고 거절한다.
 *
 * <p>순서가 규칙이다.
 *
 * <table>
 *   <caption>등록 순서와 이유</caption>
 *   <tr><th>①<td>키가 <b>내 것인지</b> 확인<td>키 문자열만 알면 남의 파일을 자기 자료로 등록할 수 있다
 *   <tr><th>②<td><b>전부</b> 크기 확인<td>하나라도 걸리면 아무것도 옮기지 않는다 — 되돌릴 것이 없다
 *   <tr><th>③<td>최종 자리로 복사<td>임시 자리는 하루 뒤 자동으로 걷힌다
 *   <tr><th>④<td>DB 저장<td>실패하면 방금 복사한 것을 도로 지운다
 *   <tr><th>⑤<td>임시본 삭제<td>실패해도 라이프사이클이 걷어간다. 등록을 되돌릴 일이 아니다
 * </table>
 */
@Service
public class NoteCreateService {

  private static final Logger log = LoggerFactory.getLogger(NoteCreateService.class);

  private final NoteRepository noteRepository;
  private final UserRepository userRepository;
  private final FileStorage storage;
  private final NoteUploadPolicy policy;
  private final TransactionTemplate transaction;

  public NoteCreateService(
      NoteRepository noteRepository,
      UserRepository userRepository,
      FileStorage storage,
      NoteUploadPolicy policy,
      PlatformTransactionManager transactionManager) {
    this.noteRepository = noteRepository;
    this.userRepository = userRepository;
    this.storage = storage;
    this.policy = policy;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public NoteDetailResponse create(Long uploaderId, NoteCreateRequest request) {
    requireCategoryMatchesExamType(request);
    List<NoteCreateRequest.UploadedFile> files = distinctFiles(request.files());

    // ① 내가 발급받은 키인가 (#53 D3).
    files.forEach(file -> requireStagedByMe(file.key(), uploaderId));

    // ② 실제 크기를 전부 확인한 뒤에야 다음으로 간다.
    List<Stored> measured = files.stream().map(this::measure).toList();

    // ③ 최종 자리로 옮긴다.
    List<String> copied = new ArrayList<>();
    try {
      measured.forEach(
          stored -> {
            storage.copy(stored.stagingKey(), stored.storedKey());
            copied.add(stored.storedKey());
          });
      // ④ 여기서 실패하면 아래 catch가 방금 복사한 것을 도로 지운다.
      Note saved = transaction.execute(ignored -> persist(uploaderId, request, measured));
      // ⑤ 임시본은 이제 필요 없다.
      measured.forEach(stored -> storage.delete(stored.stagingKey()));
      return detailOf(saved, uploaderId);
    } catch (RuntimeException e) {
      /*
       * 등록이 무산됐는데 최종 자리에 파일만 남으면 아무도 그것을 지울 수 없다 — 그 자리에는
       * 만료 규칙이 없고(자료는 오래 남아야 한다), DB에 행이 없으니 찾을 실마리도 없다.
       */
      copied.forEach(storage::delete);
      throw e;
    }
  }

  /** 임시 키와 최종 키, 그리고 <b>실제로 올라온</b> 크기. */
  private record Stored(String stagingKey, String storedKey, String originalName, long sizeBytes) {}

  /**
   * <b>같은 키를 두 번 담아도 한 번만 등록한다.</b>
   *
   * <p>두 번 담기면 ⑤에서 첫 번째가 임시본을 지운 뒤 두 번째가 같은 것을 복사하려다 터진다. 재시도·중복 클릭으로 흔히 생기는 모양이라 조용히 접는다.
   */
  private List<NoteCreateRequest.UploadedFile> distinctFiles(
      List<NoteCreateRequest.UploadedFile> files) {
    Set<String> keys = new LinkedHashSet<>();
    List<NoteCreateRequest.UploadedFile> distinct =
        files.stream().filter(file -> keys.add(file.key())).toList();
    if (distinct.size() > policy.maxFileCount()) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "파일은 " + policy.maxFileCount() + "개까지 붙일 수 있습니다.");
    }
    return distinct;
  }

  /**
   * {@code category=EXAM}이면 {@code examType}이 있어야 하고, {@code SUBJECT}면 없어야 한다 (§3-2-2 CHECK).
   *
   * <p>DB가 최종적으로 막지만 <b>제약 위반은 {@code 500}으로 샌다.</b> 화면이 고칠 수 있는 실수라 여기서 {@code 400}으로 답한다.
   */
  private void requireCategoryMatchesExamType(NoteCreateRequest request) {
    boolean exam = request.category() == Category.EXAM;
    if (exam && request.examType() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "시험 자료는 중간·기말을 선택해야 합니다.");
    }
    if (!exam && request.examType() != null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "과목 자료에는 시험 구분을 넣을 수 없습니다.");
    }
  }

  private void requireStagedByMe(String key, Long uploaderId) {
    if (!NoteObjectKey.stagedBy(key, uploaderId)) {
      log.warn("남의 업로드 키로 등록을 시도했다: uploaderId={} key={}", uploaderId, key);
      throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 올린 파일만 등록할 수 있습니다.");
    }
  }

  /**
   * <b>올라오지 않은 키는 {@code 400}이다.</b>
   *
   * <p>흔한 클라이언트 실수(업로드가 끝나기 전에 등록을 부름)이지 서버 장애가 아니다. 발급만 받고 올리지 않은 키도 같은 자리에 온다.
   */
  private Stored measure(NoteCreateRequest.UploadedFile file) {
    OptionalLong size = storage.sizeOf(file.key());
    if (size.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "업로드가 끝나지 않은 파일이 있습니다.");
    }
    if (policy.tooLarge(size.getAsLong())) {
      /*
       * 넘긴 파일을 그 자리에 두지 않는다 (2-1 §2-1-2 MUST). 임시 자리라 하루 뒤에는 어차피
       * 사라지지만, 20MB 넘는 것을 하루씩 쌓아 둘 이유가 없다.
       */
      storage.delete(file.key());
      throw new BusinessException(
          ErrorCode.FILE_TOO_LARGE,
          "파일 하나는 " + policy.maxFileSize().toMegabytes() + "MB까지 올릴 수 있습니다.");
    }
    return new Stored(
        file.key(), NoteObjectKey.stored(file.key()), file.originalName(), size.getAsLong());
  }

  private Note persist(Long uploaderId, NoteCreateRequest request, List<Stored> files) {
    Instant now = Instant.now();
    Note note =
        Note.upload(
            request.category(),
            request.title().trim(),
            request.subjectName().trim(),
            blankToNull(request.professor()),
            request.year(),
            request.semester(),
            request.examType(),
            uploaderId,
            now);
    files.forEach(
        stored ->
            note.attach(
                NoteFile.stored(
                    note, stored.originalName(), stored.storedKey(), stored.sizeBytes())));
    return noteRepository.save(note);
  }

  /** 빈 문자열은 {@code null}로 눕힌다 — 필터 옵션이 빈 항목을 만들지 않게 한다 (§3-2-4). */
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }

  /** 방금 등록한 사람이 곧 업로더다. 즐겨찾기는 아직 없다. */
  private NoteDetailResponse detailOf(Note note, Long uploaderId) {
    String name = userRepository.findById(uploaderId).map(user -> user.getName()).orElse(null);
    return NoteDetailResponse.of(note, Uploader.of(uploaderId, name), false);
  }
}
