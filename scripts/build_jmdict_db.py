#!/usr/bin/env python3
"""Build jmdict.db for NovelNotifier from the EDRDG JMdict_e XML.

Downloads (or reuses) JMdict_e.gz, parses entries and writes a compact
SQLite lookup table:

    dict(form TEXT, reading TEXT, senses TEXT)
    CREATE INDEX dict_form ON dict(form)

One row per lookup key: every kanji form AND every reading of an entry
point at the same senses blob. `senses` is "1) (n) gloss / gloss; 2) ..."
with POS abbreviations kept. Android queries it read-only via the
framework SQLite (no Room).

Usage: python3 scripts/build_jmdict_db.py [JMdict_e.gz] [out.db]
"""
import gzip
import sqlite3
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

URL = "https://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz"
XML_PATH = Path("/tmp/JMdict_e")
DB_PATH = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("app/src/main/assets/databases/jmdict.db")


def ensure_xml() -> Path:
    if XML_PATH.exists():
        return XML_PATH
    gz = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/JMdict_e.gz")
    if not gz.exists():
        print(f"downloading {URL}")
        urllib.request.urlretrieve(URL, gz)
    with gzip.open(gz, "rb") as f, open(XML_PATH, "wb") as out:
        out.write(f.read())
    return XML_PATH


def main() -> None:
    ensure_xml()
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    if DB_PATH.exists():
        DB_PATH.unlink()
    conn = sqlite3.connect(DB_PATH)
    conn.execute("CREATE TABLE dict (form TEXT, reading TEXT, senses TEXT)")
    rows = 0
    for _, entry in ET.iterparse(str(XML_PATH), events=("end",)):
        if entry.tag != "entry":
            continue
        kanji = [k.findtext("keb") for k in entry.findall("k_ele")]
        readings = [r.findtext("reb") for r in entry.findall("r_ele")]
        senses = []
        for i, sense in enumerate(entry.findall("sense"), start=1):
            pos = [p.text for p in sense.findall("pos") if p.text]
            pos_abbr = []
            for p in pos:
                # "noun (common) (spelled ..." → keep the abbreviation only
                abbr = p.split(",")[0].strip("() ")
                if abbr:
                    pos_abbr.append(abbr)
            glosses = [g.text for g in sense.findall("gloss") if g.text]
            if glosses:
                prefix = f"({';'.join(pos_abbr)}) " if pos_abbr else ""
                senses.append(f"{i}) {prefix}" + "; ".join(glosses))
        if not senses:
            entry.clear()
            continue
        blob = "\n".join(senses)
        first_reading = readings[0] if readings else ""
        forms = set(kanji + readings)
        for form in forms:
            if form:
                conn.execute(
                    "INSERT INTO dict VALUES (?, ?, ?)", (form, first_reading, blob)
                )
                rows += 1
        entry.clear()
    conn.execute("CREATE INDEX dict_form ON dict(form)")
    conn.commit()
    n = conn.execute("SELECT COUNT(*) FROM dict").fetchone()[0]
    conn.close()
    print(f"wrote {DB_PATH}: {n} rows ({rows} inserts)")


if __name__ == "__main__":
    main()
