# Demo corpora

One tenant per corpus. `./demo/seed.sh` downloads each corpus at a pinned revision, converts it to Markdown,
creates the tenants with their language profiles and uploads everything. Nothing downloaded is committed: it
all lands in `demo/data/`, which is gitignored.

```bash
./demo/seed.sh            # the demo tenants (needs the API and the worker running)
./demo/seed.sh --local    # also LaRA, for local evaluation only
```

The seed reads `ADMIN_TOKEN` from the repo-root `.env`. Each run creates a temporary API key per tenant and revokes
it when done. Running it again is safe: tenants are reused, and unchanged files are matched by checksum and
skipped, so nothing is embedded twice. Set `RAG_API_URL` to seed another address.

Licences were checked on 2026-11-09.

**Not fully seeded yet.** The first run on 2026-11-09 hit OpenRouter's daily free-model limit (about 50 requests a day
without purchased credits) after 56 Kubernetes pages; the rest failed with `embedding_failed`. The worker embeds per
document, so the demo needs roughly one call per document, about 550 in all. A one-time purchase of 10 OpenRouter
credits raises the limit to 1,000 free requests a day. Until then, running the seed again requeues the failed documents
and skips the ready ones.

| Tenant | Profile | Corpus | Licence | Where it may go |
|---|---|---|---|---|
| `demo-kubernetes-docs` | `en` | Kubernetes docs, the Concepts section: 187 pages | CC BY 4.0 | Anywhere, with credit |
| `demo-ragturk-wiki` | `tr` | RAGTurk, Turkish Wikipedia part: 300 of 4,891 articles | CC BY-NC-SA 4.0 | Non-commercial use, with credit |
| `local-lara-32k` | `en` | LaRA, the 32k contexts: 64 documents | None stated | **Your machine only.** Never deployed or committed; only scores are published |

## Kubernetes documentation

- **Source:** [kubernetes/website](https://github.com/kubernetes/website), `content/en/docs/concepts`, commit
  `14783102c018b7a79d99462fb6cdbd5473f157d6`. Rendered at <https://kubernetes.io/docs/concepts/>.
- **Licence:** [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), from the repository's
  [`LICENSE`](https://github.com/kubernetes/website/blob/main/LICENSE). Credit: © The Kubernetes Authors.
- **Changes we make:** Hugo front matter becomes a `#` title; shortcodes become the text they render as
  (`glossary_tooltip` → its text, `note` → "Note:", `heading "whatsnext"` → "What's next") or are dropped
  (`feature-state`, `code_sample` and the like); a `Source:` line with the page URL is added at the end.

## RAGTurk (Turkish Wikipedia part)

- **Source:** [metunlp/ragturk on Hugging Face](https://huggingface.co/datasets/metunlp/ragturk),
  `formal_5k/dataset/json`, revision `86d35335d01b30869ab8eb392e380921fa1e2185`. Code and paper:
  [metunlp/ragturk](https://github.com/metunlp/ragturk), [RAGTurk: Best Practices for Retrieval Augmented
  Generation in Turkish](https://arxiv.org/abs/2602.03652).
- **Licence:** [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/), from the dataset's licence
  tag; its dataset card is empty. The articles come from [Turkish Wikipedia](https://tr.wikipedia.org), whose text is
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
  - Non-commercial: fine for this demo; not for anything sold or run for a paying customer.
  - Share-alike: if converted files are ever published, they carry the same licence. We download at seed time
    instead.
- **Which part:** only `formal_5k` (Wikipedia); the dataset's `informal_6k` folder (the paper's CulturaX web pages)
  is empty on Hugging Face, and web pages' own copyright would be unclear anyway. Articles whose URL isn't
  `tr.wikipedia.org` are skipped.
- **Slice:** 300 articles, sampled with a fixed seed (19) so it's the same 300 every time;
  `prepare.py --ragturk-articles N` changes the number.
- **Changes we make:** the inline `== Heading ==` markers become Markdown headings, the title becomes a `#` heading,
  and a `Source:` line with the article URL is added.
- **Questions:** the dataset's 2 questions per article (600 here), with answers and the ids of the chunks that answer
  them, are saved to `demo/data/ragturk-wiki/questions.json` for the evaluation set (Phase 4).

## LaRA (local evaluation only)

- **Source:** [Alibaba-NLP/LaRA](https://github.com/Alibaba-NLP/LaRA), `datasets/32k` and the matching
  `datasets/query/32k_*.jsonl`, commit `c17155376f4e708bce3c1659facd19886d75a5eb`. Paper: [LaRA: Benchmarking
  Retrieval-Augmented Generation and Long-Context LLMs](https://arxiv.org/abs/2502.09977) (ICML 2025).
- **Licence:** the code is MIT; **the data has no stated licence**, and the README doesn't say whether MIT covers it.
  The contexts are 15 novels and stories, 19 academic papers and 30 financial filings. Most of the stories are
  public domain, but some are not (for example *The Old Man and the Sea*, *The Pearl*, *The Great Divorce*).
- **So:** it's only seeded with `--local`, into a tenant named `local-…`. Never run `--local` against a deployed
  stack, never commit its files, and don't show its text in screenshots or talks. Publishing scores on it, with a
  citation of the paper, is fine: that's what a benchmark is for.
- **Changes we make:** the `.txt` contexts are wrapped as Markdown with the file name as a `#` title, since the API
  takes Markdown, not plain text. The papers are already Markdown.
