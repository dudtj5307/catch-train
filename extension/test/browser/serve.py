# test/browser/*.html 를 열어 볼 정적 서버.
#
# `python -m http.server` 를 그냥 쓰면 **`.mjs` 를 모듈로 안 준다** — Windows 에서
# MIME 이 `application/octet-stream` 으로 나가고 브라우저가 그 모듈을 거부한다.
# 그 한 줄 때문에 이 파일이 있다.
#
#   py test/browser/serve.py          → http://127.0.0.1:8731
#
# 실행 위치는 `extension/` 이다 (임포트 경로가 `/src/...` 라서).

import http.server
import mimetypes
import os
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8731
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

mimetypes.add_type('text/javascript', '.mjs')
mimetypes.add_type('text/javascript', '.js')


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    print(f'{ROOT} → http://127.0.0.1:{PORT}/test/browser/unit.html')
    http.server.ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
