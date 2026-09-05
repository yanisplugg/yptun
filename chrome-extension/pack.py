#!/usr/bin/env python3
"""Собирает архив расширения для релиза.

    python chrome-extension/pack.py [куда]        # по умолчанию: chrome-extension/dist

Архив распаковывают и грузят через «Режим разработчика» → «Загрузить распакованное».
Установщика одним кликом нет и быть не может: Chrome с 73-й версии ставит только пакеты,
подписанные Web Store («CRX_REQUIRED_PROOF_MISSING»), локальный .crx из реестра больше не
берётся, а флаг --load-extension вырезан в Chrome 137.
"""
import io, json, os, sys, zipfile

SRC = os.path.dirname(os.path.abspath(__file__))
SKIP = {"pack.py", "link.test.mjs", "dist", ".gitignore"}


def files():
    for root, dirs, names in os.walk(SRC):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for n in names:
            path = os.path.join(root, n)
            rel = os.path.relpath(path, SRC).replace("\\", "/")
            if rel.split("/")[0] not in SKIP:
                yield path, rel


def main(out_dir):
    version = json.load(io.open(os.path.join(SRC, "manifest.json"), encoding="utf-8"))["version"]
    os.makedirs(out_dir, exist_ok=True)
    zip_path = os.path.join(out_dir, "yptun-chrome-%s.zip" % version)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for path, rel in files():
            z.write(path, rel)
    print("%s  %d bytes" % (zip_path, os.path.getsize(zip_path)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(SRC, "dist"))
