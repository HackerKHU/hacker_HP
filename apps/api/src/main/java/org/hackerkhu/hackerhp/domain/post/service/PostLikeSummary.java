package org.hackerkhu.hackerhp.domain.post.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 게시글 하나의 좋아요 개수와 <b>내가 눌렀는지</b> (#368 리뷰).
 *
 * <p><b>두 값을 함께 들고 다니는 이유는 함께 읽어야 하기 때문이다.</b> 개수와 내 상태를 각각 질의하면 기본 {@code READ COMMITTED}에서 스냅샷이
 * 갈려 {@code 0}개인데 내가 눌렀다고 답하는 응답이 만들어진다 — 그 모순을 막으려고 {@code
 * PostLikeRepository#countWithMineByPostIds}가 한 문장에서 둘을 읽고, 그 결과를 이 타입으로 옮긴다.
 *
 * <p>목록·상세·수정 세 경로가 같은 변환을 쓰므로 여기 모은다.
 */
record PostLikeSummary(long count, boolean likedByMe) {

  /** 좋아요가 하나도 없는 게시글 — 질의 결과에 행이 없으면 이 값이다. */
  static final PostLikeSummary NONE = new PostLikeSummary(0L, false);

  /** {@code [postId, count, 내가 누른 수]} 행들을 게시글 id로 묶는다. */
  static Map<Long, PostLikeSummary> byPostId(List<Object[]> rows) {
    Map<Long, PostLikeSummary> found = new HashMap<>();
    for (Object[] row : rows) {
      found.put((Long) row[0], new PostLikeSummary(number(row[1]), number(row[2]) > 0));
    }
    return found;
  }

  /** {@code SUM}은 행이 없으면 {@code null}이고, 개수 계열은 드라이버에 따라 {@code Long}·{@code BigInteger}로 온다. */
  private static long number(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }
}
