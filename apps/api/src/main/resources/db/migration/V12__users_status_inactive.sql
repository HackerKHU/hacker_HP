-- 비활동 부원 상태를 더한다 (#228, #229, spec 3-1 §3-1-2).
--
-- 이번 학기에 활동하지 않는 부원이다. 자료 기능 전체가 막히고 나머지는 ACTIVE와 같다.
--
-- V1이 status를 세 값으로 못 박아 두었다. 제약을 넓히지 않으면 enum만 늘려도
-- 저장에서 터진다. 제약은 교체되므로 기존 세 값을 전부 다시 적는다.
--
-- 기존 행은 손대지 않는다 — 값이 그대로 유효하다.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check;

ALTER TABLE users
    ADD CONSTRAINT users_status_check CHECK (
        status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'SUSPENDED')
    );
