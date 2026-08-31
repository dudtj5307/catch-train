// `node:test` 의 아주 작은 대역. **import map 으로 갈아 끼운다.**
//
// 이 머신에는 Node 가 없어서 `node --test` 를 돌릴 수 없다. 테스트 파일을 브라우저용으로
// 고쳐 쓰는 대신 **`node:test` 쪽을 흉내 내서** 같은 파일이 양쪽에서 돌게 한다 —
// 테스트를 두 벌로 만들면 반드시 한쪽만 고치게 된다.
//
// 쓰는 것만 만든다: `test(name, fn)`. 하위 test, mock, skip 은 없다.

const queue = [];

export default function test(name, fn) {
  queue.push({ name, fn });
}

export { test };

/** 등록된 순서대로 하나씩. 실패해도 멈추지 않는다 — 몇 개가 깨졌는지가 알고 싶다. */
export async function runAll() {
  const results = [];
  for (const { name, fn } of queue) {
    try {
      await fn();
      results.push({ name, ok: true });
    } catch (e) {
      results.push({ name, ok: false, error: (e && (e.message || String(e))) || 'unknown' });
    }
  }
  return results;
}
