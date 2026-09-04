package org.hackerkhu.hackerhp.domain.notice.repository;

import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

  /**
   * <b>작성자를 함께 가져온다.</b> 응답이 {@code authorName}을 담기 때문이다 (#58).
   *
   * <p>{@code author}는 {@code LAZY}라 그냥 두면 <b>한 페이지에 실린 공지 수만큼 사용자 조회가 더 나간다</b> — 목록 쿼리 1번 + N번.
   * 작성자가 겹치면 영속성 컨텍스트가 일부를 막아주지만, 서로 다른 관리자가 쓴 20건이면 그대로 20번이다.
   *
   * <p><b>to-one이라 페이지네이션과 함께 써도 안전하다.</b> 컬렉션을 fetch join하면 조인으로 늘어난 행 때문에 Hibernate가 페이징을 메모리에서
   * 처리하지만(그쪽은 위험하다), to-one 조인은 행 수를 늘리지 않아 {@code LIMIT}이 그대로 먹는다.
   */
  @Override
  @EntityGraph(attributePaths = "author")
  Page<Notice> findAll(Pageable pageable);

  /**
   * <b>내가 좋아요한 공지만</b> (#355, 3-3 결정 28).
   *
   * <p><b>{@code EXISTS}로 본다.</b> 좋아요 표와 조인하면 공지가 좋아요 행 수만큼 중복돼 페이지 크기가 어긋난다 — 여기서 필요한 것은 "그 조합이
   * 있나"뿐이다.
   *
   * <p><b>작성자를 함께 읽는 이유는 {@link #findAll}과 같다</b> — 응답이 {@code authorName}을 담기 때문이다(#58). 작성자가 나간
   * 공지도 목록에 남아야 하므로 {@code LEFT JOIN FETCH}다. to-one이라 페이지네이션과 함께 써도 행이 늘지 않는다.
   *
   * <p>정렬은 부르는 쪽이 {@code Pageable}에 실어 준다 — 전체 목록과 같은 고정 정렬이어야 필터를 켜고 끌 때 순서가 흔들리지 않는다.
   */
  @Query(
      value =
          """
          select n from Notice n left join fetch n.author
          where exists (select 1 from NoticeLike l
                        where l.noticeId = n.id and l.userId = :viewerId)
          """,
      countQuery =
          """
          select count(n) from Notice n
          where exists (select 1 from NoticeLike l
                        where l.noticeId = n.id and l.userId = :viewerId)
          """)
  Page<Notice> findLikedBy(@Param("viewerId") Long viewerId, Pageable pageable);
}
