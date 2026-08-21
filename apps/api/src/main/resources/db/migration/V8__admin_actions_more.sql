-- 거부·제거·권한 변경을 이력에 담는다 (#58, spec 2-2 §2-2-7).
--
-- V5는 승인·정지·해제·최초 승격 넷만 알았다. 회원 관리를 완성하면서 네 동작이 늘어난다.
--
-- 권한 변경을 부여와 회수로 가른다. 하나로 뭉치면 이력을 읽어도 어느 방향인지 알 수 없다 —
-- SUSPEND와 ACTIVATE를 가른 것과 같은 이유다.
ALTER TABLE admin_actions DROP CONSTRAINT IF EXISTS admin_actions_action_check;

ALTER TABLE admin_actions
    ADD CONSTRAINT admin_actions_action_check CHECK (
        action IN (
            'APPROVE', 'SUSPEND', 'ACTIVATE', 'PROMOTE_ADMIN',
            'REJECT', 'REMOVE', 'GRANT_ADMIN', 'REVOKE_ADMIN'
        )
    );
