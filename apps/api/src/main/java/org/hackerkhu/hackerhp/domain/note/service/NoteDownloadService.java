package org.hackerkhu.hackerhp.domain.note.service;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.note.dto.DownloadUrlResponse;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.hackerkhu.hackerhp.domain.note.entity.NoteFile;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.FileStorage;
import org.hackerkhu.hackerhp.global.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내려받기 URL 발급 (spec 2-1 §2-1-4 MUST, 3-2 §3-2-4).
 *
 * <p><b>영구적인 공개 URL은 존재하지 않는다</b> (MUST). 버킷은 완전 비공개이고, 파일에 닿는 길은 여기서 발급하는 짧은 수명의 서명된 주소뿐이다.
 *
 * <p><b>서버는 파일 바이트를 만지지 않는다.</b> 주소만 주고, 받는 것은 브라우저와 S3 사이의 일이다.
 */
@Service
public class NoteDownloadService {

  private static final Logger log = LoggerFactory.getLogger(NoteDownloadService.class);

  private final NoteRepository notes;
  private final FileStorage storage;
  private final StorageProperties properties;

  public NoteDownloadService(
      NoteRepository notes, FileStorage storage, StorageProperties properties) {
    this.notes = notes;
    this.storage = storage;
    this.properties = properties;
  }

  /**
   * <b>그 파일이 그 자료의 것인지 함께 본다.</b>
   *
   * <p>{@code fileId}만으로 찾으면 <b>경로가 거짓말을 해도 통한다</b> — 아무 자료 번호에 남의 파일 번호를 끼워 넣어도 URL이 나온다. 지금은 모든
   * {@code ACTIVE}가 모든 자료를 받을 수 있어 손해가 없지만, 수정·삭제(#54)가 소유자를 따지기 시작하면 <b>이 경로만 기준이 다른 채로 남는다.</b>
   */
  @Transactional(readOnly = true)
  public DownloadUrlResponse issue(Long viewerId, Long noteId, Long fileId) {
    Note note =
        notes
            .findWithFilesById(noteId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다."));

    NoteFile file =
        note.getFiles().stream()
            .filter(candidate -> candidate.getId().equals(fileId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "파일을 찾을 수 없습니다."));

    /*
     * 누가 무엇을 받았는지는 로그로만 남긴다 (#55 D5). 테이블을 만들면 보존 기간·열람 권한·
     * 개인정보처리방침 고지가 줄줄이 따라붙는데, 승인된 부원끼리 자료를 나누는 사이트라
     * 그만한 요구가 없다.
     */
    log.info("자료 내려받기 URL 발급: viewerId={} noteId={} fileId={}", viewerId, noteId, fileId);

    return new DownloadUrlResponse(
        storage.presignGet(file.getStoredPath(), file.getOriginalName()),
        file.getOriginalName(),
        Instant.now().plus(properties.downloadPresignTtl()));
  }
}
