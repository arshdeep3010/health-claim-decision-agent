"""
Generate a SYNTHETIC policy-wording PDF for every seeded policy, upload it, and
ingest it so RAG retrieval has real clauses to cite.

The wording is built from the same dataset fields the rule engine uses
(coverage limit, exclusions, waiting periods, dates), so evidence agrees with
the deterministic rules. Standard library only.

Usage (app running on :8080, dev profile):
    python scripts/seed_policy_documents.py             # upload + ingest missing wording
    python scripts/seed_policy_documents.py --reingest  # re-embed existing documents
                                                        # (after changing EMBEDDING_*)
Env overrides: HCDA_BASE_URL, HCDA_ADMIN_USER, HCDA_ADMIN_PASSWORD

SYNTHETIC DATA ONLY - not real insurance wording.
"""
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATASET = ROOT / "src" / "main" / "resources" / "synthetic" / "dataset.json"
OUT_DIR = ROOT / "data" / "policy-docs"
BASE = os.environ.get("HCDA_BASE_URL", "http://localhost:8080").rstrip("/")
USER = os.environ.get("HCDA_ADMIN_USER", "admin")
PASSWORD = os.environ.get("HCDA_ADMIN_PASSWORD", "admin-dev-password")  # dev seed default

# Procedures each policy explicitly lists as covered (matches the dataset's claims).
COVERED_PROCEDURES = [
    ("Laparoscopic cholecystectomy", "removal of the gallbladder for gallstones or cholecystitis"),
    ("Appendectomy", "surgical removal of the appendix for acute appendicitis"),
    ("Hernia repair", "open or laparoscopic repair of inguinal and umbilical hernia"),
    ("Cataract surgery (phacoemulsification)", "lens extraction with intraocular lens implant, day care"),
    ("Ureteroscopy", "endoscopic removal of kidney and ureteric stones"),
    ("Fracture fixation (ORIF)", "open reduction and internal fixation of long-bone fractures"),
    ("Inpatient antibiotic therapy", "intravenous antibiotics for pneumonia, cellulitis and sepsis"),
    ("Joint replacement", "total hip or knee replacement for osteoarthritis, subject to pre-authorisation"),
    ("Cardiac procedures", "angioplasty and CABG, subject to pre-existing condition waiting periods"),
    ("Arthroscopy", "diagnostic and therapeutic arthroscopy of knee and shoulder"),
]

EXCLUSION_TEXT = {
    "Cosmetic surgery": "Any procedure performed primarily to change appearance, including rhinoplasty, "
                        "liposuction and aesthetic surgery, unless required following an accident.",
    "Experimental treatment": "Treatment that is experimental, investigational or unproven, including gene "
                              "therapy and therapies outside approved clinical guidelines.",
    "Dental implants": "Dental treatment of any kind, including dental implants, crowns and dentures, "
                       "unless arising from an accident requiring hospitalisation.",
    "Infertility treatment": "Investigation and treatment of infertility, including IVF, IUI and assisted "
                             "reproduction.",
}


def wording(p):
    wp = p.get("waitingPeriods", {})
    lines = [
        f"SYNTHETIC POLICY WORDING - {p['policyNumber']}",
        f"Plan type: {p['policyType'].replace('_', ' ').title()}. Fabricated for demonstration only.",
        "",
        "Section 1 - Policy Period",
        f"This policy is effective from {p['effectiveDate']} and expires on {p['expiryDate']}.",
        "Claims for hospitalisation that begins outside the policy period are not payable.",
        f"Current policy status: {p['status']}.",
        "",
        "Section 2 - Sum Insured and Cost Sharing",
        f"The maximum amount payable in a policy year is {p['currency']} {p['coverageLimit']:,}.",
        f"A deductible of {p['currency']} {p.get('deductible', 0):,} applies per claim.",
        f"A co-payment of {p.get('copayPercentage', 0)} percent applies to every admissible claim.",
        "Claims above the sum insured are payable only up to the remaining limit.",
        "",
        "Section 3 - Covered Treatment",
        "The insurer pays reasonable and customary charges for medically necessary in-patient",
        "hospitalisation of at least 24 hours, and listed day-care procedures, including:",
    ]
    for i, (name, desc) in enumerate(COVERED_PROCEDURES, 1):
        lines.append(f"3.{i} {name}: {desc}.")
    lines += ["", "Section 4 - Exclusions",
              "The insurer will not pay for any claim arising from the following:"]
    for i, ex in enumerate(p.get("exclusions", []), 1):
        lines.append(f"4.{i} {ex}: {EXCLUSION_TEXT.get(ex, 'Excluded under this policy.')}")
    lines += ["", "Section 5 - Waiting Periods"]
    if "GENERAL" in wp:
        lines.append(f"5.1 Initial waiting period: claims within {wp['GENERAL']} month(s) of the policy "
                     "start are not payable, except for accidents.")
    if "PRE_EXISTING" in wp:
        lines.append(f"5.2 Pre-existing conditions are covered only after {wp['PRE_EXISTING']} months of "
                     "continuous coverage.")
    lines.append("5.3 Pre-existing condition status must be declared; unknown status is referred to review.")
    lines += ["", "Section 6 - Claims Procedure",
              "A claim must include the diagnosis, the procedure performed, hospitalisation dates and the",
              "itemised bill. Missing or conflicting information is referred to a human claims reviewer.",
              "Automated decisions are advisory and every outcome is recorded in the audit trail."]
    return lines


def make_pdf(lines):
    """Minimal multi-page, text-only PDF (Helvetica, 11pt)."""
    per_page = 48
    pages = [lines[i:i + per_page] for i in range(0, len(lines), per_page)] or [[]]

    def esc(s):
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").encode("latin-1", "replace")

    objs = []  # index 0 -> obj 1
    objs.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objs.append(None)  # pages, filled later
    objs.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    kids = []
    for page in pages:
        stream = b"BT /F1 11 Tf 15 TL 56 790 Td\n"
        for ln in page:
            stream += b"(" + esc(ln) + b") Tj T*\n"
        stream += b"ET"
        objs.append(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        content_no = len(objs)
        objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                    b"/Resources << /Font << /F1 3 0 R >> >> /Contents %d 0 R >>" % content_no)
        kids.append(len(objs))
    objs[1] = b"<< /Type /Pages /Kids [" + b" ".join(b"%d 0 R" % k for k in kids) + b"] /Count %d >>" % len(kids)

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objs, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    return bytes(out)


def call(method, path, token=None, body=None, content_type="application/json"):
    req = urllib.request.Request(BASE + path, data=body, method=method)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if body is not None:
        req.add_header("Content-Type", content_type)
    with urllib.request.urlopen(req, timeout=120) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def main():
    reingest = "--reingest" in sys.argv[1:]
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    by_number = {p["policyNumber"]: p for p in dataset["policies"]}
    token = call("POST", "/api/v1/auth/login",
                 body=json.dumps({"username": USER, "password": PASSWORD}).encode())["token"]
    policies = call("GET", "/api/v1/policies?size=100", token)["content"]
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for pol in policies:
        number, pid = pol["policyNumber"], pol["policyId"]
        if number not in by_number:
            continue
        existing = call("GET", f"/api/v1/policies/{pid}/documents", token) or []
        if existing:
            if reingest:
                result = call("POST", f"/api/v1/policies/{pid}/ingest?reembed=true", token)
                print(f"{number}: re-ingested {len(existing)} document(s) -> {json.dumps(result)}")
            else:
                print(f"{number}: already has {len(existing)} document(s), skipping (use --reingest to re-embed)")
            continue
        pdf = make_pdf(wording(by_number[number]))
        (OUT_DIR / f"{number}.pdf").write_bytes(pdf)

        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{number}.pdf\"\r\n"
                f"Content-Type: application/pdf\r\n\r\n").encode() + pdf + f"\r\n--{boundary}--\r\n".encode()
        call("POST", f"/api/v1/policies/{pid}/documents", token, body,
             f"multipart/form-data; boundary={boundary}")
        result = call("POST", f"/api/v1/policies/{pid}/ingest", token)
        print(f"{number}: uploaded + ingested -> {json.dumps(result)}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} on {e.url}: {e.read().decode(errors='replace')[:300]}")
