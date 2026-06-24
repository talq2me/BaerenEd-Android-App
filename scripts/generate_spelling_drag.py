#!/usr/bin/env python3
"""Generate spelling drag JSON files (100 words each). Run from repo root."""
import json
import unicodedata
from pathlib import Path

from spelling_drag_gr1_data import EN_GR1, FR_GR1

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/assets/data"


def norm(s: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn"
    )


def parse_entry(line: str) -> dict:
    word, syl = line.strip().split("|", 1)
    syllables = [s.strip() for s in syl.split("-") if s.strip()]
    if norm("".join(syllables)) != norm(word):
        raise ValueError(f"Syllable mismatch: {word!r} -> {syllables}")
    return {"word": word.strip(), "syllables": syllables}


def write_file(name: str, lang: str, lines: list[str]) -> None:
    words = [parse_entry(ln) for ln in lines if ln.strip()]
    if len(words) != 100:
        raise SystemExit(f"{name}: expected 100 words, got {len(words)}")
    path = ROOT / name
    path.write_text(
        json.dumps({"lang": lang, "words": words}, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {path.name} ({len(words)} words)")


EN_BM_EXTRA = """
excitement|ex-cite-ment
leadership|lead-er-ship
membership|mem-ber-ship
ownership|own-er-ship
profitable|prof-it-a-ble
falsehood|false-hood
championship|cham-pi-on-ship
sensible|sen-si-ble
motherhood|moth-er-hood
dependable|de-pend-a-ble
agreeable|a-gree-a-ble
excitement|ex-cite-ment
beautiful|beau-ti-ful
adventure|ad-ven-ture
calendar|cal-en-dar
celebrate|cel-e-brate
community|com-mu-ni-ty
condition|con-di-tion
continue|con-tin-ue
creature|crea-ture
dangerous|dan-ger-ous
decision|de-ci-sion
delicious|de-li-cious
discover|dis-cov-er
distance|dis-tance
division|di-vi-sion
electric|e-lec-tric
elephant|el-e-phant
enormous|e-nor-mous
especially|es-pe-cial-ly
everyone|ev-ery-one
exercise|ex-er-cise
experience|ex-pe-ri-ence
favourite|fa-vour-ite
February|Feb-ru-ar-y
fifteen|fif-teen
fourteen|four-teen
thirteen|thir-teen
twelve|twelve
thousand|thou-sand
hundred|hun-dred
library|li-brar-y
medicine|med-i-cine
mountain|moun-tain
national|na-tion-al
natural|nat-u-ral
necessary|nec-es-sar-y
newspaper|news-pa-per
November|No-vem-ber
October|Oc-to-ber
December|De-cem-ber
September|Sep-tem-ber
January|Jan-u-ar-y
August|Au-gust
precious|pre-cious
probably|prob-a-bly
recently|re-cent-ly
remember|re-mem-ber
restaurant|res-tau-rant
sandwich|sand-wich
Saturday|Sat-ur-day
secretary|sec-re-tar-y
sentence|sen-tence
separate|sep-a-rate
situation|sit-u-a-tion
somebody|some-bod-y
something|some-thing
somewhere|some-where
straight|straight
strawberry|straw-ber-ry
strength|strength
surprise|sur-prise
telephone|tel-e-phone
temperature|tem-per-a-ture
terrible|ter-ri-ble
Thursday|Thurs-day
together|to-geth-er
tomorrow|to-mor-row
triangle|tri-an-gle
trouble|trou-ble
Tuesday|Tues-day
umbrella|um-brel-la
understand|un-der-stand
vegetable|veg-e-ta-ble
Wednesday|Wednes-day
yesterday|yes-ter-day
""".strip().splitlines()

# Merge existing BM + extras - read current BM files and combine

FR_BM_EXTRA = """
février|fé-vrier
mars|mars
avril|a-vril
mai|mai
juin|juin
juillet|jui-llet
août|août
nettoyer|net-toy-er
nettoyer|net-toy-er
""".strip().splitlines()

if __name__ == "__main__":
    write_file("spellingDragEnAM.json", "en", EN_GR1[:100])
    write_file("spellingDragFrAM.json", "fr", FR_GR1[:100])

    # BM: load existing + merge
    en_bm_existing = json.loads((ROOT / "spellingDragEnBM.json").read_text(encoding="utf-8"))
    fr_bm_existing = json.loads((ROOT / "spellingDragFrBM.json").read_text(encoding="utf-8"))

    def to_line(w):
        return w["word"] + "|" + "-".join(w["syllables"])

    en_lines = [to_line(w) for w in en_bm_existing["words"]]
    seen_en = {w.split("|")[0] for w in en_lines}
    for block in [EN_BM_EXTRA]:
        for ln in block:
            if not ln.strip():
                continue
            w = ln.split("|")[0]
            if w not in seen_en:
                seen_en.add(w)
                en_lines.append(ln.strip())
    # more en if needed
    MORE_EN = """
excitement|ex-cite-ment
leadership|lead-er-ship
membership|mem-ber-ship
ownership|own-er-ship
profitable|prof-it-a-ble
falsehood|false-hood
agreeable|a-gree-a-ble
beautiful|beau-ti-ful
adventure|ad-ven-ture
calendar|cal-en-dar
celebrate|cel-e-brate
community|com-mu-ni-ty
condition|con-di-tion
continue|con-tin-ue
creature|crea-ture
dangerous|dan-ger-ous
decision|de-ci-sion
delicious|de-li-cious
discover|dis-cov-er
distance|dis-tance
division|di-vi-sion
electric|e-lec-tric
elephant|el-e-phant
enormous|e-nor-mous
everyone|ev-ery-one
exercise|ex-er-cise
favourite|fa-vour-ite
fourteen|four-teen
thirteen|thir-teen
twelve|twelve
thousand|thou-sand
hundred|hun-dred
library|li-brar-y
medicine|med-i-cine
national|na-tion-al
natural|nat-u-ral
necessary|nec-es-sar-y
newspaper|news-pa-per
precious|pre-cious
probably|prob-a-bly
recently|re-cent-ly
restaurant|res-tau-rant
sandwich|sand-wich
secretary|sec-re-tar-y
separate|sep-a-rate
situation|sit-u-a-tion
somebody|some-bod-y
somewhere|some-where
straight|straight
strawberry|straw-ber-ry
strength|strength
telephone|tel-e-phone
temperature|tem-per-a-ture
Thursday|Thurs-day
tomorrow|to-mor-row
triangle|tri-an-gle
trouble|trou-ble
Tuesday|Tues-day
umbrella|um-brel-la
understand|un-der-stand
vegetable|veg-e-ta-ble
Wednesday|Wednes-day
yesterday|yes-ter-day
activity|ac-tiv-i-ty
actually|ac-tu-al-ly
although|al-though
anything|any-thing
attention|at-ten-tion
building|build-ing
business|bus-i-ness
children|chil-dren
complete|com-plete
consider|con-sid-er
continue|con-tin-ue
daughter|daugh-ter
describe|de-scribe
develop|de-vel-op
dictionary|dic-tion-ar-y
difficult|dif-fi-cult
disappear|dis-ap-pear
education|ed-u-ca-tion
electricity|e-lec-tric-i-ty
encourage|en-cour-age
especially|es-pe-cial-ly
everybody|ev-ery-bod-y
everything|ev-ery-thing
explanation|ex-pla-na-tion
favourite|fa-vour-ite
February|Feb-ru-ar-y
fifteen|fif-teen
""".strip().splitlines()
    for ln in MORE_EN:
        w = ln.split("|")[0]
        if w not in seen_en:
            seen_en.add(w)
            en_lines.append(ln.strip())
    if len(en_lines) < 100:
        raise SystemExit(f"EN BM only {len(en_lines)} words - add more")
    write_file("spellingDragEnBM.json", "en", en_lines[:100])

    fr_lines = [to_line(w) for w in fr_bm_existing["words"]]
    seen_fr = {w.split("|")[0] for w in fr_lines}
    MORE_FR = """
février|fé-vrier
mars|mars
avril|a-vril
mai|mai
juin|juin
juillet|jui-llet
août|août
dimanche|di-man-che
lundi|lun-di
mardi|mar-di
mercredi|mer-cre-di
jeudi|jeu-di
vendredi|ven-dre-di
samedi|sa-me-di
boulanger|bou-lan-ger
boulangerie|bou-lan-ge-rie
épicerie|é-pi-ce-rie
pharmacie|phar-ma-cie
bibliothèque|bi-bli-o-thèque
université|u-ni-ver-si-té
athlétisme|ath-lé-tisme
natation|na-ta-tion
gymnase|gym-nase
cinéma|ci-né-ma
théâtre|thé-â-tre
musée|mu-sée
parc|parc
jardin|jar-din
camping|cam-ping
plage|plage
piscine|pis-cine
avion|a-vion
bateau|ba-teau
train|train
autobus|au-to-bus
camion|ca-mion
moto|mo-to
vélo|vé-lo
route|route
rue|rue
avenue|a-ve-nue
pont|pont
rivière|ri-vière
lac|lac
océan|o-céan
plage|plage
île|île
mont|mont
colline|col-line
vallée|val-lée
forêt|fo-rêt
champ|champ
ferme|fer-me
grange|gran-ge
étable|é-ta-ble
poule|pou-le
coq|coq
canard|ca-nard
oie|oie
mouton|mou-ton
chèvre|chèvre
âne|âne
singe|sin-ge
zèbre|zè-bre
girafe|gi-rafe
éléphant|é-lé-phant
lion|li-on
tigre|ti-gre
ours|ours
loup|loup
renard|re-nard
écureuil|é-cu-reuil
castor|cas-tor
loutre|lou-tre
baleine|ba-leine
dauphin|dau-phin
requin|re-quin
crabe|crabe
homard|ho-mard
crevette|cre-vet-te
moule|mou-le
huître|huî-tre
""".strip().splitlines()
    for ln in MORE_FR:
        w = ln.split("|")[0]
        if w not in seen_fr:
            seen_fr.add(w)
            fr_lines.append(ln.strip())
    if len(fr_lines) < 100:
        raise SystemExit(f"FR BM only {len(fr_lines)} words")
    write_file("spellingDragFrBM.json", "fr", fr_lines[:100])
