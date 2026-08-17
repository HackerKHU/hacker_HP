/**
 * 표에서 값을 꺼낸다. **선언하지 않은 키는 `undefined`다.**
 *
 * 객체를 변수로 인덱싱하면 `__proto__`·`constructor`·`toString` 같은 프로토타입 키가
 * **선언한 적 없는데도 값을 돌려준다.** `ERROR_MESSAGE['__proto__']`는 `Object.prototype`을
 * 주고, 그것이 truthy라 화면이 객체를 자식으로 렌더하려다 죽는다 — URL 하나로 공개
 * 진입점이 통째로 멈춘 적이 있다.
 *
 * `Object.hasOwn`으로 **직접 선언한 키인지** 먼저 확인한다.
 *
 * 표를 `Map`이나 `Object.create(null)`로 바꾸는 방법도 있지만 함수 하나로 두었다.
 * 표는 리터럴 그대로 읽히고 `Object.entries`로 순회하는 자리도 그대로 쓸 수 있으며,
 * **다음에 새 표가 생겨도 이 함수만 부르면 된다** — 표마다 안전한 자료구조를 고르게
 * 하면 언젠가 하나가 빠진다.
 */
export function lookup<T>(
  table: Record<string, T>,
  key: string | null | undefined,
): T | undefined {
  if (key === null || key === undefined) return undefined
  return Object.hasOwn(table, key) ? table[key] : undefined
}
