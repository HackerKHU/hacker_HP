package org.hackerkhu.hackerhp.domain.note.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * spec/3-2-DESIGN-CONTRACT.md §3-2-2 {@code note_files}.
 *
 * <p><b>{@code storedPath}는 밖으로 나가지 않는다.</b> 버킷이 비공개라 키를 알아도 열 수 없고(2-1 §2-1-4), 키 구조를 드러낼 이유도 없다.
 * 파일을 받는 경로는 presigned URL을 발급하는 {@code GET /notes/{id}/files/{fileId}}뿐이다 (#55).
 */
@Entity
@Table(name = "note_files")
public class NoteFile {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "note_id", nullable = false)
  private Note note;

  /** 업로드 당시 파일명. 화면이 보여주는 것은 이것이다. */
  @Column(name = "original_name", nullable = false)
  private String originalName;

  /** S3 오브젝트 키. <b>응답에 담지 않는다.</b> */
  @Column(name = "stored_path", nullable = false)
  private String storedPath;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  protected NoteFile() {}

  /**
   * 업로드를 마친 파일을 자료에 붙인다 (#53).
   *
   * <p>{@code sizeBytes}는 <b>브라우저가 말한 값이 아니라 S3에 실제로 올라온 크기다</b> (2-1 §2-1-2 MUST) — presigned PUT은
   * 용량을 강제하지 못하므로, 등록 단계에서 직접 재서 넣는다.
   */
  public static NoteFile stored(Note note, String originalName, String storedPath, long sizeBytes) {
    NoteFile file = new NoteFile();
    file.note = note;
    file.originalName = originalName;
    file.storedPath = storedPath;
    file.sizeBytes = sizeBytes;
    return file;
  }

  public Long getId() {
    return id;
  }

  public Note getNote() {
    return note;
  }

  public String getOriginalName() {
    return originalName;
  }

  public String getStoredPath() {
    return storedPath;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }
}
