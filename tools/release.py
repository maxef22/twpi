"""Публикация релиза аддона TWPI на GitHub.

Запуск из корня проекта:

    python tools/release.py

Что делает:
  1. берёт версию из web/info.xml;
  2. создаёт (или находит) релиз с тегом v<версия> на ветке main;
  3. прикладывает собранный dist/twpi.addon.

Токен берётся из переменной окружения GITHUB_TOKEN (или
GITHUB_PERSONAL_ACCESS_TOKEN), а если её нет — из %USERPROFILE%\\.dsh\\.env.
Нужны права Contents: write на репозиторий.

Скрипт намеренно не использует requests: только стандартная библиотека,
чтобы работать на любой машине сборки.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request

REPO = os.environ.get("TWPI_REPO", "maxef22/twpi")
ASSET = os.path.join("dist", "twpi.addon")
INFO = os.path.join("web", "info.xml")


def read_version():
    with open(INFO, encoding="utf-8") as handle:
        text = handle.read()
    match = re.search(r"<version>([^<]+)</version>", text)
    if not match:
        sys.exit("не нашёл <version> в " + INFO)
    return match.group(1).strip()


def read_token():
    for name in ("GITHUB_TOKEN", "GITHUB_PERSONAL_ACCESS_TOKEN"):
        value = os.environ.get(name)
        if value:
            return value.strip()
    env_file = os.path.join(os.path.expanduser("~"), ".dsh", ".env")
    if os.path.isfile(env_file):
        with open(env_file, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line.startswith("GITHUB_PERSONAL_ACCESS_TOKEN="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    sys.exit("нет токена: задайте GITHUB_TOKEN или положите его в ~/.dsh/.env")


TOKEN = read_token()
API = "https://api.github.com/repos/" + REPO


def call(url, method="GET", payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", "Bearer " + TOKEN)
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("User-Agent", "twpi-release")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=90) as response:
        body = response.read().decode("utf-8")
    return json.loads(body) if body else {}


def main():
    version = read_version()
    tag = "v" + version
    if not os.path.isfile(ASSET):
        sys.exit("нет файла " + ASSET + " — сначала запустите build.cmd")

    try:
        release = call(API + "/releases", "POST", {
            "tag_name": tag,
            "target_commitish": "main",
            "name": "TWPI " + version,
            "body": "Аддон TWPI для WebCTRL " + version + ". "
                    "Установочный файл — во вложениях (twpi.addon).",
            "draft": False,
            "prerelease": False
        })
        print("релиз создан:", release["html_url"])
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        if error.code != 422 or "already_exists" not in detail:
            sys.exit("не удалось создать релиз: " + detail)
        release = None
        for item in call(API + "/releases"):
            if item.get("tag_name") == tag:
                release = item
                break
        if release is None:
            sys.exit("релиз " + tag + " не найден")
        print("релиз уже есть:", release["html_url"])

    assets = [item["name"] for item in call(API + "/releases/%d/assets" % release["id"])]
    if "twpi.addon" in assets:
        print("файл уже приложен")
        return

    upload_url = release["upload_url"].split("{")[0] + "?name=twpi.addon"
    with open(ASSET, "rb") as handle:
        blob = handle.read()
    request = urllib.request.Request(upload_url, data=blob, method="POST")
    request.add_header("Authorization", "Bearer " + TOKEN)
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("User-Agent", "twpi-release")
    request.add_header("Content-Type", "application/octet-stream")
    with urllib.request.urlopen(request, timeout=180) as response:
        asset = json.loads(response.read().decode("utf-8"))
    print("приложен:", asset["name"], asset["size"], "байт")
    print("ссылка:", asset["browser_download_url"])


if __name__ == "__main__":
    main()
