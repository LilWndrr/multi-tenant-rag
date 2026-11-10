"""Creates one tenant per demo corpus, uploads its files and waits until every document is ready or failed.

Run demo/prepare.py first (demo/seed.sh does both). Safe to run again: existing tenants are reused, and
re-uploading an unchanged file does nothing (the API matches it by checksum).

Needs ADMIN_TOKEN in the environment (demo/seed.sh loads .env). Each run creates a temporary API key per tenant
and revokes it at the end, so no key is printed or left behind.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"

# folder in demo/data -> tenant name and language profile; local ones are never deployed (see demo/README.md)
CORPORA = {
    "kubernetes-docs": {"tenant": "demo-kubernetes-docs", "profile": "en", "local": False},
    "ragturk-wiki": {"tenant": "demo-ragturk-wiki", "profile": "tr", "local": False},
    "lara-32k": {"tenant": "local-lara-32k", "profile": "en", "local": True},
}


class Api:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def call(self, method, path, token, body=None, files=None):
        headers = {"Authorization": "Bearer " + token}
        data = None
        if files is not None:
            boundary = "----seed" + uuid.uuid4().hex
            name, content = files
            data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                    f"Content-Type: text/markdown\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")[:300]


def find_or_create_tenant(api, admin, name, profile):
    status, created = api.call("POST", "/admin/tenants", admin, {"name": name, "languageProfile": profile})
    if status == 201:
        return created["id"], True
    if status != 409:
        sys.exit(f"creating tenant {name} failed: {status} {created}")
    _, tenants = api.call("GET", "/admin/tenants", admin)
    tenant = next(t for t in tenants if t["name"] == name)
    if tenant["languageProfile"] != profile:
        sys.exit(f"tenant {name} exists with profile {tenant['languageProfile']}, expected {profile}")
    return tenant["id"], False


def all_documents(api, key):
    documents, cursor = [], None
    while True:
        path = "/v1/documents?limit=100" + (f"&cursor={cursor}" if cursor else "")
        status, page = api.call("GET", path, key)
        if status != 200:
            sys.exit(f"listing documents failed: {status} {page}")
        documents += page["items"]
        cursor = page["nextCursor"]
        if not cursor:
            return documents


SEED_KEY_PREFIX = "seed "


def revoke_leftover_seed_keys(api, admin, tenant_id):
    """Keys a run didn't get to revoke, because it was stopped or crashed."""
    _, keys = api.call("GET", f"/admin/tenants/{tenant_id}/api-keys", admin)
    leftovers = [k for k in keys or [] if k["name"].startswith(SEED_KEY_PREFIX) and not k["revokedAt"]]
    for k in leftovers:
        api.call("POST", f"/admin/tenants/{tenant_id}/api-keys/{k['id']}/revoke", admin)
    if leftovers:
        print(f"  revoked {len(leftovers)} leftover seed key(s)")


def delete_all_documents(api, key):
    documents = all_documents(api, key)
    for d in documents:
        status, body = api.call("DELETE", f"/v1/documents/{d['id']}", key)
        if status not in (204, 404):
            sys.exit(f"deleting document {d['id']} failed: {status} {body}")
    print(f"  reset: deleted {len(documents)} documents")


def seed(api, admin, folder, corpus, timeout_seconds, reset):
    files = sorted((DATA / folder / "files").glob("*.md"))
    if not files:
        print(f"{folder}: no files, run demo/prepare.py{' --local' if corpus['local'] else ''} first")
        return False
    tenant_id, created = find_or_create_tenant(api, admin, corpus["tenant"], corpus["profile"])
    print(f"{corpus['tenant']} ({corpus['profile']}): tenant {'created' if created else 'exists'}, {len(files)} files")
    revoke_leftover_seed_keys(api, admin, tenant_id)

    status, key = api.call("POST", f"/admin/tenants/{tenant_id}/api-keys", admin,
                           {"name": SEED_KEY_PREFIX + time.strftime("%Y-%m-%d %H:%M")})
    if status != 201:
        sys.exit(f"creating a seed key failed: {status} {key}")
    try:
        if reset:
            delete_all_documents(api, key["key"])
        counts = {}
        for path in files:
            status, _ = api.call("POST", "/v1/documents", key["key"], files=(path.name, path.read_bytes()))
            outcome = {202: "queued", 200: "unchanged"}.get(status, f"error {status}")
            counts[outcome] = counts.get(outcome, 0) + 1
            if status not in (200, 202):
                print(f"  {path.name}: upload failed with {status}")
        print("  uploads: " + ", ".join(f"{n} {k}" for k, n in sorted(counts.items())))
        return wait_until_done(api, key["key"], timeout_seconds)
    finally:
        api.call("POST", f"/admin/tenants/{tenant_id}/api-keys/{key['id']}/revoke", admin)


def wait_until_done(api, key, timeout_seconds):
    started, last = time.time(), None
    while True:
        documents = all_documents(api, key)
        by_status = {}
        for d in documents:
            by_status[d["status"]] = by_status.get(d["status"], 0) + 1
        summary = ", ".join(f"{n} {s}" for s, n in sorted(by_status.items()))
        if summary != last:
            print(f"  {time.time() - started:5.0f}s  {summary}")
            last = summary
        if not by_status.get("queued") and not by_status.get("processing"):
            failed = [d for d in documents if d["status"] == "failed"]
            for d in failed:
                print(f"  failed: {d['fileName']} ({d['error']})")
            return not failed
        if time.time() - started > timeout_seconds:
            print(f"  gave up after {timeout_seconds}s; the worker keeps going, run the seed again to check")
            return False
        time.sleep(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--local", action="store_true", help="also seed LaRA, for local evaluation only")
    parser.add_argument("--api", default=os.environ.get("RAG_API_URL", "http://localhost:8080"), help="API base URL")
    parser.add_argument("--timeout", type=int, default=3600, help="seconds to wait per tenant (default 3600)")
    parser.add_argument("--reset", action="store_true",
                        help="delete the tenants' documents first; needed after a LocalStack restart, which empties S3")
    args = parser.parse_args()

    admin = os.environ.get("ADMIN_TOKEN")
    if not admin:
        sys.exit("ADMIN_TOKEN is not set (demo/seed.sh reads it from .env)")
    api = Api(args.api)
    ok = True
    for folder, corpus in CORPORA.items():
        if corpus["local"] and not args.local:
            continue
        ok = seed(api, admin, folder, corpus, args.timeout, args.reset) and ok
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
