package org.hackerkhu.hackerhp.domain.user.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 그 회원이 남길 콘텐츠 건수 (spec 2-2 §2-2-4 MUST).
 *
 * <p><b>엔티티가 아니라 SQL로 센다.</b> 여기 필요한 것은 {@code COUNT} 몇 개뿐이라 매핑을 기다릴 이유가 없다 — 그 API가 생기든 말든 세는 방법은
 * 같다.
 *
 * <p>한 번에 센다. 따로 물으면 왕복이 그만큼 늘고, 그 사이에 값이 갈라진다.
 *
 * <p><b>콘텐츠 종류가 늘면 여기도 늘어야 한다</b> (#236). 빠뜨리면 관리자가 <b>그것이 남는다는 사실을 보지 못한 채</b> 되돌릴 수 없는 제거를 하고,
 * 작성자 관계가 끊긴 뒤에는 그 회원의 글을 다시 찾을 수도 없다 — 이 조회가 존재하는 이유 그대로다.
 */
@Repository
public class AuthoredContentRepository {

  private static final String COUNTS =
      """
      SELECT (SELECT count(*) FROM notes    WHERE uploader_id = ?) AS notes,
             (SELECT count(*) FROM notices  WHERE author_id   = ?) AS notices,
             (SELECT count(*) FROM photos   WHERE uploader_id = ?) AS photos,
             (SELECT count(*) FROM posts    WHERE author_id   = ?) AS posts
      """;

  private final JdbcTemplate jdbcTemplate;

  public AuthoredContentRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Counts countBy(Long userId) {
    return jdbcTemplate.queryForObject(
        COUNTS,
        (rs, rowNum) ->
            new Counts(
                rs.getLong("notes"),
                rs.getLong("notices"),
                rs.getLong("photos"),
                rs.getLong("posts")),
        userId,
        userId,
        userId,
        userId);
  }

  public record Counts(long notes, long notices, long photos, long posts) {}
}
