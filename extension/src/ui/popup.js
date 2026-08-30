// 팝업 — [열차 선택] 목록. (android: ui/WatchScreen.kt 의 `TrainSelectPanel`)
//
// 화면은 service worker 에만 말을 건다. selector 도 DOM 판독도 모른다 (대원칙 8).
// 사용자가 코레일에서 직접 조회한 결과를 그대로 펼쳐 주고, 체크한 칸을 돌려줄 뿐이다.
//
// 매진인 칸도 체크할 수 있다. **지금 매진인 좌석이 풀리기를 기다리는 것이 이 도구의
// 목적이다.** 반대로 아예 없는 칸(UNKNOWN)은 체크할 수 없다 — 영영 열리지 않는 칸을
// 기다리게 된다.

import { SeatClass, seatClassLabel } from '../domain/seat-class.js';
import { SeatStatus, seatStatusLabel } from '../domain/seat-status.js';
import { PageStatus, pageStatusLabel } from '../domain/page-snapshot.js';
import { keyOf, seatStatusOf } from '../domain/train.js';
import { selectionContainsTrain, selectionSize } from '../domain/watch-selection.js';

const els = {
  count: document.getElementById('count'),
  clear: document.getElementById('clear'),
  refresh: document.getElementById('refresh'),
  notice: document.getElementById('notice'),
  table: document.getElementById('table'),
  rows: document.getElementById('rows'),
  warnings: document.getElementById('warnings'),
};

let state = { snapshot: null, selection: { seats: [] } };

els.refresh.addEventListener('click', read);
els.clear.addEventListener('click', async () => {
  const res = await send({ type: 'CLEAR_SELECTION' });
  if (res.ok) {
    state.selection = res.selection;
    render();
  }
});

read();

async function read() {
  els.refresh.disabled = true;
  els.refresh.textContent = '읽는 중…';
  try {
    const res = await send({ type: 'READ_PAGE' });
    if (res.ok) {
      state = { snapshot: res.snapshot, selection: res.selection };
    } else {
      state = { snapshot: null, selection: { seats: [] }, failure: res };
    }
    render();
  } finally {
    els.refresh.disabled = false;
    els.refresh.textContent = '갱신';
  }
}

async function toggle(train, seatClass) {
  const res = await send({ type: 'TOGGLE_SEAT', trainKey: keyOf(train), seatClass });
  if (res.ok) {
    state.selection = res.selection;
    render();
  }
}

function render() {
  const { snapshot, selection } = state;
  const trains = snapshot ? snapshot.trains : [];

  els.count.textContent = snapshot ? `선택 ${selectionSize(selection)} / 조회 ${trains.length}` : '';
  els.clear.hidden = selectionSize(selection) === 0;

  const notice = noticeFor(state);
  els.notice.textContent = notice ?? '';
  els.notice.hidden = notice === null;

  els.table.hidden = trains.length === 0;
  els.rows.replaceChildren(...trains.map((train) => trainRow(train, selection)));

  const warnings = snapshot && snapshot.warnings.length > 0
    ? [...new Set(snapshot.warnings)].join('\n')
    : '';
  els.warnings.textContent = warnings;
  els.warnings.hidden = warnings === '';
}

function trainRow(train, selection) {
  const tr = document.createElement('tr');

  const info = document.createElement('td');
  info.className = 'train';
  const time = document.createElement('div');
  time.className = 'time';
  time.textContent = `${train.departureTime} → ${train.arrivalTime}`;
  const number = document.createElement('div');
  number.className = 'number';
  number.textContent = [
    train.trainNumber || '열차번호 미상',
    train.departureStation && `${train.departureStation} → ${train.arrivalStation}`,
  ].filter(Boolean).join('  ');
  info.append(time, number);

  tr.append(
    info,
    // 사이트와 같은 순서: 일반실이 왼쪽, 특실이 오른쪽. **SRT 와 반대다.** (§38-3)
    seatCell(train, SeatClass.GENERAL, selection),
    seatCell(train, SeatClass.FIRST_CLASS, selection),
  );
  return tr;
}

function seatCell(train, seatClass, selection) {
  const td = document.createElement('td');
  const label = document.createElement('label');
  label.className = 'seat';

  const status = seatStatusOf(train, seatClass);
  const box = document.createElement('input');
  box.type = 'checkbox';
  box.checked = selectionContainsTrain(selection, train, seatClass);
  box.disabled = status === SeatStatus.UNKNOWN;
  box.title = `${train.departureTime} ${train.trainNumber} ${seatClassLabel(seatClass)}`;
  box.addEventListener('change', () => toggle(train, seatClass));

  const text = document.createElement('span');
  text.className = status === SeatStatus.AVAILABLE ? 'status available' : 'status';
  text.textContent = seatStatusLabel(status);

  label.append(box, text);
  td.append(label);
  return td;
}

/** 목록이 안 나오는 이유를 사람 말로. 원인을 모르면 모른다고 한다 (대원칙 6). */
function noticeFor({ snapshot, failure }) {
  if (failure) {
    switch (failure.reason) {
      case 'NOT_KORAIL':
        return '코레일 예매 화면에서 열어 주세요.\n' +
          '이 창은 지금 보고 있는 탭의 조회 결과를 읽습니다.';
      case 'NO_CONTENT_SCRIPT':
        return '아직 화면을 읽을 수 없습니다.\n' +
          '페이지가 다 그려진 뒤 [갱신] 을 눌러 보세요. (확장을 새로 설치했다면 탭 새로고침이 필요합니다)';
      default:
        return `화면을 읽지 못했습니다.\n${failure.error ?? failure.reason ?? ''}`;
    }
  }
  if (!snapshot) return '읽어온 화면이 없습니다.';

  switch (snapshot.status) {
    case PageStatus.TRAIN_LIST:
      return snapshot.searchDate ? `${snapshot.searchDate} 조회 결과` : null;
    case PageStatus.NO_TRAIN:
      return '조회된 열차가 없습니다. 코레일 화면에서 조건을 바꿔 다시 조회해 주세요.';
    case PageStatus.BLOCKED:
      return '접속이 차단된 화면으로 보입니다. 잠시 뒤에 다시 시도하세요.';
    default:
      return `${pageStatusLabel(snapshot.status)}.\n` +
        '코레일 화면에서 원하는 조건으로 조회한 뒤 [갱신] 을 누르세요.';
  }
}

function send(message) {
  return chrome.runtime.sendMessage(message);
}
