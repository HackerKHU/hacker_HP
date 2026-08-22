package org.hackerkhu.hackerhp.domain.notice.repository;

import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
