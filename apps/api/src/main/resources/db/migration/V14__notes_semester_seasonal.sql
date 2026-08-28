-- 계절학기를 학기에 더한다 (#272, spec 2-1 §2-1-1).
--
-- 여름·겨울에 만든 정리본을 올릴 방법이 없었다. enum만 늘리면 V4의 CHECK가
-- 두 값으로 못 박고 있어 저장에서 터진다.
--
-- 제약은 교체된다 (DROP -> ADD). 그래서 기존 두 값을 다시 적는다 — 새 값만
-- 적으면 이미 쌓인 자료의 수정이 전부 거절된다.
--
-- 기존 행은 손대지 않는다. SPRING·FALL이 그대로 유효하다.
--
-- VARCHAR(10)은 그대로 둔다. SUMMER·WINTER 모두 6자다.
ALTER TABLE notes DROP CONSTRAINT IF EXISTS notes_semester_check;

ALTER TABLE notes
    ADD CONSTRAINT notes_semester_check CHECK (
        semester IN ('SPRING', 'SUMMER', 'FALL', 'WINTER')
    );
