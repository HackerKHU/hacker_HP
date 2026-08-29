-- 본인 탈퇴를 이력에 담는다 (#223, #225, spec 2-2 §2-2-4).
--
-- REMOVE와 가른다. 계정이 사라지고 나면 남는 것은 숫자 id뿐이라, 뭉치면
-- "관리자가 지웠다"와 "본인이 나갔다"를 영영 가를 수 없다.
--
-- 제약은 교체된다 (DROP -> ADD). 그래서 기존 여덟 값을 전부 다시 적는다 —
-- 새 값만 적으면 그 여덟 조작의 이력 INSERT가 전부 거절되는데, 이력은 실패를
-- 삼키므로(2-2 §2-2-7) 화면에는 아무 일도 없어 보인 채 감사 기록만 빈다.
ALTER TABLE admin_actions DROP CONSTRAINT IF EXISTS admin_actions_action_check;

ALTER TABLE admin_actions
    ADD CONSTRAINT admin_actions_action_check CHECK (
        action IN (
            'APPROVE', 'SUSPEND', 'ACTIVATE', 'PROMOTE_ADMIN',
            'REJECT', 'REMOVE', 'GRANT_ADMIN', 'REVOKE_ADMIN',
            'WITHDRAW'
        )
    );
