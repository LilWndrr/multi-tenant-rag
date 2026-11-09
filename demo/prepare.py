"""Downloads the demo corpora and converts them into files the API accepts (Markdown).

Nothing downloaded here goes in git: everything lands in demo/data/ (gitignored). Each corpus is pinned to an
exact upstream revision, so the files, and later the evaluation's expected passages, stay the same.
See demo/README.md for the sources and their licences.

    python demo/prepare.py            # the demo corpora: Kubernetes docs and the RAGTurk Wikipedia slice
    python demo/prepare.py --local    # also LaRA, for local evaluation only (never deployed)

Standard library only, so it runs anywhere Python 3.9+ does. Re-running reuses what is already downloaded.
"""

import argparse
import json
import random
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"

KUBERNETES_COMMIT = "14783102c018b7a79d99462fb6cdbd5473f157d6"
KUBERNETES_SECTION = "content/en/docs/concepts"

RAGTURK_REVISION = "86d35335d01b30869ab8eb392e380921fa1e2185"
RAGTURK_FOLDER = "formal_5k/dataset/json"
RAGTURK_SAMPLE_SEED = 19   # fixed, so the same articles are picked every time

LARA_COMMIT = "c17155376f4e708bce3c1659facd19886d75a5eb"
LARA_TYPES = ("book", "paper", "financial")


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "rag-platform-demo-seed"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read(), response.headers


def fetch_json(url):
    body, headers = fetch(url)
    return json.loads(body), headers


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def fresh_dir(path):
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)
    return path


# ---------------------------------------------------------------- Kubernetes docs (CC BY 4.0)

# {{% heading "whatsnext" %}} and friends are Hugo i18n keys; these are the English strings they render to
HUGO_HEADINGS = {
    "whatsnext": "What's next", "prerequisites": "Before you begin", "objectives": "Objectives",
    "cleanup": "Clean up", "synopsis": "Synopsis", "options": "Options", "seealso": "See also",
}


def hugo_to_markdown(text):
    """Turns Hugo shortcodes into the plain text they render as; anything unknown is dropped."""
    def tooltip(match):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', match.group(1)))
        return attrs.get("text") or attrs.get("term_id", "").replace("-", " ")

    text = re.sub(r"\{\{[<%]\s*glossary_tooltip\b(.*?)[%>]\}\}", tooltip, text, flags=re.S)
    text = re.sub(r'\{\{%\s*heading\s+"(\w+)"\s*%\}\}', lambda m: HUGO_HEADINGS.get(m.group(1), m.group(1)), text)
    text = re.sub(r"\{\{[<%]\s*(note|caution|warning)\b[^}]*[%>]\}\}", lambda m: m.group(1).capitalize() + ": ", text)
    text = re.sub(r'\{\{[<%]\s*tab\s+name="([^"]*)"[^}]*[%>]\}\}', r"\1: ", text)
    text = re.sub(r"\{\{[<%].*?[%>]\}\}", "", text, flags=re.S)   # closing tags, feature-state, code_sample, ...
    return re.sub(r"\n{3,}", "\n\n", text).strip() + "\n"


def front_matter(text):
    match = re.match(r"^---\s*\n(.*?)\n---\s*\n", text, flags=re.S)
    if not match:
        return {}, text
    fields = {}
    for line in match.group(1).splitlines():
        key, sep, value = line.partition(":")
        if sep and not line.startswith((" ", "\t", "-")):
            fields[key.strip()] = value.strip().strip("'\"")
    return fields, text[match.end():]


def prepare_kubernetes():
    out = DATA / "kubernetes-docs"
    checkout = DATA / "cache" / "kubernetes-website"
    if not (checkout / KUBERNETES_SECTION).exists():
        fresh_dir(checkout)
        git = ["git", "-C", str(checkout)]
        subprocess.run(git + ["init", "-q"], check=True)
        subprocess.run(git + ["remote", "add", "origin", "https://github.com/kubernetes/website.git"], check=True)
        subprocess.run(git + ["sparse-checkout", "set", KUBERNETES_SECTION], check=True)
        subprocess.run(git + ["fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", KUBERNETES_COMMIT], check=True)
        subprocess.run(git + ["checkout", "-q", "FETCH_HEAD"], check=True)

    files = fresh_dir(out / "files")
    section = checkout / KUBERNETES_SECTION
    count = 0
    for source in sorted(section.rglob("*.md")):
        relative = source.relative_to(section).with_suffix("")
        parts = [p for p in relative.parts if p != "_index"] or ["index"]
        fields, body = front_matter(source.read_text(encoding="utf-8"))
        title = fields.get("title") or parts[-1].replace("-", " ").capitalize()
        url = "https://kubernetes.io/docs/concepts/" + "/".join(p for p in relative.parts if p != "_index") + "/"
        text = f"# {title}\n\n{hugo_to_markdown(body)}\nSource: {url}\n"
        write(files / ("concepts-" + "-".join(parts) + ".md"), text)
        count += 1
    print(f"kubernetes-docs: {count} files")


# ---------------------------------------------------------------- RAGTurk, Turkish Wikipedia part (CC BY-NC-SA 4.0)

def ragturk_file_names():
    """Every file in the folder; the Hugging Face tree API pages by 1000 through the Link header."""
    folder = urllib.parse.quote(RAGTURK_FOLDER, safe="")
    url = f"https://huggingface.co/api/datasets/metunlp/ragturk/tree/{RAGTURK_REVISION}/{folder}?limit=1000"
    names = []
    while url:
        entries, headers = fetch_json(url)
        names += [e["path"] for e in entries if e["type"] == "file" and e["path"].endswith(".json")]
        link = re.search(r'<([^>]+)>;\s*rel="next"', headers.get("Link", ""))
        url = link.group(1) if link else None
    return sorted(names)


def wiki_headings_to_markdown(content):
    """The article text keeps Wikipedia's inline "== Heading ==" markers; make them Markdown headings."""
    def heading(match):
        return "\n\n" + "#" * len(match.group(1)) + " " + match.group(2).strip() + "\n\n"
    return re.sub(r"\s*(={2,6})\s*([^=\n]+?)\s*\1\s*", heading, content).strip()


def prepare_ragturk(sample_size):
    out = DATA / "ragturk-wiki"
    cache = DATA / "cache" / "ragturk"
    names = random.Random(RAGTURK_SAMPLE_SEED).sample(ragturk_file_names(), sample_size)

    files = fresh_dir(out / "files")
    questions = []
    for name in sorted(names):
        cached = cache / Path(name).name
        if not cached.exists():
            body, _ = fetch(f"https://huggingface.co/datasets/metunlp/ragturk/resolve/{RAGTURK_REVISION}/{name}")
            cached.parent.mkdir(parents=True, exist_ok=True)
            cached.write_bytes(body)
        record = json.loads(cached.read_text(encoding="utf-8"))
        article = record["article"]
        if urllib.parse.urlparse(article["url"]).hostname != "tr.wikipedia.org":
            continue   # the demo only takes the Wikipedia part (see demo/README.md)
        file_name = Path(name).stem + ".md"
        text = f"# {article['title']}\n\n{wiki_headings_to_markdown(article['content'])}\n\nSource: {article['url']}\n"
        write(files / file_name, text)
        for item in record["questions"]["items"]:
            questions.append({"file": file_name, "question": item["question"], "answer": item["answer"],
                              "category": item["category"], "related_chunk_ids": item["related_chunk_ids"]})

    write(out / "questions.json", json.dumps(questions, ensure_ascii=False, indent=1))
    print(f"ragturk-wiki: {len(list(files.iterdir()))} files, {len(questions)} questions")


# ---------------------------------------------------------------- LaRA 32k (no data licence: local evaluation only)

def prepare_lara():
    out = DATA / "lara-32k"
    raw = f"https://raw.githubusercontent.com/Alibaba-NLP/LaRA/{LARA_COMMIT}/datasets"
    files = fresh_dir(out / "files")
    for kind in LARA_TYPES:
        listing, _ = fetch_json(f"https://api.github.com/repos/Alibaba-NLP/LaRA/contents/datasets/32k/{kind}?ref={LARA_COMMIT}")
        for entry in listing:
            body, _ = fetch(f"{raw}/32k/{kind}/{urllib.parse.quote(entry['name'])}")
            text = body.decode("utf-8", errors="replace")
            stem = Path(entry["name"]).stem
            if entry["name"].endswith(".txt"):   # the API takes Markdown, not plain text
                text = f"# {stem}\n\n{text.strip()}\n"
            write(files / f"{kind}-{re.sub(r'[^A-Za-z0-9._-]+', '_', stem)}.md", text)

    queries = fresh_dir(out / "queries")
    listing, _ = fetch_json(f"https://api.github.com/repos/Alibaba-NLP/LaRA/contents/datasets/query?ref={LARA_COMMIT}")
    for entry in listing:
        if entry["name"].startswith("32k_"):
            body, _ = fetch(f"{raw}/query/{entry['name']}")
            (queries / entry["name"]).write_bytes(body)
    print(f"lara-32k: {len(list(files.iterdir()))} files, {len(list(queries.iterdir()))} query files")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--local", action="store_true", help="also prepare LaRA, for local evaluation only")
    parser.add_argument("--ragturk-articles", type=int, default=300, help="RAGTurk articles to sample (default 300)")
    args = parser.parse_args()

    prepare_kubernetes()
    prepare_ragturk(args.ragturk_articles)
    if args.local:
        prepare_lara()


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError) as e:
        sys.exit(f"prepare failed: {e}")
