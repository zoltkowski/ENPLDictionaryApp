#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
EPUB -> potencjalne brakujące słowa względem słownika StarDict EN-PL.

Wersja 3:
- znacznie lepsze filtrowanie nazw własnych dzięki zachowaniu informacji
  o wielkości liter i pozycji w zdaniu;
- osobny plik z odrzuconymi prawdopodobnymi nazwami własnymi;
- nie zapisuje nazw książek, tylko liczbę książek, w których wystąpiło słowo;
- wybór największego/najnowszego słownika spośród kilku .idx/ZIP-ów;
- raportuje osobno liczbę wszystkich wpisów i unikalnych form case-insensitive.

Użycie:
    python find_missing_words_v3.py

Pliki wynikowe:
    missing_words_shortlist.txt
    missing_words_candidates.tsv
    missing_words_rejected_proper_names.tsv
    missing_words_summary.txt

Skrypt używa wyłącznie standardowej biblioteki Pythona.
"""

from __future__ import annotations

import csv
import html
import re
import sys
import zipfile
from collections import Counter, defaultdict
from html.parser import HTMLParser
from pathlib import Path


# -------------------- USTAWIENIA --------------------

MIN_TOTAL_COUNT = 2
MIN_BOOK_COUNT = 2
MIN_WORD_LEN = 3
MAX_CONTEXTS = 3
SHORTLIST_LIMIT = 500

# Jak agresywnie odrzucać nazwy własne.
# Jeżeli słowo NIGDY nie wystąpiło małą literą i pojawia się jako Title Case
# poza początkiem zdania, traktujemy je jako nazwę własną.
DROP_TITLECASE_ONLY_MID_SENTENCE = True

# Akronimy pisane wyłącznie wielkimi literami mogą być przydatnymi hasłami.
# Zostawiamy je dopiero, gdy powtarzają się dostatecznie często.
ACRONYM_MIN_COUNT = 3
ACRONYM_MIN_BOOKS = 2
ACRONYM_MAX_LEN = 10


# -------------------- HTML / EPUB --------------------

class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        if tag in {"script", "style", "svg", "math"}:
            self._skip += 1
        elif tag in {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag in {"script", "style", "svg", "math"} and self._skip:
            self._skip -= 1
        elif tag in {"p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self._skip:
            self.parts.append(data)

    def text(self):
        return html.unescape(" ".join(self.parts))


def epub_text(path: Path) -> str:
    chunks = []
    with zipfile.ZipFile(path) as z:
        names = [
            n for n in z.namelist()
            if n.lower().endswith((".xhtml", ".html", ".htm"))
        ]
        for name in names:
            try:
                raw = z.read(name)
                text = raw.decode("utf-8", errors="replace")
                p = TextExtractor()
                p.feed(text)
                chunks.append(p.text())
            except Exception:
                continue
    return "\n".join(chunks)


# -------------------- NORMALIZACJA --------------------

APOSTROPHES = "’‘`´"
DASHES = "‐‑‒–—−"

def normalize_punctuation(s: str) -> str:
    for c in APOSTROPHES:
        s = s.replace(c, "'")
    for c in DASHES:
        s = s.replace(c, "-")
    return s


def normalize_word(w: str) -> str:
    w = normalize_punctuation(w).strip().lower()
    if w.endswith("'s") and len(w) > 3:
        w = w[:-2]
    return w


# -------------------- STARDICT --------------------

def parse_idx_bytes(idx: bytes) -> tuple[set[str], int]:
    words = set()
    total_entries = 0
    i = 0
    n = len(idx)

    while i < n:
        try:
            j = idx.index(b"\0", i)
        except ValueError:
            break

        raw = idx[i:j]
        try:
            word = raw.decode("utf-8").strip()
        except UnicodeDecodeError:
            word = raw.decode("utf-8", errors="ignore").strip()

        if word:
            total_entries += 1
            words.add(normalize_word(word))

        # NUL + offset uint32 + size uint32
        if j + 9 > n:
            break
        i = j + 9

    return words, total_entries


def version_number(name: str) -> int:
    m = re.search(r"(?:^|[-_])v(\d+)(?:\D|$)", name.lower())
    return int(m.group(1)) if m else -1


def find_dictionary_words(folder: Path) -> tuple[set[str], int, str]:
    """
    Jeżeli w folderze jest kilka słowników, nie bierzemy pierwszego
    alfabetycznie. Odczytujemy kandydatów i wybieramy ten z największą
    liczbą wpisów; przy remisie wyższą wersję vNN.
    """
    candidates = []

    # Rozpakowane IDX-y.
    for p in folder.glob("*.idx"):
        try:
            words, total = parse_idx_bytes(p.read_bytes())
            candidates.append((total, version_number(p.name), words, p.name))
        except Exception:
            pass

    # ZIP-y.
    for zp in folder.glob("*.zip"):
        try:
            with zipfile.ZipFile(zp) as z:
                idx_names = [n for n in z.namelist() if n.lower().endswith(".idx")]
                for idx_name in idx_names:
                    # Jeżeli ZIP ma wiele słowników, preferuj EN-PL/ENPL.
                    low = idx_name.lower()
                    if len(idx_names) > 1 and not ("en-pl" in low or "enpl" in low):
                        continue
                    words, total = parse_idx_bytes(z.read(idx_name))
                    candidates.append(
                        (
                            total,
                            version_number(zp.name),
                            words,
                            f"{zp.name}:{idx_name}",
                        )
                    )
        except (zipfile.BadZipFile, OSError):
            continue

    if not candidates:
        raise FileNotFoundError(
            "Nie znalazłam słownika. Umieść w folderze plik .idx albo ZIP "
            "zawierający .idx/.dict/.ifo."
        )

    # Najwięcej wpisów, potem najwyższe vNN.
    total, ver, words, source = max(candidates, key=lambda x: (x[0], x[1]))
    return words, total, source


# -------------------- TOKENIZACJA --------------------

WORD_RE = re.compile(r"[A-Za-z]+(?:['-][A-Za-z]+)*")

CONTRACTIONS = {
    "ain't", "aren't", "can't", "couldn't", "didn't", "doesn't", "don't",
    "hadn't", "hasn't", "haven't", "he'd", "he'll", "he's", "i'd", "i'll",
    "i'm", "i've", "isn't", "it'd", "it'll", "it's", "let's", "mightn't",
    "mustn't", "shan't", "she'd", "she'll", "she's", "shouldn't", "that's",
    "there's", "they'd", "they'll", "they're", "they've", "wasn't",
    "we'd", "we'll", "we're", "we've", "weren't", "what's", "where's",
    "who's", "won't", "wouldn't", "you'd", "you'll", "you're", "you've",
}

EPUB_NOISE = {
    "epub", "xhtml", "html", "isbn", "http", "https", "www", "com", "org",
    "copyright", "contents", "chapter", "chapters",
    "publisher", "publishers", "edition", "editions",
}


def sentenceish_context(text: str, start: int, end: int, radius: int = 90) -> str:
    a = max(0, start - radius)
    b = min(len(text), end + radius)
    return re.sub(r"\s+", " ", text[a:b]).strip()


def starts_sentence(separator: str, first_token: bool) -> bool:
    """
    Czy token stoi na początku zdania/akapitu?

    Patrzymy na tekst pomiędzy poprzednim tokenem a bieżącym:
    - początek dokumentu,
    - znak końca zdania . ! ? + opcjonalny cudzysłów/nawias,
    - nowy akapit.
    """
    if first_token:
        return True
    if "\n" in separator:
        return True
    return bool(re.search(r'[.!?]["\'’”)\]]*\s*$', separator))


def casing_kind(raw: str) -> str:
    letters = "".join(c for c in raw if c.isalpha())
    if not letters:
        return "other"
    if letters.isupper():
        return "upper"
    if letters.islower():
        return "lower"
    if letters[:1].isupper() and letters[1:].islower():
        return "title"
    return "mixed"


# -------------------- MORFOLOGIA --------------------

def morphology_bases(word: str) -> list[tuple[str, str]]:
    out = []

    def add(base, kind):
        if len(base) >= 2:
            out.append((base, kind))

    if word.endswith("ies") and len(word) > 4:
        add(word[:-3] + "y", "-ies")
    if word.endswith("es") and len(word) > 3:
        add(word[:-2], "-es")
        add(word[:-1], "-s")
    elif word.endswith("s") and not word.endswith("ss") and len(word) > 3:
        add(word[:-1], "-s")

    if word.endswith("ing") and len(word) > 5:
        stem = word[:-3]
        add(stem, "-ing")
        add(stem + "e", "-ing (drop-e)")
        if len(stem) >= 3 and stem[-1] == stem[-2]:
            add(stem[:-1], "-ing (double consonant)")

    if word.endswith("ied") and len(word) > 4:
        add(word[:-3] + "y", "-ied")
    if word.endswith("ed") and len(word) > 4:
        stem = word[:-2]
        add(stem, "-ed")
        add(stem + "e", "-ed (final-e)")
        if len(stem) >= 3 and stem[-1] == stem[-2]:
            add(stem[:-1], "-ed (double consonant)")

    if word.endswith("iest") and len(word) > 5:
        add(word[:-4] + "y", "-iest")
    elif word.endswith("ier") and len(word) > 4:
        add(word[:-3] + "y", "-ier")

    if word.endswith("est") and len(word) > 5:
        stem = word[:-3]
        add(stem, "-est")
        add(stem + "e", "-est")
        if len(stem) >= 3 and stem[-1] == stem[-2]:
            add(stem[:-1], "-est (double consonant)")

    if word.endswith("er") and len(word) > 4:
        stem = word[:-2]
        add(stem, "-er")
        add(stem + "e", "-er")
        if len(stem) >= 3 and stem[-1] == stem[-2]:
            add(stem[:-1], "-er (double consonant)")

    if word.endswith("ly") and len(word) > 4:
        add(word[:-2], "-ly")
        if word.endswith("ily") and len(word) > 5:
            add(word[:-3] + "y", "-ily")

    if word.endswith("ness") and len(word) > 6:
        add(word[:-4], "-ness")
    if word.endswith("ment") and len(word) > 6:
        add(word[:-4], "-ment")

    # dodatkowe częste rodziny przymiotnikowe/rzeczownikowe
    if word.endswith("al") and len(word) > 5:
        add(word[:-2], "-al")
    if word.endswith("ic") and len(word) > 5:
        add(word[:-2], "-ic")
    if word.endswith("ical") and len(word) > 7:
        add(word[:-4], "-ical")
    if word.endswith("ity") and len(word) > 6:
        add(word[:-3], "-ity")
    if word.endswith("able") and len(word) > 6:
        add(word[:-4], "-able")
        add(word[:-4] + "e", "-able")

    seen = set()
    result = []
    for x in out:
        if x not in seen:
            seen.add(x)
            result.append(x)
    return result


def classify(word: str, dict_words: set[str], all_caps_only: bool = False) -> tuple[str, str]:
    if all_caps_only:
        return "ACRONYM", "all-caps form"

    for base, kind in morphology_bases(word):
        if base in dict_words:
            return "MORPHOLOGY", f"{kind} of {base}"

    for p in ("un", "in", "im", "il", "ir", "non", "dis"):
        if word.startswith(p) and len(word) > len(p) + 3:
            base = word[len(p):]
            if base in dict_words:
                return "PREFIX", f"{p}- + {base}"

    if "-" in word:
        return "COMPOUND", "hyphenated/compound word"

    return "LEXICAL", ""


# -------------------- FILTR NAZW WŁASNYCH --------------------

def proper_name_reason(
    word: str,
    count: int,
    books_count: int,
    lower_count: int,
    title_count: int,
    title_mid_count: int,
    title_start_count: int,
    upper_count: int,
    mixed_count: int,
) -> str | None:
    """
    Zwraca powód odrzucenia jako prawdopodobna nazwa własna albo None.

    Najważniejsza zmiana względem v2:
    nie wystarczy już "częste i w wielu książkach", by uratować Robert/York/etc.
    Jeśli forma nigdy nie występuje małą literą, a pojawia się Title Case
    wewnątrz zdań, traktujemy ją jako nazwę własną niezależnie od częstości.
    """

    # Normalne użycie małą literą jest mocnym dowodem, że to zwykły wyraz.
    if lower_count > 0:
        return None

    # Wewnętrzne CamelCase / iPhone / McSomething: zwykle nazwa/marka.
    if mixed_count > 0 and title_count == 0 and upper_count == 0:
        return "mixed-case only; likely proper name/brand"

    # Typowy John/London/Harvard: Title Case poza początkiem zdania.
    if DROP_TITLECASE_ONLY_MID_SENTENCE and title_mid_count > 0:
        return (
            f"title-case only; {title_mid_count}/{title_count} title-case "
            "occurrences are mid-sentence"
        )

    # Jeśli występuje tylko Title Case, ale wyłącznie na początku zdań,
    # nadal częściej jest nazwą/tytułem niż brakującym wyrazem.
    if title_count > 0 and upper_count == 0:
        return "title-case only; no lowercase occurrences"

    # All-caps: nie odrzucamy automatycznie, bo to może być sensowny akronim.
    if upper_count == count:
        if (
            2 <= len(word) <= ACRONYM_MAX_LEN
            and count >= ACRONYM_MIN_COUNT
            and books_count >= ACRONYM_MIN_BOOKS
        ):
            return None
        return "all-caps only but too rare/long for useful acronym candidate"

    # Mieszanka Title Case + ALL CAPS, ale nigdy lowercase.
    if title_count + upper_count + mixed_count == count:
        return "capitalized only; no lowercase occurrences"

    return None


# -------------------- GŁÓWNY PROGRAM --------------------

def main():
    folder = Path(__file__).resolve().parent

    epub_paths = sorted(folder.glob("*.epub"))
    if not epub_paths:
        print("Brak plików EPUB w folderze.")
        sys.exit(1)

    try:
        dict_words, dict_entry_count, dict_source = find_dictionary_words(folder)
    except Exception as e:
        print(f"Błąd słownika: {e}")
        sys.exit(1)

    print(f"Słownik: {dict_source}")
    print(f"Haseł w indeksie (wszystkie wpisy): {dict_entry_count:,}")
    print(f"Unikalnych form do porównania (bez wielkości liter): {len(dict_words):,}")
    print(f"EPUB-y: {len(epub_paths)}")

    total = Counter()
    books = defaultdict(set)

    lower_seen = Counter()
    upper_seen = Counter()
    title_seen = Counter()
    title_mid_seen = Counter()
    title_start_seen = Counter()
    mixed_seen = Counter()

    contexts = defaultdict(list)
    total_tokens = 0

    for ep in epub_paths:
        print(f"  czytam: {ep.name}")
        text = normalize_punctuation(epub_text(ep))
        book_seen = set()

        prev_end = 0
        first_token = True

        for m in WORD_RE.finditer(text):
            raw = m.group(0)
            w = normalize_word(raw)

            separator = text[prev_end:m.start()]
            at_sentence_start = starts_sentence(separator, first_token)
            prev_end = m.end()
            first_token = False

            if not w or len(w) < MIN_WORD_LEN:
                continue
            if w in EPUB_NOISE or w in CONTRACTIONS:
                continue
            if not w.isalpha() and not re.fullmatch(r"[a-z]+(?:['-][a-z]+)*", w):
                continue
            if w.count("'") > 1 or w.count("-") > 3:
                continue

            total[w] += 1
            total_tokens += 1
            book_seen.add(w)

            ck = casing_kind(raw)
            if ck == "lower":
                lower_seen[w] += 1
            elif ck == "upper":
                upper_seen[w] += 1
            elif ck == "title":
                title_seen[w] += 1
                if at_sentence_start:
                    title_start_seen[w] += 1
                else:
                    title_mid_seen[w] += 1
            else:
                mixed_seen[w] += 1

            if len(contexts[w]) < MAX_CONTEXTS:
                contexts[w].append(sentenceish_context(text, m.start(), m.end()))

        for w in book_seen:
            books[w].add(ep.name)

    candidates = []
    rejected_names = []

    for w, cnt in total.items():
        if w in dict_words:
            continue

        nbooks = len(books[w])
        if cnt < MIN_TOTAL_COUNT and nbooks < MIN_BOOK_COUNT:
            continue

        reason = proper_name_reason(
            w,
            cnt,
            nbooks,
            lower_seen[w],
            title_seen[w],
            title_mid_seen[w],
            title_start_seen[w],
            upper_seen[w],
            mixed_seen[w],
        )

        base_row = {
            "word": w,
            "count": cnt,
            "books": nbooks,
            "lower": lower_seen[w],
            "title": title_seen[w],
            "title_mid": title_mid_seen[w],
            "title_start": title_start_seen[w],
            "upper": upper_seen[w],
            "mixed": mixed_seen[w],
            "contexts": " || ".join(contexts[w]),
        }

        if reason:
            base_row["reason"] = reason
            rejected_names.append(base_row)
            continue

        all_caps_only = upper_seen[w] == cnt and cnt > 0
        kind, note = classify(w, dict_words, all_caps_only=all_caps_only)

        type_bonus = {
            "LEXICAL": 12,
            "PREFIX": 10,
            "MORPHOLOGY": 8,
            "ACRONYM": 6,
            "COMPOUND": 4,
        }.get(kind, 0)

        # Bonus za realne użycia lowercase.
        lowercase_bonus = min(lower_seen[w], 20)
        score = nbooks * 100 + min(cnt, 100) + type_bonus + lowercase_bonus

        base_row.update({
            "type": kind,
            "note": note,
            "score": score,
        })
        candidates.append(base_row)

    candidates.sort(
        key=lambda x: (-x["books"], -x["score"], -x["count"], x["word"])
    )
    rejected_names.sort(
        key=lambda x: (-x["books"], -x["count"], x["word"])
    )

    # Pełni kandydaci.
    tsv_path = folder / "missing_words_candidates.tsv"
    with tsv_path.open("w", encoding="utf-8-sig", newline="") as f:
        fields = [
            "word", "count", "books", "type", "note",
            "lower", "title", "title_mid", "title_start", "upper", "mixed",
            "contexts",
        ]
        wr = csv.DictWriter(f, fieldnames=fields, delimiter="\t")
        wr.writeheader()
        for row in candidates:
            wr.writerow({k: row[k] for k in fields})

    # Odrzucone nazwy — zachowujemy do audytu, zamiast bezpowrotnie usuwać.
    rejected_path = folder / "missing_words_rejected_proper_names.tsv"
    with rejected_path.open("w", encoding="utf-8-sig", newline="") as f:
        fields = [
            "word", "count", "books", "reason",
            "lower", "title", "title_mid", "title_start", "upper", "mixed",
            "contexts",
        ]
        wr = csv.DictWriter(f, fieldnames=fields, delimiter="\t")
        wr.writeheader()
        for row in rejected_names:
            wr.writerow({k: row[k] for k in fields})

    shortlist_path = folder / "missing_words_shortlist.txt"
    with shortlist_path.open("w", encoding="utf-8") as f:
        f.write(
            "POTENCJALNE BRAKI W EN-PL\n"
            "========================\n\n"
            "Nazwy własne są filtrowane na podstawie wielkości liter i pozycji w zdaniu.\n"
            "Pełna lista odrzuconych nazw jest w missing_words_rejected_proper_names.tsv.\n\n"
        )
        for row in candidates[:SHORTLIST_LIMIT]:
            extra = f" — {row['note']}" if row["note"] else ""
            f.write(
                f"{row['word']}\t{row['type']}\t{row['count']}×\t"
                f"{row['books']} książki{extra}\n"
            )

    by_type = Counter(r["type"] for r in candidates)

    summary_path = folder / "missing_words_summary.txt"
    with summary_path.open("w", encoding="utf-8") as f:
        f.write("EPUB vs EN-PL — PODSUMOWANIE\n")
        f.write("===========================\n\n")
        f.write(f"Słownik: {dict_source}\n")
        f.write(f"Haseł słownika (wszystkie wpisy): {dict_entry_count:,}\n")
        f.write(f"Unikalnych form case-insensitive: {len(dict_words):,}\n")
        f.write(f"Liczba EPUB-ów: {len(epub_paths)}\n")
        f.write(f"Tokenów po filtracji: {total_tokens:,}\n")
        f.write(f"Kandydatów po filtracji: {len(candidates):,}\n")
        f.write(f"Odrzuconych prawdopodobnych nazw własnych: {len(rejected_names):,}\n\n")
        f.write("Kandydaci według typu:\n")
        for k, v in by_type.most_common():
            f.write(f"- {k}: {v}\n")

    print()
    print(f"Gotowe.")
    print(f"  kandydaci: {len(candidates):,}")
    print(f"  odrzucone prawdopodobne nazwy własne: {len(rejected_names):,}")
    print(f"  {shortlist_path.name}")
    print(f"  {tsv_path.name}")
    print(f"  {rejected_path.name}")
    print(f"  {summary_path.name}")

    if candidates:
        print("\nPierwsze 30 kandydatów:")
        for row in candidates[:30]:
            note = f" ({row['note']})" if row["note"] else ""
            print(
                f"  {row['word']:<24} {row['type']:<11} "
                f"{row['count']:>4}× / {row['books']} books{note}"
            )


if __name__ == "__main__":
    main()
