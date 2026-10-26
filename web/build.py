#!/usr/bin/env python3
"""Arma web/index.html: una sola página con las listas de debloater/lists y el código incrustados.

Uso: python3 web/build.py [--fragment SALIDA]
  --fragment  escribe además la página sin <html>/<head>/<body> (para publicarla como Artifact).
"""
import json
import pathlib
import sys

WEB = pathlib.Path(__file__).resolve().parent
LISTS = WEB.parent / "debloater" / "lists"


def script(code):
    return "<script>\n" + code.replace("</script", "<\\/script") + "\n</script>"


def fragment():
    lists = {p.name: p.read_text(encoding="utf-8") for p in sorted(LISTS.glob("*.txt"))}
    page = (WEB / "index.src.html").read_text(encoding="utf-8")
    page = page.replace("<!-- LISTS -->", script("window.DEBLOAT_LISTS = " + json.dumps(lists, ensure_ascii=False) + ";"))
    page = page.replace("<!-- ADB -->", script((WEB / "adb.js").read_text(encoding="utf-8")))
    page = page.replace("<!-- DEBLOAT -->", script((WEB / "debloat.js").read_text(encoding="utf-8")))
    return page


def main():
    body = fragment()
    head, _, rest = body.partition("</style>")
    full = ("<!doctype html>\n<html lang=\"es\">\n<head>\n<meta charset=\"utf-8\">\n"
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
            + head + "</style>\n</head>\n<body>\n" + rest.lstrip("\n") + "</body>\n</html>\n")
    (WEB / "index.html").write_text(full, encoding="utf-8")
    print("web/index.html:", len(full.encode()), "bytes")
    if len(sys.argv) > 2 and sys.argv[1] == "--fragment":
        pathlib.Path(sys.argv[2]).write_text(body, encoding="utf-8")
        print(sys.argv[2] + ":", len(body.encode()), "bytes")


if __name__ == "__main__":
    main()
