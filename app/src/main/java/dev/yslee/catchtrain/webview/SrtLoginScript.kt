package dev.yslee.catchtrain.webview

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 지금 사이트에 **로그인되어 있는지**만 확인하는 스크립트.
 *
 * ## 왜 따로 필요한가
 *
 * SRT 는 **비로그인 상태에서도 열차 조회가 된다.** 조회 결과 표도, 표 안의
 * [예약하기] 버튼도 그대로 보인다. 로그인을 요구하는 시점은 [예약하기] 를
 * 누른 **뒤**다. (2026-08-23 실측)
 *
 * 그래서 [SrtParserScript] 가 돌려주는 `status` 만으로는 부족하다. 그쪽의
 * `LOGIN_REQUIRED` 는 "지금 화면이 로그인 화면인가"를 보는 값이라,
 * 비로그인 사용자가 조회 결과 화면에 있으면 멀쩡히 `TRAIN_LIST` 가 나온다.
 * 그 상태로 감시를 시작하면 이렇게 된다.
 *
 *   좌석이 열림 → 알림 → [예약하기] 클릭 → 로그인 화면으로 튕김 → 좌석은 남이 가져감
 *
 * 즉 **취소표가 나오는 그 순간에** 실패한다. 감시를 몇 시간 돌린 보람이
 * 통째로 날아가는 자리라, 시작 전에 미리 막는다.
 *
 * ## 판정 방법
 *
 * 머리말([SrtSelectors.LoginIndicator.HEADER_SCOPES]) 안의 **링크/버튼**만 본다.
 * 문서 전체 텍스트에서 "로그아웃"을 찾는 방법은 쓰지 않는다.
 * 로그인 화면 본문에 "로그인 후 1시간 동안 입력이 없을 경우 자동으로
 * 로그아웃됩니다." 라는 안내가 있어서, **비로그인 상태를 로그인으로
 * 잘못 읽는다.** (2026-08-23 실측)
 *
 * 링크 문구는 공백을 지운 뒤 **완전 일치**로 비교한다. 부분 일치를 쓰면
 * "간편로그인 설정" 같은 메뉴에 걸린다.
 *
 * 판정은 세 갈래다.
 *  - `LOGGED_IN`  : 로그아웃 링크가 있고 로그인 링크는 없다.
 *  - `LOGGED_OUT` : 로그인 링크가 있고 로그아웃 링크는 없다.
 *  - `UNKNOWN`    : 둘 다 없거나 둘 다 있다. (사이트 개편 / 판단 불가)
 *
 * `UNKNOWN` 일 때 **감시를 막지 않는다.** 사이트 구조가 바뀌어 마커를 놓친 것뿐인데
 * 앱이 영영 시작되지 않는 편이, 로그인 확인을 한 번 건너뛰는 것보다 나쁘다.
 * ([SrtSelectors] 의 "selector 가 틀려도 곧바로 못 쓰게 되지는 않는다" 원칙과 같다)
 *
 * 부작용이 전혀 없다. 읽기만 하고 요청도 보내지 않는다.
 *
 * 반환 형식:
 * ```json
 * {"state": "LOGGED_IN|LOGGED_OUT|UNKNOWN", "detail": "scope=.login_wrap 로그인 링크"}
 * ```
 */
object SrtLoginScript {

    fun build(): String {
        val config = buildString {
            append("{")
            append("scopes:").append(jsArray(SrtSelectors.LoginIndicator.HEADER_SCOPES)).append(",")
            append("logoutHrefs:").append(jsArray(SrtSelectors.LoginIndicator.LOGOUT_HREF_HINTS)).append(",")
            append("logoutTexts:").append(jsArray(SrtSelectors.LoginIndicator.LOGOUT_TEXTS)).append(",")
            append("loginHrefs:").append(jsArray(SrtSelectors.LoginIndicator.LOGIN_HREF_HINTS)).append(",")
            append("loginTexts:").append(jsArray(SrtSelectors.LoginIndicator.LOGIN_TEXTS))
            append("}")
        }
        return TEMPLATE.replace(CONFIG_PLACEHOLDER, config)
    }

    private fun jsArray(values: List<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    private const val CONFIG_PLACEHOLDER = "/*__CONFIG__*/"

    private val TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;

      function squash(text) {
        return (text || '').replace(/\s+/g, '').toLowerCase();
      }

      /**
       * 화면에 실제로 그려져 있는 요소인가.
       * display:none / visibility:hidden 으로 숨긴 중복 메뉴(모바일용 등)를 걸러낸다.
       * 스크롤로 화면 밖에 있는 것은 여기서 걸러지지 않는다. 그래도 된다.
       */
      function isShown(el) {
        if (!el) return false;
        if (!el.getClientRects || el.getClientRects().length === 0) {
          if (!el.offsetParent) return false;
        }
        try {
          var style = window.getComputedStyle(el);
          if (style && (style.visibility === 'hidden' || style.display === 'none')) return false;
        } catch (e) { /* 계산 실패는 보이는 것으로 본다 */ }
        return true;
      }

      function attrOf(el) {
        var href = '';
        var click = '';
        try { href = el.getAttribute('href') || ''; } catch (e) { href = ''; }
        try { click = el.getAttribute('onclick') || ''; } catch (e) { click = ''; }
        return (href + ' ' + click).toLowerCase();
      }

      function textOf(el) {
        var text = '';
        try { text = el.innerText || el.textContent || ''; } catch (e) { text = ''; }
        if (!text && el.value) text = el.value;
        return squash(text);
      }

      function hrefHits(el, hints) {
        var attr = attrOf(el);
        if (!attr) return false;
        for (var i = 0; i < hints.length; i++) {
          if (attr.indexOf(hints[i].toLowerCase()) >= 0) return true;
        }
        return false;
      }

      /** 문구 비교는 완전 일치다. 안내 문장에 걸리지 않게 하기 위한 것이다. */
      function textHits(el, texts) {
        var value = textOf(el);
        if (!value) return false;
        for (var i = 0; i < texts.length; i++) {
          if (value === squash(texts[i])) return true;
        }
        return false;
      }

      function clickablesIn(root) {
        var found = [];
        try {
          var nodes = root.querySelectorAll('a, button, input[type=submit], input[type=button]');
          for (var i = 0; i < nodes.length; i++) found.push(nodes[i]);
        } catch (e) { /* 무시 */ }
        return found;
      }

      function describe(el) {
        var text = textOf(el);
        var href = '';
        try { href = (el.getAttribute('href') || '').slice(-40); } catch (e) { href = ''; }
        return (text || '(문구없음)') + (href ? ' href=' + href : '');
      }

      // 머리말 영역을 앞에서부터 찾는다. 하나도 없으면 문서 전체를 본다.
      var scopeName = null;
      var scopes = [];
      for (var s = 0; s < CFG.scopes.length && scopes.length === 0; s++) {
        try {
          var nodes = document.querySelectorAll(CFG.scopes[s]);
          for (var n = 0; n < nodes.length; n++) {
            if (isShown(nodes[n])) scopes.push(nodes[n]);
          }
          if (scopes.length > 0) scopeName = CFG.scopes[s];
        } catch (e) { /* 잘못된 selector 무시 */ }
      }
      if (scopes.length === 0) {
        scopes = [document.body];
        scopeName = 'body';
      }

      var logout = null;
      var login = null;
      var scanned = 0;

      for (var i = 0; i < scopes.length; i++) {
        var items = clickablesIn(scopes[i]);
        for (var j = 0; j < items.length; j++) {
          var el = items[j];
          if (!isShown(el)) continue;
          scanned++;
          if (!logout && (hrefHits(el, CFG.logoutHrefs) || textHits(el, CFG.logoutTexts))) {
            logout = el;
          }
          if (!login && (hrefHits(el, CFG.loginHrefs) || textHits(el, CFG.loginTexts))) {
            login = el;
          }
        }
      }

      var state;
      var detail;
      if (logout && !login) {
        state = 'LOGGED_IN';
        detail = '로그아웃 ' + describe(logout);
      } else if (login && !logout) {
        state = 'LOGGED_OUT';
        detail = '로그인 ' + describe(login);
      } else if (login && logout) {
        // 둘 다 보이면 우리가 읽는 방식이 이 화면에 맞지 않는다는 뜻이다. 막지 않는다.
        state = 'UNKNOWN';
        detail = '로그인/로그아웃 둘 다 있음';
      } else {
        state = 'UNKNOWN';
        detail = '표시 없음';
      }

      return {
        state: state,
        detail: detail + ' / scope=' + scopeName + ' 요소' + scanned + '개',
        url: location.href
      };
    })();
    """.trimIndent()
}

/** [SrtLoginScript] 결과를 읽는다. 스크립트 실행 실패나 형식 오류는 모두 UNKNOWN 이다. */
object SrtLoginParser {

    fun parse(rawJson: String?): LoginCheck {
        val raw = rawJson?.trim()
        if (raw.isNullOrEmpty() || raw == "null" || raw == "undefined") {
            return LoginCheck(LoginState.UNKNOWN, "스크립트 실행 실패")
        }
        // evaluateJavascript 결과는 객체 그대로일 수도, 한 번 더 문자열로 감싸여 올 수도 있다.
        val root = runCatching {
            when (val value = JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                is String -> JSONObject(value)
                else -> null
            }
        }.getOrNull() ?: return LoginCheck(LoginState.UNKNOWN, "결과 형식 오류")

        val state = runCatching { LoginState.valueOf(root.optString("state")) }
            .getOrElse { LoginState.UNKNOWN }
        return LoginCheck(state, root.optString("detail"))
    }
}

/** 사이트 로그인 여부. */
enum class LoginState {
    LOGGED_IN,

    /** 로그인하지 않았다고 **확신**할 때만 쓴다. 이 값일 때만 감시 시작을 막는다. */
    LOGGED_OUT,

    /** 판단하지 못했다. 감시를 막지 않는다. */
    UNKNOWN,
}

data class LoginCheck(
    val state: LoginState,
    val detail: String = "",
) {
    /** 감시를 시작해도 되는가. 확실히 로그아웃일 때만 막는다. */
    val blocksWatch: Boolean get() = state == LoginState.LOGGED_OUT
}
