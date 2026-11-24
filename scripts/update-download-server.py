#!/usr/bin/env python3
"""HTTPS fixture for the emulator updater's real network/download/install path."""
import argparse
import http.server
import pathlib
import ssl

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("certificate")
parser.add_argument("key")
args = parser.parse_args()


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/candidate.apk":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(args.apk.stat().st_size))
        self.end_headers()
        with args.apk.open("rb") as source:
            while chunk := source.read(65536):
                self.wfile.write(chunk)


server = http.server.HTTPServer(("127.0.0.1", 38443), Handler)
tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
tls.load_cert_chain(args.certificate, args.key)
server.socket = tls.wrap_socket(server.socket, server_side=True)
server.serve_forever()
