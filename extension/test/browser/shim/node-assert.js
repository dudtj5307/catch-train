// `node:assert/strict` 의 아주 작은 대역. (짝: `node-test.js`)
//
// 쓰는 것만 만든다 — `equal` `notEqual` `deepEqual` `ok` `fail` `throws` `rejects`.
// **전부 strict 비교다.** 느슨한 비교를 흉내 내면 Node 에서 통과하던 것이 여기서
// 통과하지 않거나 그 반대가 되고, 그러면 이 대역은 있으나 마나다.

class AssertionError extends Error {}

function show(value) {
  if (typeof value === 'string') return JSON.stringify(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function fail(message) {
  throw new AssertionError(message);
}

function equal(actual, expected, message) {
  if (!Object.is(actual, expected)) {
    fail(message || `equal 실패: ${show(actual)} !== ${show(expected)}`);
  }
}

function notEqual(actual, expected, message) {
  if (Object.is(actual, expected)) {
    fail(message || `notEqual 실패: 둘 다 ${show(actual)}`);
  }
}

function isDeepEqual(a, b) {
  if (Object.is(a, b)) return true;
  if (typeof a !== typeof b) return false;
  if (a === null || b === null) return false;
  if (typeof a !== 'object') return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;

  if (a instanceof Set) {
    if (a.size !== b.size) return false;
    for (const v of a) if (!b.has(v)) return false;
    return true;
  }
  if (a instanceof Map) {
    if (a.size !== b.size) return false;
    for (const [k, v] of a) if (!b.has(k) || !isDeepEqual(v, b.get(k))) return false;
    return true;
  }

  const ka = Object.keys(a);
  const kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && isDeepEqual(a[k], b[k]));
}

function deepEqual(actual, expected, message) {
  if (!isDeepEqual(actual, expected)) {
    fail(message || `deepEqual 실패: ${show(actual)} !== ${show(expected)}`);
  }
}

function ok(value, message) {
  if (!value) fail(message || `ok 실패: ${show(value)}`);
}

function throws(fn, expected, message) {
  let threw = null;
  try {
    fn();
  } catch (e) {
    threw = e;
  }
  if (!threw) fail(message || 'throws 실패: 예외가 나지 않았다');
  matchExpected(threw, expected, message);
}

async function rejects(promiseOrFn, expected, message) {
  let threw = null;
  try {
    await (typeof promiseOrFn === 'function' ? promiseOrFn() : promiseOrFn);
  } catch (e) {
    threw = e;
  }
  if (!threw) fail(message || 'rejects 실패: 거부되지 않았다');
  matchExpected(threw, expected, message);
}

/**
 * `ErrorClass` · `/정규식/` · **검증 함수** 셋을 받는다.
 *
 * 셋째가 중요하다 — `assert.rejects(p, (e) => e instanceof AbortError)` 처럼
 * 화살표 함수를 넘기는 것이 Node 에서 되는 일이라, 함수를 무조건 생성자로 보면
 * `prototype` 이 없다고 터진다. 생성자로 먼저 보고, 아니면 불러서 결과를 믿는다.
 */
function matchExpected(error, expected, message) {
  if (!expected) return;

  if (expected instanceof RegExp) {
    if (!expected.test(String(error && error.message))) {
      fail(message || `예외 문구가 맞지 않다: ${error && error.message}`);
    }
    return;
  }

  if (typeof expected === 'function') {
    const isClass = typeof expected.prototype === 'object' && expected.prototype !== null;
    if (isClass && error instanceof expected) return;
    if (expected(error)) return;
    fail(message || `예외가 기대와 다르다: ${error && error.message}`);
  }
}

const assert = Object.assign(ok, {
  AssertionError,
  equal,
  notEqual,
  deepEqual,
  strictEqual: equal,
  notStrictEqual: notEqual,
  deepStrictEqual: deepEqual,
  ok,
  fail,
  throws,
  rejects,
});

export default assert;
export { AssertionError, deepEqual, equal, fail, notEqual, ok, rejects, throws };
