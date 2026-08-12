package org.hackerkhu.hackerhp.domain.user.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserSearch;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * 회원 목록의 조회 조건과 순서 (spec 2-2 §2-2-1, 3-2 §3-2-6).
 *
 * <p>조건을 JPQL 문자열로 쓰지 않는다. {@code (:status is null or u.status = :status)} 식으로 늘어놓으면 조건이 하나 늘 때마다
 * 질의문 전체를 다시 읽어야 하고, 타입이 정해지지 않은 널 파라미터가 PostgreSQL에서 문제를 일으킨다.
 *
 * <p><b>정렬도 여기서 만든다.</b> {@code Pageable}에 실어 보내면 안 된다 — Spring Data는 Criteria 질의에 {@code NULLS
 * LAST}를 적용하지 못하고 {@code UnsupportedOperationException}을 던진다. 널의 위치는 이 화면에서 그냥 넘길 수 없는 문제라({@link
 * #nullsLast}) 표현식으로 직접 만든다.
 */
public final class AdminUserSpecifications {

  /** {@code LIKE}에서 뜻을 가지는 문자. 검색어에 들어 있으면 글자 그대로 찾아야 한다. */
  private static final char ESCAPE = '\\';

  private AdminUserSpecifications() {}

  /**
   * @param orders 이미 검증된 정렬 기준. 마지막에 {@code id}가 동점을 가른다
   */
  public static Specification<User> matching(AdminUserSearch search, List<Sort.Order> orders) {
    return (root, query, builder) -> {
      applyOrder(root, query, builder, orders);
      return builder.and(conditions(search, root, builder).toArray(new Predicate[0]));
    };
  }

  private static List<Predicate> conditions(
      AdminUserSearch search, Root<User> root, CriteriaBuilder builder) {
    List<Predicate> conditions = new ArrayList<>();

    if (search.status() != null) {
      conditions.add(builder.equal(root.get("status"), search.status()));
    }
    if (search.role() != null) {
      conditions.add(builder.equal(root.get("role"), search.role()));
    }
    /*
     * 신청 여부. PENDING이면서 신청하지 않은 계정은 승인 대상이 아니다 (3-2 §3-2-6 MUST).
     * 화면에서 거르는 것으로는 안 된다 — 서버가 20건을 주고 화면이 그중 일부를 버리면
     * 페이지마다 보이는 건수가 들쭉날쭉하고 총 건수가 실제와 어긋난다.
     */
    if (search.applied() != null) {
      conditions.add(
          search.applied()
              ? builder.isNotNull(root.get("appliedAt"))
              : builder.isNull(root.get("appliedAt")));
    }
    if (search.q() != null) {
      String pattern = "%" + escapeLike(search.q()).toLowerCase() + "%";
      conditions.add(
          builder.or(
              builder.like(builder.lower(root.get("name")), pattern, ESCAPE),
              // 신청 전 계정은 학번이 없다. coalesce가 없으면 그 행은 검색에서 통째로 빠진다.
              builder.like(
                  builder.lower(builder.coalesce(root.get("studentNo"), "")), pattern, ESCAPE),
              builder.like(builder.lower(root.get("email")), pattern, ESCAPE)));
    }
    return conditions;
  }

  private static void applyOrder(
      Root<User> root, CriteriaQuery<?> query, CriteriaBuilder builder, List<Sort.Order> orders) {
    // 건수를 세는 질의에는 순서가 필요 없다. 붙이면 DB에 따라 거절당한다.
    if (Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType())) {
      return;
    }
    List<Order> criteria = new ArrayList<>();
    for (Sort.Order order : orders) {
      Expression<?> path = root.get(order.getProperty());
      criteria.add(nullsLast(builder, path));
      criteria.add(order.isAscending() ? builder.asc(path) : builder.desc(path));
    }
    // 동점을 가르는 마지막 기준. 널이 될 수 없으므로 위 처리가 필요 없다.
    criteria.add(builder.asc(root.get("id")));
    query.orderBy(criteria);
  }

  /**
   * 값이 없는 행을 언제나 뒤로 보낸다.
   *
   * <p>PostgreSQL은 {@code DESC}에서 널을 <b>맨 앞에</b> 올린다. 기본 정렬이 신청일 최신순이므로, 그대로 두면 <b>신청조차 하지 않은 계정이
   * 승인 대기자보다 위에 온다</b> — 관리자가 첫 화면에서 보는 것이 승인 대상이 아닌 사람들이 된다. 학번 정렬도 마찬가지로 빈 칸부터 보이게 된다.
   */
  private static Order nullsLast(CriteriaBuilder builder, Expression<?> path) {
    return builder.asc(builder.<Integer>selectCase().when(builder.isNull(path), 1).otherwise(0));
  }

  /**
   * 검색어의 {@code %}·{@code _}를 글자 그대로 만든다.
   *
   * <p>escape하지 않으면 {@code _}가 "아무 글자 하나"로 해석되어 <b>찾지 않은 회원이 결과에 섞인다.</b> 관리자는 그 목록을 보고 승인·정지를 누른다.
   */
  private static String escapeLike(String keyword) {
    return keyword
        .replace(String.valueOf(ESCAPE), ESCAPE + "\\")
        .replace("%", ESCAPE + "%")
        .replace("_", ESCAPE + "_");
  }
}
