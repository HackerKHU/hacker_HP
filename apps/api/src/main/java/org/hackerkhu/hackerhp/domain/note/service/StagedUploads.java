package org.hackerkhu.hackerhp.domain.note.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.FileStorage;
import org.hackerkhu.hackerhp.global.storage.FileStorage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 올라온 임시 파일을 <b>자료에 붙일 수 있는 것으로 만든다</b> (spec 2-1 §2-1-2 MUST).
 *
 * <p>등록(#53)과 수정(#54)이 같은 일을 한다 — <b>규칙을 한 곳에 둔다.</b> 여기 모인 검사들은 하나하나가 리뷰에서 뚫린 자리라(#207), 두 경로에 따로
 * 적으면 한쪽만 고쳐진다.
 *
 * <table>
 *   <caption>붙이기 전에 보는 것</caption>
 *   <tr><th>①<td>이름의 확장자<td>발급 때 통과한 이름과 <b>다른 이름</b>을 붙이는 길을 막는다
 *   <tr><th>②<td>키가 내 것인지<td>키 문자열만 알면 남의 파일을 자기 자료로 삼을 수 있다
 *   <tr><th>③<td>실제로 올라온 크기<td>presigned PUT은 용량을 강제하지 못한다
 *   <tr><th>④<td>잰 그 내용인지<td>재고 옮기는 사이에 갈아치우면 제한이 무력해진다
 * </table>
 */
@Component
public class StagedUploads {

  private static final Logger log = LoggerFactory.getLogger(StagedUploads.class);

  private final FileStorage storage;
  private final NoteUploadPolicy policy;

  public StagedUploads(FileStorage storage, NoteUploadPolicy policy) {
    this.storage = storage;
    this.policy = policy;
  }

  /** 붙여 달라는 요청 하나. */
  public record Claim(String key, String originalName) {}

  /** 최종 자리로 옮겨진 결과. DB에 적을 값이다. */
  public record Stored(String stagingKey, String storedKey, String originalName, long sizeBytes) {}

  /**
   * 전부 재고 나서 옮긴다.
   *
   * <p><b>하나라도 걸리면 아무것도 남기지 않는다.</b> 재는 것을 먼저 끝내므로 대개 옮기기 전에 걸리고, 옮기는 중에 걸리면 그때까지 옮긴 것을 도로 지운다.
   *
   * @return 옮겨진 것들. <b>부르는 쪽은 저장에 실패하면 {@link #discardStored}로 되돌려야 한다</b>
   */
  public List<Stored> claim(Long uploaderId, List<Claim> claims) {
    claims.forEach(
        claim -> {
          requireAllowedName(claim.originalName());
          requireStagedBy(claim.key(), uploaderId);
        });

    List<Measured> measured = claims.stream().map(this::measure).toList();

    List<Stored> copied = new ArrayList<>();
    try {
      measured.forEach(m -> copied.add(copyToStored(m)));
      return copied;
    } catch (RuntimeException e) {
      discardStored(copied.stream().map(Stored::storedKey).toList(), e);
      throw e;
    }
  }

  /**
   * 옮겨 둔 최종본을 되돌린다.
   *
   * <p><b>정리 실패를 삼키지 않는다</b> (#207 리뷰). 최종 자리에는 만료 규칙이 없어, 여기서 실패한 오브젝트는 <b>DB 행도 만료 규칙도 없이 영원히
   * 남는다.</b> 그렇다고 원래 실패를 이 실패로 덮으면 무엇이 잘못됐는지 알 수 없으므로, <b>키를 찍어 남기고</b> 원래 예외에 매달아 보낸다.
   */
  public void discardStored(List<String> storedKeys, RuntimeException cause) {
    storedKeys.forEach(
        key -> {
          try {
            storage.delete(key);
          } catch (RuntimeException e) {
            log.error("정리에 실패했다 — 손으로 지워야 한다: key={}", key, e);
            cause.addSuppressed(e);
          }
        });
  }

  /**
   * 임시본을 치운다. <b>여기는 삼켜도 된다</b> — 하루 뒤 라이프사이클이 걷어간다.
   *
   * <p>이미 커밋된 뒤에 부르므로 실패해도 되돌릴 일이 아니다.
   */
  public void discardStaging(List<String> stagingKeys) {
    stagingKeys.forEach(
        key -> {
          try {
            storage.delete(key);
          } catch (RuntimeException e) {
            log.warn("임시본 정리에 실패했다. 라이프사이클이 걷어간다: key={}", key, e);
          }
        });
  }

  /**
   * <b>이미 자료에 붙어 있던 파일</b>을 치운다 (2-1 §2-1-3 SHOULD, #54).
   *
   * <p>DB는 이미 커밋됐다 — 사용자에게 삭제는 <b>끝난 일</b>이다. 여기서 실패했다고 {@code 500}을 주면 재요청해도 자료가 없어 영원히 실패하고, 스펙이
   * 이것을 SHOULD로 둔 이유도 그것이다("남아도 접근 경로는 없지만 저장 비용이 쌓인다"). <b>키를 찍어 남겨</b> 사람이 찾아 지울 수 있게 한다.
   */
  public void discardDetached(List<String> storedKeys) {
    storedKeys.forEach(
        key -> {
          try {
            storage.delete(key);
          } catch (RuntimeException e) {
            log.error("떨어져 나온 파일을 지우지 못했다 — 손으로 지워야 한다: key={}", key, e);
          }
        });
  }

  private record Measured(String stagingKey, String originalName, StoredObject object) {}

  /**
   * <b>붙일 때의 이름도 확장자 검사를 받는다</b> (#207 리뷰).
   *
   * <p>발급 때 {@code safe.pdf}로 통과한 뒤 {@code malware.exe}를 붙이면 그 이름이 저장되고 <b>내려받을 때 사용자가 보는 이름</b>이
   * 된다.
   */
  private void requireAllowedName(String originalName) {
    if (!policy.allows(NoteObjectKey.extensionOf(originalName))) {
      throw new BusinessException(
          ErrorCode.UNSUPPORTED_FILE_TYPE,
          "허용되지 않는 형식입니다. " + String.join(", ", policy.allowedExtensions()) + "만 올릴 수 있습니다.");
    }
  }

  private void requireStagedBy(String key, Long uploaderId) {
    if (!NoteObjectKey.stagedBy(key, uploaderId)) {
      log.warn("남의 업로드 키를 붙이려 했다: uploaderId={} key={}", uploaderId, key);
      throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 올린 파일만 붙일 수 있습니다.");
    }
  }

  /** 올라오지 않은 키는 {@code 400}이다 — 흔한 클라이언트 실수이지 서버 장애가 아니다. */
  private Measured measure(Claim claim) {
    Optional<StoredObject> found = storage.describe(claim.key());
    if (found.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "업로드가 끝나지 않은 파일이 있습니다.");
    }
    StoredObject object = found.get();
    if (policy.tooLarge(object.sizeBytes())) {
      // 넘긴 파일을 그 자리에 두지 않는다 (§2-1-2 MUST). 임시 자리라 어차피 하루 뒤 사라지지만.
      discardStaging(List.of(claim.key()));
      throw new BusinessException(
          ErrorCode.FILE_TOO_LARGE,
          "파일 하나는 " + policy.maxFileSize().toMegabytes() + "MB까지 올릴 수 있습니다.");
    }
    return new Measured(claim.key(), claim.originalName(), object);
  }

  /**
   * <b>잰 그 내용일 때만 옮긴다</b> (#207 리뷰).
   *
   * <p>최종 키는 <b>붙일 때마다 새로 뽑는다.</b> 임시 키에서 물려받으면 같은 임시 키의 두 번째 등록이 앞 자료의 파일을 덮어쓴다.
   */
  private Stored copyToStored(Measured measured) {
    String storedKey = NoteObjectKey.stored(NoteObjectKey.extensionOf(measured.stagingKey()));
    if (!storage.copyIfUnchanged(measured.stagingKey(), storedKey, measured.object().etag())) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "업로드가 도중에 바뀌었습니다. 다시 올려 주세요.");
    }
    return new Stored(
        measured.stagingKey(), storedKey, measured.originalName(), measured.object().sizeBytes());
  }
}
