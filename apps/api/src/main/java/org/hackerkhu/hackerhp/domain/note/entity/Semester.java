package org.hackerkhu.hackerhp.domain.note.entity;

/**
 * 학기 (spec 2-1 §2-1-1).
 *
 * <p><b>학사 순서대로 둔다</b> — 1학기 → 여름 → 2학기 → 겨울. 화면이 이 순서를 그대로 보여주므로 선언 순서가 곧 표시 순서다.
 *
 * <p>계절학기는 2026-08-29에 더했다 (#272). 그 전에 쌓인 자료는 {@link #SPRING}·{@link #FALL}뿐이고 그대로 유효하다.
 */
public enum Semester {
  /** 1학기 */
  SPRING,
  /** 여름 계절학기 */
  SUMMER,
  /** 2학기 */
  FALL,
  /** 겨울 계절학기 */
  WINTER
}
