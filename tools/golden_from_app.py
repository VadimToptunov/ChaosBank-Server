"""Regenerates the golden responses (server/src/test/resources/golden/) from the apps'
own seed, so the parity test compares the server with the embedded mock, not with a copy.

Usage: python3 tools/golden_from_app.py <path to ChaosBank-Android>/app/src/main/java/com/vadimtoptunov/chaosbank_android/models/SeedData.kt
"""
import json
import pathlib
import re
import sys

src = pathlib.Path(sys.argv[1]).read_text()
ref = int(re.search(r"referenceDate: Long = ([\d_]+)L", src).group(1).replace("_", ""))

accounts = [
    {"name": n, "currency": c, "balance": b}
    for n, c, b in re.findall(r'Account\("([^"]+)", Currency\.(\w+), BigDecimal\("([^"]+)"\)\)', src)
]
order = ["EUR", "USD", "GBP"]  # Currency.entries order: fetchAccounts lists accounts in it
accounts.sort(key=lambda a: order.index(a["currency"]))

txs = []
for tid, title, cat, days, amount, ccy in re.findall(
        r'tx\("(\w+)", "([^"]+)", "([^"]+)", (\d+), "([^"]+)", Currency\.(\w+)\)', src):
    hour = int(tid[-2:]) % 12
    txs.append({"id": tid, "title": title, "category": cat,
                "date": ref - (int(days) * 86_400 + hour * 3_600), "amount": amount, "currency": ccy})
txs.sort(key=lambda t: -t["date"])  # sortedByDescending is stable, like Python's sort

out = pathlib.Path(__file__).resolve().parent.parent / "server/src/test/resources/golden"
out.mkdir(parents=True, exist_ok=True)
(out / "accounts.json").write_text(json.dumps(accounts, ensure_ascii=False, indent=2) + "\n")
(out / "transactions.json").write_text(json.dumps(txs, ensure_ascii=False, indent=2) + "\n")
print(f"{len(accounts)} accounts, {len(txs)} transactions")
