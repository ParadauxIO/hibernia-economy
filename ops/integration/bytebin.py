#!/usr/bin/env python3
"""Disposable loopback Bytebin boundary for live Treasury export acceptance."""
import argparse
import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Lock


class CaptureServer(ThreadingHTTPServer):
    def __init__(self, port, evidence):
        super().__init__(('127.0.0.1', port), Handler)
        self.evidence = evidence
        self.captures = []
        self.next_key = 1
        self.response_status = 201
        self.lock = Lock()


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body, content_type='application/json'):
        data = body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        with self.server.lock:
            if self.path == '/__captures':
                self.reply(200, json.dumps(self.server.captures))
                return
            item = next((c for c in self.server.captures if self.path == '/' + c['key'] and c['status'] == 201), None)
            if item:
                self.reply(200, item['content'], 'text/csv')
            else:
                self.reply(404, '{}')

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        with self.server.lock:
            if self.path == '/__reset':
                self.server.captures.clear()
                self.server.response_status = 201
                self.reply(200, '{}')
                return
            if self.path == '/__mode':
                try:
                    status = json.loads(raw)['status']
                    if status not in (201, 503):
                        raise ValueError('unsupported response status')
                except (ValueError, KeyError, TypeError):
                    self.reply(400, '{}')
                    return
                self.server.response_status = status
                self.reply(200, '{}')
                return
            if self.path != '/post':
                self.reply(404, '{}')
                return
            try:
                content = gzip.decompress(raw).decode('utf-8') if self.headers.get('Content-Encoding') == 'gzip' else raw.decode('utf-8')
            except (OSError, EOFError, UnicodeError):
                self.reply(400, '{}')
                return
            key = 'it-' + str(self.server.next_key)
            self.server.next_key += 1
            captured = dict(key=key, status=self.server.response_status,
                            headers=dict(self.headers), content=content)
            self.server.captures.append(captured)
            with self.server.evidence.open('a') as evidence:
                evidence.write(json.dumps(captured) + '\n')
            self.reply(captured['status'], json.dumps(dict(key=key)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', required=True, type=int)
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    with CaptureServer(args.port, args.evidence) as server:
        server.serve_forever()
