package org.hackerkhu.hackerhp.domain.note.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hackerkhu.hackerhp.domain.note.dto.NoteCreateRequest;
import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.dto.Uploader;
import org.hackerkhu.hackerhp.domain.note.entity.Category;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.entity.NoteFile;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.FileStorage;
import org.hackerkhu.hackerhp.global.storage.FileStorage.StoredObject;
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
 *   <tr><th>①<td>키가 <b>내 것인지</b>, 이름이 허용 확장자인지<td>키도 이름도 클라이언트가 보내는 값이다
 *   <tr><th>②<td><b>전부</b> 재고 {@code etag}를 붙든다<td>하나라도 걸리면 아무것도 옮기지 않는다
 *   <tr><th>③<td><b>잰 그 내용일 때만</b> 최종 자리로 복사<td>잰 뒤에 갈아치우면 제한이 무력해진다
 *   <tr><th>④<td>업로더를 잠그고 {@code ACTIVE} 재확인 → 저장<td>인가를 지난 뒤 정지될 수 있다
 *   <tr><th>⑤<td>임시본 삭제<td>실패해도 라이프사이클이 걷어간다
 * </table>
 *
 * <p><b>커밋 전에 실패한 경우에만 최종본을 지운다</b> (#207 리뷰). 커밋 뒤에 무엇이 잘못돼도 파일은 그대로 둔다 — 이미 저장된 자료가 파일 없는 껍데기가 되면
 * 되돌릴 방법이 없다.
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

    // ① 키도 이름도 클라이언트가 보내는 값이다. 둘 다 본다 (#53 D3, #207 리뷰).
    files.forEach(
        file -> {
          requireStagedByMe(file.key(), uploaderId);
          requireAllowedName(file.originalName());
        });

    // ② 전부 재고 etag를 붙든 뒤에야 다음으로 간다.
    List<Measured> measured = files.stream().map(this::measure).toList();

    Note saved = copyAndPersist(uploaderId, request, measured);

    /*
     * ⑤ 여기부터는 커밋된 뒤다. 무엇이 실패해도 파일을 지우지 않는다.
     *
     * 임시본 정리는 실패해도 라이프사이클이 하루 뒤에 걷어가므로, 등록을 무를 일이 아니다.
     */
    measured.forEach(m -> deleteQuietly(m.stagingKey()));
    return detailOf(saved, uploaderId);
  }

  /**
   * ③·④를 한 묶음으로 본다. <b>여기서 실패하면 옮겨 둔 것을 도로 지운다.</b>
   *
   * <p>파일은 저장하기 <b>전에</b> 최종 자리로 옮겨진다 — 그 뒤 저장이 실패하면 그 파일은 <b>아무도 지울 수 없는 것</b>이 된다. 최종 자리에는 만료 규칙이
   * 없고(자료는 오래 남아야 한다), DB에 행이 없어 찾을 실마리도 없다.
   *
   * <p><b>정리 범위가 여기서 끝나는 것이 중요하다.</b> 커밋 뒤의 실패까지 이 {@code catch}가 받으면, 이미 저장된 자료의 파일만 사라져 <b>고칠 수
   * 없는 껍데기</b>가 남는다.
   */
  private Note copyAndPersist(Long uploaderId, NoteCreateRequest request, List<Measured> measured) {
    List<Stored> copied = new ArrayList<>();
    try {
      measured.forEach(m -> copied.add(copyToStored(m)));
      return transaction.execute(ignored -> persist(uploaderId, request, copied));
    } catch (RuntimeException e) {
      cleanUp(copied, e);
      throw e;
    }
  }

  /**
   * 옮겨 둔 최종본을 되돌린다.
   *
   * <p><b>정리 실패를 삼키지 않는다</b> (#207 리뷰). 최종 자리에는 만료 규칙이 없어, 여기서 실패한 오브젝트는 <b>DB 행도 만료 규칙도 없이 영원히
   * 남는다.</b> 그렇다고 원래 실패를 이 실패로 덮으면 무엇이 잘못됐는지 알 수 없으므로, <b>키를 찍어 남기고</b> 원래 예외에 매달아 보낸다 — 사람이 그 키로
   * 찾아 지울 수 있어야 한다.
   */
  private void cleanUp(List<Stored> copied, RuntimeException cause) {
    copied.forEach(
        stored -> {
          try {
            storage.delete(stored.storedKey());
          } catch (RuntimeException e) {
            log.error("등록 실패 후 정리도 실패했다 — 손으로 지워야 한다: key={}", stored.storedKey(), e);
            cause.addSuppressed(e);
          }
        });
  }

  /** 임시본 정리. <b>여기는 삼켜도 된다</b> — 하루 뒤 라이프사이클이 걷어간다. */
  private void deleteQuietly(String key) {
    try {
      storage.delete(key);
    } catch (RuntimeException e) {
      log.warn("임시본 정리에 실패했다. 라이프사이클이 걷어간다: key={}", key, e);
    }
  }

  /** 잰 결과 — 임시 키와 <b>그 순간의 내용</b>. */
  private record Measured(String stagingKey, String originalName, StoredObject object) {}

  /** 옮긴 결과 — 최종 키와 DB에 적을 값. */
  private record Stored(String storedKey, String originalName, long sizeBytes) {}

  /**
   * <b>같은 키를 두 번 담아도 한 번만 등록한다.</b>
   *
   * <p>두 번 담기면 같은 임시본을 두 번 옮기게 되는데, 그러면 한 자료에 같은 내용의 파일이 둘로 들어간다. 재시도·중복 클릭으로 흔히 생기는 모양이라 조용히 접는다.
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
   * <b>등록 요청의 파일명도 확장자 검사를 받는다</b> (#207 리뷰).
   *
   * <p>발급 때 {@code safe.pdf}로 통과한 뒤 등록에서 같은 키에 {@code malware.exe}를 붙이면, <b>화면에 실행 파일 이름이 그대로
   * 뜬다.</b> 이 이름은 {@code note_files.original_name}에 저장되고 내려받을 때 사용자가 보는 이름이 된다 — 발급 API의 {@code
   * 415}를 우회하는 길이다.
   */
  private void requireAllowedName(String originalName) {
    if (!policy.allows(NoteObjectKey.extensionOf(originalName))) {
      throw new BusinessException(
          ErrorCode.UNSUPPORTED_FILE_TYPE,
          "허용되지 않는 형식입니다. " + String.join(", ", policy.allowedExtensions()) + "만 올릴 수 있습니다.");
    }
  }

  /**
   * <b>올라오지 않은 키는 {@code 400}이다.</b>
   *
   * <p>흔한 클라이언트 실수(업로드가 끝나기 전에 등록을 부름)이지 서버 장애가 아니다. 발급만 받고 올리지 않은 키, 하루가 지나 걷힌 키도 같은 자리에 온다.
   */
  private Measured measure(NoteCreateRequest.UploadedFile file) {
    Optional<StoredObject> found = storage.describe(file.key());
    if (found.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "업로드가 끝나지 않은 파일이 있습니다.");
    }
    StoredObject object = found.get();
    if (policy.tooLarge(object.sizeBytes())) {
      /*
       * 넘긴 파일을 그 자리에 두지 않는다 (2-1 §2-1-2 MUST). 임시 자리라 하루 뒤에는 어차피
       * 사라지지만, 20MB 넘는 것을 하루씩 쌓아 둘 이유가 없다.
       */
      deleteQuietly(file.key());
      throw new BusinessException(
          ErrorCode.FILE_TOO_LARGE,
          "파일 하나는 " + policy.maxFileSize().toMegabytes() + "MB까지 올릴 수 있습니다.");
    }
    return new Measured(file.key(), file.originalName(), object);
  }

  /**
   * <b>잰 그 내용일 때만 옮긴다</b> (#207 리뷰).
   *
   * <p>발급한 presigned URL은 만료(5분)까지 살아 있다 — 작은 파일을 재게 하고 곧바로 큰 파일로 갈아치우면, 옮겨지는 것은 큰 파일인데 DB에는 <b>작은
   * 크기가 적혀 용량 제한이 통째로 무력해진다.</b>
   *
   * <p>최종 키는 <b>등록할 때마다 새로 뽑는다.</b> 임시 키에서 물려받으면 같은 임시 키의 두 번째 등록이 첫 자료의 파일을 덮어쓴다.
   */
  private Stored copyToStored(Measured measured) {
    String storedKey = NoteObjectKey.stored(NoteObjectKey.extensionOf(measured.stagingKey()));
    if (!storage.copyIfUnchanged(measured.stagingKey(), storedKey, measured.object().etag())) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "업로드가 도중에 바뀌었습니다. 다시 올려 주세요.");
    }
    return new Stored(storedKey, measured.originalName(), measured.object().sizeBytes());
  }

  /**
   * <b>업로더를 잠그고 다시 확인한다</b> (3-1 §3-1-4 MUST, #207 리뷰).
   *
   * <p>인가는 세션 값으로 이루어지고 필터는 매 요청 {@code users}를 읽지 않는다. 그래서 관리자가 방금 정지시킨 사람의 <b>대기 중이던 등록이 그대로
   * 커밋될</b> 수 있다 — 정지 반영이 끝나기 전에 시작된 요청이면 더 그렇다. 잠근 채 확인하면 정지 트랜잭션과 자연히 줄이 선다.
   */
  private Note persist(Long uploaderId, NoteCreateRequest request, List<Stored> files) {
    User uploader = userRepository.findByIdForUpdate(uploaderId).orElse(null);
    RequesterCheck.requireActive(uploader, uploaderId);

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
    String name = userRepository.findById(uploaderId).map(User::getName).orElse(null);
    return NoteDetailResponse.of(note, Uploader.of(uploaderId, name), false);
  }
}
