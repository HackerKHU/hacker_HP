package org.hackerkhu.hackerhp.domain.note.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * spec/3-2-DESIGN-CONTRACT.md §3-2-2 {@code notes}.
 *
 * <p><b>업로더를 연관관계로 매핑하지 않는다.</b> {@code uploader_id}는 {@code ON DELETE SET NULL}이라(2-2 §2-2-4) 회원을
 * 지워도 자료는 남고 그 자리가 빈다. 엔티티로 물려 두면 조회마다 {@code users}를 함께 읽게 되는데, 목록이 필요한 것은 <b>이름 하나뿐이고 그마저 없을 수
 * 있다.</b> 이름은 조회 시점에 따로 붙인다.
 *
 * <p>등록은 #53이 얹었다. 수정·삭제는 #54다.
 */
@Entity
@Table(name = "notes")
public class Note {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Category category;

  @Column(nullable = false)
  private String title;

  @Column(name = "subject_name", nullable = false)
  private String subjectName;

  /** 없을 수 있다. 검색·필터가 이 널을 그냥 지나치면 그 행이 통째로 빠진다. */
  @Column private String professor;

  @Column(nullable = false)
  private int year;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Semester semester;

  /** {@link Category#EXAM}에만 있다. DB의 CHECK 제약이 이 짝을 강제한다. */
  @Enumerated(EnumType.STRING)
  @Column(name = "exam_type")
  private ExamType examType;

  /** <b>{@code null}이면 탈퇴한 회원이다</b> (2-2 §2-2-4). 이름이 아니라 이 id로 소유자를 판단한다. */
  @Column(name = "uploader_id")
  private Long uploaderId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * 딸린 파일. {@code note_files.note_id}가 {@code ON DELETE CASCADE}라 자료와 함께 사라진다.
   *
   * <p><b>{@code LAZY}다.</b> 목록은 파일 <b>개수</b>만 쓰고 내용은 상세에서만 쓴다 — {@code EAGER}로 두면 20건을 그릴 때마다 파일
   * 질의가 20번 따라온다.
   */
  @OneToMany(mappedBy = "note", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
  private List<NoteFile> files = new ArrayList<>();

  protected Note() {}

  /**
   * 자료를 등록한다 (#53).
   *
   * <p><b>파일은 뒤에 붙인다.</b> {@link NoteFile}이 자료를 가리키므로 자료가 먼저 있어야 한다.
   *
   * <p>{@code uploaderId}는 <b>인증 주체에서만 온다</b> — 요청 본문으로 받으면 다른 사람 이름으로 올릴 수 있다.
   */
  public static Note upload(
      Category category,
      String title,
      String subjectName,
      String professor,
      int year,
      Semester semester,
      ExamType examType,
      Long uploaderId,
      Instant now) {
    Note note = new Note();
    note.category = category;
    note.title = title;
    note.subjectName = subjectName;
    note.professor = professor;
    note.year = year;
    note.semester = semester;
    note.examType = examType;
    note.uploaderId = uploaderId;
    note.createdAt = now;
    note.updatedAt = now;
    return note;
  }

  /** 딸린 파일을 붙인다. 자료를 저장할 때 함께 저장된다 ({@code cascade = PERSIST}). */
  public void attach(NoteFile file) {
    files.add(file);
  }

  public Long getId() {
    return id;
  }

  public Category getCategory() {
    return category;
  }

  public String getTitle() {
    return title;
  }

  public String getSubjectName() {
    return subjectName;
  }

  public String getProfessor() {
    return professor;
  }

  public int getYear() {
    return year;
  }

  public Semester getSemester() {
    return semester;
  }

  public ExamType getExamType() {
    return examType;
  }

  public Long getUploaderId() {
    return uploaderId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public List<NoteFile> getFiles() {
    return Collections.unmodifiableList(files);
  }
}
