-- 학기 전환 — 일괄 비활성화와 복구 (#228, #230, spec 2-2 §2-2-3).
--
-- 되돌릴 근거가 응답이 아니라 데이터여야 한다. 비활성화는 조건으로 실행되므로
-- "방금 누가 내려갔나"가 응답에만 담기는데, 그 응답은 잃을 수 있다 — 세션 반영이
-- 실패해 500이 나가거나, 브라우저가 닫히거나, 연결이 끊긴다. 재요청은 "이미 전원
-- INACTIVE"라 빈 목록을 주므로 그 뒤로는 원래 비활동이던 사람과 방금 내려간 사람을
-- 영영 가르지 못한다.
--
-- 이력은 이 자리를 대신하지 못한다. 이력은 세션 반영보다 뒤에 남기고 실패를
-- 삼키므로(§2-2-7) 정확히 그 실패 경로에서 비어 있다.
--
-- INACTIVE일 때만 값이 있다. 이전 상태를 기억하는 열이 아니다 — SUSPENDED가 된
-- 사람에게서도 지워지므로 정지 해제가 INACTIVE로 돌아가는 근거로 쓸 수 없다.
ALTER TABLE users ADD COLUMN deactivated_at TIMESTAMP;

-- 학기마다 전원이 대상이라 이 조건으로 훑는 일이 잦다.
CREATE INDEX idx_users_role_status ON users (role, status);

-- 내려간 것과 올라온 것을 가른다. 뭉치면 이력을 읽어도 무엇이 있었는지 알 수 없다 —
-- 기존 ACTIVATE는 "정지 해제"라 뜻이 다르다.
--
-- 제약은 교체된다 (DROP -> ADD). 그래서 기존 아홉 값을 전부 다시 적는다. 새 값만
-- 적으면 그 아홉 조작의 이력 INSERT가 전부 거절되는데, 이력은 실패를 삼키므로
-- 화면에는 아무 일도 없어 보인 채 감사 기록만 빈다.
ALTER TABLE admin_actions DROP CONSTRAINT IF EXISTS admin_actions_action_check;

ALTER TABLE admin_actions
    ADD CONSTRAINT admin_actions_action_check CHECK (
        action IN (
            'APPROVE', 'SUSPEND', 'ACTIVATE', 'PROMOTE_ADMIN',
            'REJECT', 'REMOVE', 'WITHDRAW', 'GRANT_ADMIN', 'REVOKE_ADMIN',
            'DEACTIVATE', 'REACTIVATE'
        )
    );
