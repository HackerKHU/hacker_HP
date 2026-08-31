package org.hackerkhu.hackerhp.domain.note.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSearch;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSort;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.springframework.data.jpa.domain.Specification;

/**
 * 자료 목록의 조회 조건과 순서 (spec 2-1 §2-1-1, 3-2 §3-2-4).
 *
 * <p>조건을 JPQL 문자열로 쓰지 않는다 — 회원 목록({@code AdminUserSpecifications})과 같은 이유다. 조건이 하나 늘 때마다 질의문 전체를 다시
 * 읽어야 하고, 타입이 정해지지 않은 널 파라미터가 PostgreSQL에서 문제를 일으킨다.
 *
 * <p><b>정렬도 여기서 만든다.</b> {@code Pageable}에 실어 보내면 Spring Data가 Criteria 질의에서 걸려 넘어진다 — 회원 목록에서 이미
 * 겪은 자리다.
 */
public final class NoteSpecifications {

  /** {@code LIKE}에서 뜻을 가지는 문자. 검색어에 들어 있으면 글자 그대로 찾아야 한다. */
  private static final char ESCAPE = '\\';

  private NoteSpecifications() {}

  public static Specification<Note> matching(NoteSearch search, NoteSort sort) {
    return (root, query, builder) -> {
      applyOrder(root, query, builder, sort);
      return builder.and(conditions(search, root, builder).toArray(new Predicate[0]));
    };
  }

  private static List<Predicate> conditions(
      NoteSearch search, Root<Note> root, CriteriaBuilder builder) {
    List<Predicate> conditions = new ArrayList<>();

    if (search.category() != null) {
      conditions.add(builder.equal(root.get("category"), search.category()));
    }
    if (search.subject() != null) {
      conditions.add(builder.equal(root.get("subjectName"), search.subject()));
    }
    if (search.professor() != null) {
      conditions.add(builder.equal(root.get("professor"), search.professor()));
    }
    if (search.year() != null) {
      conditions.add(builder.equal(root.get("year"), search.year()));
    }
    if (search.semester() != null) {
      conditions.add(builder.equal(root.get("semester"), search.semester()));
    }
    if (search.examType() != null) {
      conditions.add(builder.equal(root.get("examType"), search.examType()));
    }

    /*
     * 통합 검색 (2-1 §2-1-1 MUST). 필드를 나눠 받지 않으므로 세 컬럼에 OR로 건다.
     *
     * 전문 검색(tsvector)을 쓰지 않는 이유는 한국어 형태소 분석이 없어서다 — "자료구조"로
     * "자료 구조"를 찾지 못한다. 이 규모에서는 부분 일치가 오히려 낫다.
     */
    if (search.q() != null) {
      String pattern = "%" + escapeLike(search.q()).toLowerCase() + "%";
      conditions.add(
          builder.or(
              builder.like(builder.lower(root.get("title")), pattern, ESCAPE),
              builder.like(builder.lower(root.get("subjectName")), pattern, ESCAPE),
              // 교수명은 없을 수 있다. coalesce가 없으면 그 행은 검색에서 통째로 빠진다.
              builder.like(
                  builder.lower(builder.coalesce(root.get("professor"), "")), pattern, ESCAPE)));
    }
    return conditions;
  }

  /**
   * <b>마지막 기준은 언제나 {@code id}다.</b>
   *
   * <p>같은 시각에 등록됐거나 제목이 같은 자료가 여럿이면 순서가 정해지지 않는다. 그러면 페이지를 넘길 때마다 배치가 달라져 <b>같은 자료가 두 번 보이거나 아예
   * 빠진다</b> — 목록을 훑는 사람은 그것을 알아채지 못한다.
   *
   * <p><b>{@link NoteSort#VIEWS}에서 특히 그렇다</b> (#245). 새로 올라온 자료는 전부 {@code 0}이라 <b>동률이 목록을 통째로
   * 채운다</b> — 다른 정렬에서는 드문 경우가 여기서는 기본값이다.
   */
  private static void applyOrder(
      Root<Note> root, CriteriaQuery<?> query, CriteriaBuilder builder, NoteSort sort) {
    // 건수를 세는 질의에는 순서가 필요 없다. 붙이면 DB에 따라 거절당한다.
    if (Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType())) {
      return;
    }
    List<Order> criteria = new ArrayList<>();
    if (sort == NoteSort.TITLE) {
      criteria.add(builder.asc(builder.lower(root.get("title"))));
    } else if (sort == NoteSort.VIEWS) {
      criteria.add(builder.desc(root.get("viewCount")));
    } else {
      criteria.add(builder.desc(root.get("createdAt")));
    }
    criteria.add(builder.desc(root.get("id")));
    query.orderBy(criteria);
  }

  /**
   * 검색어의 {@code %}·{@code _}를 글자 그대로 만든다.
   *
   * <p>escape하지 않으면 {@code _}가 "아무 글자 하나"로 해석되어 <b>찾지 않은 자료가 결과에 섞인다.</b>
   */
  private static String escapeLike(String keyword) {
    return keyword
        .replace(String.valueOf(ESCAPE), ESCAPE + "\\")
        .replace("%", ESCAPE + "%")
        .replace("_", ESCAPE + "_");
  }
}
