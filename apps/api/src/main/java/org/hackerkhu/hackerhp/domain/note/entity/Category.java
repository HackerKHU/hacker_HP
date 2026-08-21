package org.hackerkhu.hackerhp.domain.note.entity;

/** 자료의 갈래 (spec 2-1 §2-1-1). 이름이 DB의 {@code CHECK} 제약과 같아야 한다. */
public enum Category {

  /** 시험 자료. {@link ExamType}이 반드시 있다. */
  EXAM,

  /** 과목 정리본. {@link ExamType}이 없다. */
  SUBJECT
}
