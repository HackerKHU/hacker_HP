package org.hackerkhu.hackerhp.domain.note.service;

import java.time.Instant;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.dto.UploadUrlRequest;
import org.hackerkhu.hackerhp.domain.note.dto.UploadUrlResponse;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.FileStorage;
import org.hackerkhu.hackerhp.global.storage.StorageProperties;
import org.springframework.stereotype.Service;

/**
 * presigned URL 발급 — 흐름의 ① (spec 2-1 §2-1-2 MUST).
 *
 * <p><b>여기서 거절하는 것은 "올리기 전에" 알려 주기 위해서다.</b> 20MB짜리를 다 올리고 나서 등록 단계에서 거절당하면 사용자는 시간을 통째로 버린다.
 *
 * <p><b>그렇다고 여기가 방어선인 것은 아니다.</b> 크기는 브라우저가 말하는 값이고 presigned PUT은 용량을 강제하지 못한다 — 실제 강제는 등록 단계가 S3에
 * 올라온 오브젝트를 직접 재서 한다 ({@link NoteCreateService}).
 */
@Service
public class NoteUploadUrlService {

  private final FileStorage storage;
  private final StorageProperties storageProperties;
  private final NoteUploadPolicy policy;

  public NoteUploadUrlService(
      FileStorage storage, StorageProperties storageProperties, NoteUploadPolicy policy) {
    this.storage = storage;
    this.storageProperties = storageProperties;
    this.policy = policy;
  }

  public UploadUrlResponse issue(Long uploaderId, UploadUrlRequest request) {
    List<UploadUrlRequest.File> files = request.files();
    if (files.size() > policy.maxFileCount()) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "파일은 한 번에 " + policy.maxFileCount() + "개까지 올릴 수 있습니다.");
    }

    // 하나라도 걸리면 아무것도 발급하지 않는다. 반쯤 발급해 두면 사용자는 무엇이 통과했는지 모른다.
    files.forEach(this::requireAcceptable);

    Instant expiresAt = Instant.now().plus(storageProperties.presignTtl());
    return new UploadUrlResponse(
        files.stream().map(file -> presign(uploaderId, file, expiresAt)).toList());
  }

  /**
   * <b>확장자를 먼저 본다.</b>
   *
   * <p>둘 다 어긋난 파일(예: 30MB짜리 {@code .exe})에 하나만 답할 수 있는데, "이 종류는 아예 받지 않는다"가 "조금 줄여서 다시"보다 먼저 알아야 할
   * 사실이다 — 크기를 먼저 말하면 사용자는 압축해서 다시 시도한 뒤에야 종류가 문제였음을 안다.
   */
  private void requireAcceptable(UploadUrlRequest.File file) {
    String extension = NoteObjectKey.extensionOf(file.originalName());
    if (!policy.allows(extension)) {
      throw new BusinessException(
          ErrorCode.UNSUPPORTED_FILE_TYPE,
          "허용되지 않는 형식입니다. " + String.join(", ", policy.allowedExtensions()) + "만 올릴 수 있습니다.");
    }
    if (policy.tooLarge(file.sizeBytes())) {
      throw new BusinessException(
          ErrorCode.FILE_TOO_LARGE,
          "파일 하나는 " + policy.maxFileSize().toMegabytes() + "MB까지 올릴 수 있습니다.");
    }
  }

  private UploadUrlResponse.Upload presign(
      Long uploaderId, UploadUrlRequest.File file, Instant expiresAt) {
    String key = NoteObjectKey.staging(uploaderId, NoteObjectKey.extensionOf(file.originalName()));
    return new UploadUrlResponse.Upload(
        file.originalName(), key, storage.presignPut(key), expiresAt);
  }
}
