// 화면이 갱신되어도 "같은 열차" 를 가리키기 위한 식별자.
// (android: domain/TrainKey.kt, DESIGN.md §38-4)
//
// **주키는 열차 번호다.** 코레일은 같은 출발 시각에 다른 편성이 있다 (07:11 의 305 와 381).
// 시각을 주키로 두면 305 를 체크했는데 381 이 열렸을 때 발견으로 처리되고,
// 자동 클릭이 엉뚱한 편성을 잡는다. 번호를 읽지 못했을 때만 시각 대체 경로로 떨어진다.
//
// 시각은 `"HH:MM"` 문자열로 들고 다닌다. Kotlin 의 `LocalTime.toString()` 과 표기가 같아
// 로그·화면·테스트가 양쪽에서 똑같이 읽히고, 0 을 채운 형식이라 문자열 비교만으로 정렬된다.
// 문자열이라 chrome.storage / postMessage 를 그대로 통과한다는 점도 중요하다.

/** `{ trainNumber, departureTime }`. 데이터일 뿐 메서드를 달지 않는다. */
export function trainKey(trainNumber, departureTime) {
  return { trainNumber: trainNumber ?? '', departureTime: departureTime ?? '' };
}

/** 번호를 읽어내지 못해 출발 시각에 기대야 하는 키인가. (모호할 수 있다) */
export function trainKeyAmbiguous(key) {
  return !key || !key.trainNumber || key.trainNumber.trim() === '';
}

export function trainKeyMatches(a, b) {
  if (!a || !b) return false;
  // 양쪽 다 번호가 있으면 번호만으로 판정한다.
  // 한 조회 결과 안에서 열차 번호는 유일하다.
  if (!trainKeyAmbiguous(a) && !trainKeyAmbiguous(b)) {
    return a.trainNumber === b.trainNumber;
  }
  // 한쪽이라도 번호가 없으면 시각으로 대체한다. (모호할 수 있는 경로)
  return a.departureTime === b.departureTime;
}

/** "07:11 305" */
export function trainKeyLabel(key) {
  if (!key) return '';
  return trainKeyAmbiguous(key) ? key.departureTime : `${key.departureTime} ${key.trainNumber}`;
}

/**
 * 시각 문자열 정규화. "18:32", "18:32 도착", "출발 18:32" 에서 시각을 뽑는다.
 * 읽지 못하면 null — 시각이 없는 편성은 사용자에게 보여줄 수도, 확인할 수도 없다.
 *
 * 코레일도 24:00 이후 표기를 쓰지 않으므로 24시 이상은 무효로 본다.
 * (android: parser/TrainParser.parseTime)
 */
export function parseTime(raw) {
  const text = (raw ?? '').trim();
  if (text.length === 0) return null;
  const m = /([0-2]?\d):([0-5]\d)/.exec(text);
  if (!m) return null;
  const hour = Number(m[1]);
  const minute = Number(m[2]);
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) return null;
  if (hour > 23 || minute > 59) return null;
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}
