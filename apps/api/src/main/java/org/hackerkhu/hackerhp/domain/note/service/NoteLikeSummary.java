package org.hackerkhu.hackerhp.domain.note.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 자료 하나의 좋아요 개수와 <b>내가 눌렀는지</b> (#367 리뷰).
 *
 * <p><b>두 값을 함께 들고 다니는 이유는 함께 읽어야 하기 때문이다.</b> 개수와 내 상태를 각각 질의하면 기본 {@code READ COMMITTED}에서 스냅샷이
 * 갈려 {@code 0}개인데 내가 눌렀다고 답하는 응답이 만들어진다 — 그 모순을 막으려고 {@code
 * NoteLikeRepository#countWithMineByNoteIds}가 한 문장에서 둘을 읽고, 그 결과를 이 타입으로 옮긴다.
 *
 * <p>조회·수정 두 경로가 같은 변환을 쓰므로 여기 모은다.
 */
record NoteLikeSummary(long count, boolean likedByMe) {

  /** 좋아요가 하나도 없는 자료 — 질의 결과에 행이 없으면 이 값이다. */
  static final NoteLikeSummary NONE = new NoteLikeSummary(0L, false);

  /** {@code [noteId, count, 내가 누른 수]} 행들을 자료 id로 묶는다. */
  static Map<Long, NoteLikeSummary> byNoteId(List<Object[]> rows) {
    Map<Long, NoteLikeSummary> found = new HashMap<>();
    for (Object[] row : rows) {
      found.put((Long) row[0], new NoteLikeSummary(number(row[1]), number(row[2]) > 0));
    }
    return found;
  }

  /** {@code SUM}은 행이 없으면 {@code null}이고, 개수 계열은 드라이버에 따라 {@code Long}·{@code BigInteger}로 온다. */
  private static long number(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }
}
