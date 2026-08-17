package org.hackerkhu.hackerhp.domain.notice.repository;

import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {}
