package org.hackerkhu.hackerhp.domain.note.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.hackerkhu.hackerhp.domain.note.entity.Category;
import org.hackerkhu.hackerhp.domain.note.entity.ExamType;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;

/** 등록과 수정이 함께 지키는 메타데이터 규칙 (#53·#54). 두 곳에 적으면 한쪽만 고쳐진다. */
final class NoteMetadata {

  private NoteMetadata() {}

  /**
   * {@code category=EXAM}이면 {@code examType}이 있어야 하고, {@code SUBJECT}면 없어야 한다 (§3-2-2 CHECK).
   *
   * <p>DB가 최종적으로 막지만 <b>제약 위반은 {@code 500}으로 샌다.</b> 화면이 고칠 수 있는 실수라 여기서 {@code 400}으로 답한다.
   */
  static void requireCategoryMatchesExamType(Category category, ExamType examType) {
    boolean exam = category == Category.EXAM;
    if (exam && examType == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "시험 자료는 중간·기말을 선택해야 합니다.");
    }
    if (!exam && examType != null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "과목 자료에는 시험 구분을 넣을 수 없습니다.");
    }
  }

  /**
   * <b>같은 것을 두 번 담아도 한 번만 센다.</b>
   *
   * <p>재시도·중복 클릭으로 흔히 생기는 모양이라 조용히 접는다. 개수 상한은 <b>접은 뒤에</b> 본다 — 같은 파일을 열한 번 담았다고 거절할 이유가 없다.
   */
  static <T> List<T> distinctByKey(List<T> items, Function<T, Object> key, int max) {
    Set<Object> seen = new LinkedHashSet<>();
    List<T> distinct = items.stream().filter(item -> seen.add(key.apply(item))).toList();
    if (distinct.size() > max) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "파일은 " + max + "개까지 붙일 수 있습니다.");
    }
    return distinct;
  }

  /** 빈 문자열은 {@code null}로 눕힌다 — 필터 옵션이 빈 항목을 만들지 않게 한다 (§3-2-4). */
  static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
