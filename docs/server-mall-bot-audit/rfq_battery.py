"""Adversarial RFQ battery with automatic contract checks (real workbook)."""
import time, json, sys, traceback
from decimal import Decimal
from servermall_cpq.new_path import NewPathCPQService

svc = NewPathCPQService(journal=None)
cat = svc.catalog

CASES = [
 # (id, text, explicit_ref)
 ("r760-sas", "Dell R760 8SFF NEW, 2x Xeon Gold 6430, 256GB DDR5 RAM, 4x 1.92TB SAS SSD RAID10", False),
 ("r760-nvme", "Dell R760 8SFF NEW, 2x Xeon Gold 6430, 256GB DDR5 RAM, 4x 1.92TB NVMe RAID10", False),
 ("r760-25g", "Dell R760 8SFF, 2x Xeon Gold 6430, 256GB DDR5, 2x 25GbE SFP28", False),
 ("r760-10g", "Dell R760 8SFF, 2x Xeon Gold 6430, 256GB DDR5, 2x 10GbE SFP+", False),
 ("r760-1cpu", "Dell R760 8SFF, 1x Xeon Gold 6430, 128GB DDR5, 2x 960GB SATA SSD RAID1", False),
 ("r760-qty3", "3x Dell R760 8SFF NEW, 2x Xeon Gold 6430, 256GB DDR5 RAM, 4x 1.92TB SAS SSD RAID10", False),
 ("r760-nodrives", "Dell R760 8SFF NEW, 2x Xeon Gold 6430, 256GB DDR5 RAM, without drives", False),
 ("r760-2pools", "Dell R760 16SFF, 2x Xeon Gold 6430, 512GB DDR5, 2x 960GB SSD RAID1 for OS + 8x 3.84TB SSD RAID5 for data", False),
 ("r760-usable", "Dell R760 12LFF, 2x Xeon Silver 4410Y, 128GB, 40TB usable RAID6 SAS HDD", False),
 ("r760-gpu", "Dell R760, 2x Xeon Gold 6430, 512GB, 2x NVIDIA L40S", False),
 ("r760xs", "Dell R760xs, 2x Xeon Silver 4410Y, 128GB DDR5, 4x 2.4TB SAS 10k RAID5", False),
 ("r660", "Dell R660 8SFF, 2x Xeon Gold 6426Y, 256GB DDR5, 2x 1.92TB SSD RAID1", False),
 ("r650", "Dell R650, 2x Xeon Gold 6338, 256GB, 4x 1.92TB SSD", False),
 ("r740-ref", "Refurbished Dell R740 16SFF, 2x Gold 6248, 384GB DDR4, 8x 2.4TB SAS 10k RAID6", True),
 ("r740xd", "Dell R740xd 12LFF, 2x Silver 4214, 192GB, 12x 8TB SAS RAID6, 2x 10GbE RJ45", False),
 ("r7525", "Dell R7525, 2x EPYC 7543, 512GB, 8x 3.84TB NVMe", False),
 ("t360", "Dell T360, E-2488, 64GB, 2x 1.92TB SSD RAID1", False),
 ("t360-simple", "Dell T360 NEW, E-2488, 64GB", False),
 ("t560", "Dell T560, 2x Xeon Silver 4410Y, 128GB, 4x 4TB SATA RAID5", False),
 ("dl380g11", "HPE DL380 Gen11 8SFF, 2x Xeon Gold 6430, 256GB DDR5, 4x 1.92TB SAS SSD RAID10", False),
 ("dl380g11-25g", "HPE DL380 Gen11 8SFF, 2x Xeon Gold 6430, 256GB DDR5, 2x 25GbE SFP28", False),
 ("dl360g11", "HPE DL360 Gen11, 2x Xeon Gold 6426Y, 512GB DDR5, 2x 960GB SSD RAID1", False),
 ("dl380g10-ref", "HPE DL380 Gen10 refurbished, 2x Gold 6248, 384GB, 8x 2.4TB SAS 10k RAID6, 3 servers", True),
 ("dl360g10-ref", "REF HPE DL360 Gen10 8SFF, 2x Gold 6130, 256GB, 4x 1.92TB SATA SSD RAID10", True),
 ("dl380g10-plus", "HPE DL380 Gen10 Plus, 2x Gold 6326, 256GB, 8x 1.92TB SSD RAID5", False),
 ("dl385", "HPE DL385 Gen11, 2x EPYC 9354, 768GB DDR5, 8x 3.84TB NVMe", False),
 ("ml350", "HPE ML350 Gen11 tower, 1x Xeon Silver 4410Y, 64GB, 4x 4TB SATA RAID5", False),
 ("virt", "Need a virtualization server, 512GB RAM, 20TB usable SSD storage, 2 CPUs", False),
 ("virt-cores", "Virtualization host for 40 VMs, 2 CPUs minimum 32 cores each, 1TB RAM, 10TB usable NVMe, 4x 25GbE", False),
 ("backup", "backup server, 12x 16TB SAS RAID6, 128GB RAM, 2x 10GbE SFP+", False),
 ("db", "database server: 2x Xeon Gold 6448Y, 1TB DDR5, 4x 3.84TB NVMe RAID10", False),
 ("ad", "small server for Active Directory and file sharing, 32GB RAM, 2x 2TB SATA RAID1", False),
 ("file", "file server 100TB usable, RAID6, 64GB RAM", False),
 ("gpu-ai", "AI training server, 2x EPYC 9354, 512GB, 4x NVIDIA H100", False),
 ("cheap", "cheapest dual-socket 2U server with 128GB RAM and 4x 1.2TB SAS", False),
 ("email", "Hi, we need a quote for a Dell PowerEdge R760 with two Intel Xeon Gold 6430 processors, 512 GB of DDR5 memory, eight 1.92 TB SAS SSDs in RAID 10, dual 25 GbE SFP28 ports, redundant power supplies and rail kit. Thanks, John", False),
 ("chat", "r760 / 2x6430 / 256gb / 4x1.92 sas ssd", False),
 ("lenovo", "Lenovo SR650 V3, 2x Xeon Gold 6430, 256GB", False),
 ("supermicro", "Supermicro 2U, 2x EPYC 9354, 512GB, 8x NVMe", False),
 ("ram-only", "8x 32GB DDR5 RDIMM 4800", False),
 ("hpe-ddr4-new", "HPE DL380 Gen10 NEW, 2x Gold 6248R, 256GB, 4x 1.92TB SSD", False),
 ("odd-ram", "Dell R760, 2x Xeon Gold 6430, 384GB DDR5, 4x 1.92TB SSD RAID5", False),
 ("tb-ram", "Dell R760, 2x Xeon Platinum 8480+, 2TB DDR5, 8x 7.68TB NVMe", False),
 ("raid1-3drives", "Dell R660, 2x Gold 6430, 256GB, 3x 1.92TB SSD RAID1", False),
 ("nic-4x10", "Dell R760, 2x Gold 6430, 256GB, 4 x 10GbE RJ45", False),
]

def comp(cid):
    c = cat.get(cid)
    return c

def analyze(case_id, text, explicit_ref):
    t0 = time.perf_counter()
    r = svc.process_text(text)
    dt = time.perf_counter() - t0
    p = r.plan; req = r.request
    out = {"id": case_id, "time_s": round(dt, 1), "status": p.status.value if p else None,
           "unsupported": r.unsupported_reason, "question": r.question, "ready": r.ready,
           "diag": str(p.diagnostics) if p else None, "roles": {}, "flags": []}
    if not p:
        return out
    want_ram = req.ram.min_gb if req else None
    want_cpus = req.cpu.cpu_count if req else None
    want_cond = req.condition.required if req else None
    pools = list(req.storage) if req else []
    roles = {}
    for lbl, o in (("DIRECT", p.direct), ("ALTERNATIVE", p.alternative), ("VALUE", p.value)):
        if o is None:
            roles[lbl] = None; continue
        tb = o.priced_bom.technical_bom
        plat = comp(tb.platform_id) or None
        info = {"platform": tb.platform_id, "sale": str(o.sale_total_per_server_ex_vat),
                "dev": list(o.deviations), "ram_gb": 0, "cpus": 0, "dimms": 0, "drives": [], "trays": 0, "nics": [],
                "conds": set(), "vendor": None}
        pl = svc.planner.builder.compatibility.platforms.get(tb.platform_id)
        if pl is not None:
            info["vendor"] = getattr(pl, "vendor", None); info["plat_condition"] = getattr(pl, "condition", None)
            info["plat_name"] = getattr(pl, "display_name", None)
        for line in o.priced_bom.lines:
            c = comp(line.component_id)
            if c is None:
                info["conds"].add("pending"); continue
            a = c.attributes
            if c.category == "ram":
                info["ram_gb"] += line.quantity * int(a.get("module_gb") or 0); info["dimms"] += line.quantity
            elif c.category == "cpu":
                info["cpus"] += line.quantity; info["cpu"] = c.display_name[:50]
            elif c.category == "storage":
                info["drives"].append((line.quantity, a.get("capacity_gb"), a.get("protocol"), a.get("media"), a.get("form_factor")))
            elif c.category == "tray":
                info["trays"] += line.quantity
            elif c.category == "nic":
                info["nics"].append((line.quantity, a.get("ports"), a.get("speed_gb"), a.get("media")))
            if c.condition: info["conds"].add(c.condition)
        info["conds"] = sorted(info["conds"])
        roles[lbl] = info
        # checks
        if want_ram and info["ram_gb"] < want_ram:
            out["flags"].append(f"{lbl}: RAM {info['ram_gb']} < requested {want_ram}")
        if want_cpus and info["cpus"] != want_cpus:
            out["flags"].append(f"{lbl}: CPU count {info['cpus']} != requested {want_cpus}")
        if info["cpus"] == 2 and info["dimms"] % 2 == 1:
            out["flags"].append(f"{lbl}: odd DIMM count {info['dimms']} on 2 CPUs")
        want_drives = sum(pl_.drive_count or 0 for pl_ in pools)
        have_drives = sum(q for q, *_ in info["drives"])
        if want_drives and have_drives < want_drives:
            out["flags"].append(f"{lbl}: drives {have_drives} < requested {want_drives}")
        for pl_ in pools:
            if pl_.min_drive_capacity_gb and info["drives"]:
                if all((capgb or 0) < pl_.min_drive_capacity_gb for _, capgb, *_ in info["drives"]):
                    out["flags"].append(f"{lbl}: drive capacity below requested {pl_.min_drive_capacity_gb}")
            if pl_.interface and info["drives"] and all((proto or '').upper() != pl_.interface.upper() for *_x, proto, m, f in info["drives"]):
                out["flags"].append(f"{lbl}: drive interface {[d[2] for d in info['drives']]} != requested {pl_.interface}")
        if have_drives and info["trays"] == 0:
            out["flags"].append(f"{lbl}: {have_drives} drives but no tray line")
        if req and req.network:
            want_ports = sum(n.ports for n in req.network)
            have_ports = sum(q * (pp or 0) for q, pp, *_ in info["nics"])
            if have_ports < want_ports:
                out["flags"].append(f"{lbl}: NIC ports {have_ports} < requested {want_ports}")
        if lbl in ("DIRECT", "ALTERNATIVE") and not explicit_ref and want_cond != "REF":
            if info.get("plat_condition") == "REF" or ("REF" in info["conds"] and "NEW" not in info["conds"]):
                out["flags"].append(f"{lbl}: REF platform/components in a NEW request ({info['conds']}, plat={info.get('plat_condition')})")
        if explicit_ref and info.get("plat_condition") == "NEW":
            out["flags"].append(f"{lbl}: NEW platform in explicit REF request")
    out["roles"] = {k: (v and {kk: vv for kk, vv in v.items() if kk != 'dev'} | {"dev": v["dev"][:4]}) for k, v in roles.items()}
    d, a, v = roles.get("DIRECT"), roles.get("ALTERNATIVE"), roles.get("VALUE")
    if d and a and d["vendor"] and a["vendor"] and d["vendor"] == a["vendor"] and not explicit_ref:
        out["flags"].append(f"ALTERNATIVE same OEM as DIRECT ({d['vendor']})")
    plats = [x["platform"] for x in (d, a, v) if x]
    if len(plats) != len(set(plats)):
        out["flags"].append("two roles share one platform")
    if d and a and v and p.status.value == "COMPLETE" and not explicit_ref:
        try:
            ds, as_, vs = Decimal(d["sale"]), Decimal(a["sale"]), Decimal(v["sale"])
            if vs > min(ds, as_) * Decimal("0.9"):
                out["flags"].append(f"VALUE not >=10% cheaper: V={vs} D={ds} A={as_}")
        except Exception: pass
    return out

results = []
for cid, text, ref in CASES:
    try:
        res = analyze(cid, text, ref)
    except Exception as e:
        res = {"id": cid, "error": repr(e), "tb": traceback.format_exc()[-800:]}
    results.append(res)
    print(f"{cid:14} {res.get('time_s','?'):>5} {str(res.get('status')):18} {str(res.get('unsupported') or res.get('question') or res.get('error') or '')[:40]:40} flags={len(res.get('flags',[]))}", flush=True)
json.dump(results, open(sys.argv[1], "w"), indent=1, default=str)
print("WROTE", sys.argv[1])
