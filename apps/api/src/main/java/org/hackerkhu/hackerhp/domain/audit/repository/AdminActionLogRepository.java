package org.hackerkhu.hackerhp.domain.audit.repository;

import java.util.List;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {

  /** 그 사람에게 무슨 일이 있었나. 조회 API는 아직 없고(#143 제외 범위) 시험이 쓴다. */
  List<AdminActionLog> findByTargetIdOrderByIdAsc(Long targetId);
}
