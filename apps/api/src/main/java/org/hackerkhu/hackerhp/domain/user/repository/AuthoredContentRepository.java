package org.hackerkhu.hackerhp.domain.user.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 그 회원이 남길 콘텐츠 건수 (spec 2-2 §2-2-4 MUST).
 *
 * <p><b>엔티티가 아니라 SQL로 센다.</b> {@code photos}에는 아직 엔티티가 없고(#57), 여기 필요한 것은 세 개의 {@code COUNT}뿐이라 매핑을
 * 기다릴 이유가 없다 — 그 API가 생기든 말든 세는 방법은 같다.
 *
 * <p>한 번에 센다. 셋을 따로 물으면 왕복이 세 번이고, 그 사이에 값이 갈라진다.
 */
@Repository
public class AuthoredContentRepository {

  private static final String COUNTS =
      """
      SELECT (SELECT count(*) FROM notes    WHERE uploader_id = ?) AS notes,
             (SELECT count(*) FROM notices  WHERE author_id   = ?) AS notices,
             (SELECT count(*) FROM photos   WHERE uploader_id = ?) AS photos
      """;

  private final JdbcTemplate jdbcTemplate;

  public AuthoredContentRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Counts countBy(Long userId) {
    return jdbcTemplate.queryForObject(
        COUNTS,
        (rs, rowNum) ->
            new Counts(rs.getLong("notes"), rs.getLong("notices"), rs.getLong("photos")),
        userId,
        userId,
        userId);
  }

  public record Counts(long notes, long notices, long photos) {}
}
