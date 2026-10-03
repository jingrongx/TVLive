# -*- coding: utf-8 -*-
"""
IPTV 源筛选工具 —— 桌面版
流程:拉取订阅(txt/m3u) -> 连通验证(m3u8 追到分片) -> 分片级测速 -> 频道归一化分类 -> 每频道优选 TopN -> 输出订阅文件
用法:
    python main.py            # 图形界面
    python main.py --cli      # 命令行直跑
"""
import asyncio
import json
import os
import queue
import re
import sys
import threading
import time
from collections import OrderedDict
from urllib.parse import urljoin

import aiohttp

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

DEFAULT_SUBS = "\n".join([
    "https://vbskycn.github.io/iptv/tv/iptv4.txt",
    "https://iptv-org.github.io/iptv/countries/cn.m3u",
])

DEFAULT_CFG = {
    "subs": DEFAULT_SUBS,
    "concurrency": 30,
    "timeout": 15,
    "min_speed": 80,
    "top_n": 3,
    "proxy": "",
    "outdir": "",
    "gitee_token": "",
    "gitee_repo": "xujingrong/tv-live-config",
    "gitee_tag": "live",
    "gitee_auto": True,
}

# ---------------- 解析与归一化 ----------------

def parse_txt(text):
    groups = OrderedDict()
    cur = "未分组"
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if "#genre#" in line:
            cur = line.split(",")[0].strip() or "未分组"
            continue
        if "," not in line:
            continue
        name, _, urls = line.partition(",")
        for u in urls.split("#"):
            u = u.strip()
            if u.startswith(("http://", "https://")):
                groups.setdefault(cur, []).append((name.strip(), u))
    return groups

def parse_m3u(text):
    groups = OrderedDict()
    pend = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            pend = line.split(",", 1)[1].strip() if "," in line else None
        elif line.startswith("#"):
            continue
        elif line.startswith(("http://", "https://")):
            if pend:
                groups.setdefault("频道", []).append((pend, line))
            pend = None
    return groups

def norm_name(name):
    n = re.sub(r"[\s\-\_·()\[\]（）【】]", "", name)
    n = n.upper()
    n = re.sub(r"CCTV(\d)综合", r"CCTV\1", n)
    n = n.replace("CCTV5+", "CCTV5PLUS")
    n = re.sub(r"超清|高清|蓝光|标清|HD$", "", n)
    return n

def classify(name):
    n = norm_name(name)
    if n.startswith(("CCTV", "CGTN", "CETV")) or "央视" in name:
        return "央视频道"
    if "卫视" in name:
        return "卫视频道"
    if re.search(r"体育|足球|篮球|NBA|中超|英超", name):
        return "体育频道"
    if re.search(r"少儿|卡通|动画|动漫|金鹰|亲子|宝贝", name):
        return "少儿频道"
    if "纪录" in name or "地理" in name:
        return "纪录频道"
    if "CHC" in n or re.search(r"电影|影院|剧场", name):
        return "电影频道"
    if re.search(r"音乐|MTV|MV", name):
        return "音乐频道"
    if "春晚" in name:
        return "春晚回顾"
    if re.search(r"TVBS|东森|中天|三立|TVB|翡翠|凤凰|澳门|民视|八大|纬来|年代|华视|中视|台视|好消息|点睛|天映|寰宇|明珠|美亚|Astro|Arirang|KBS|MBC|SBS|NHK|Fuji|CNN|BBC|HBO|Fox|Discovery|Cartoon|Disney|Nick|History|EBC", name, re.I):
        return "港澳台及国际"
    if re.search(r"北京|上海|广东|深圳|浙江|江苏|湖南|湖北|山东|河南|河北|四川|重庆|天津|福建|江西|安徽|辽宁|吉林|黑龙江|陕西|甘肃|青海|宁夏|新疆|广西|云南|贵州|山西|内蒙古|海南|西藏|城市|都市|新闻综合|影视|生活|法治|教育|纪实", name) and "卫视" not in name:
        return "地方频道"
    if re.search(r"[\u4e00-\u9fff]", name):
        return "其他频道"
    return "海外频道"

BLACKLIST = re.compile(r"支持作者|赞助|广告|推广|测试频道|test", re.I)
GROUP_ORDER = ["央视频道", "卫视频道", "电影频道", "体育频道", "少儿频道", "纪录频道",
               "音乐频道", "春晚回顾", "港澳台及国际", "地方频道", "其他频道", "海外频道"]

def merge_channels(sub_results):
    """sub_results: [(title, groups), ...] -> OrderedDict key->{'display','group','sources'}"""
    chans = OrderedDict()
    for title, groups in sub_results:
        for items in groups.values():
            for name, url in items:
                key = norm_name(name)
                if not key or BLACKLIST.search(name):
                    continue
                c = chans.setdefault(key, {"display": name, "group": classify(name), "sources": []})
                if url not in c["sources"]:
                    c["sources"].append(url)
                if len(name) < len(c["display"]):
                    c["display"] = name
    return chans

# ---------------- 检测引擎 ----------------

class Engine:
    def __init__(self, cfg, emit):
        self.cfg = cfg
        self.emit = emit            # emit(kind, payload)
        self.stop_flag = threading.Event()

    def _check(self):
        if self.stop_flag.is_set():
            raise asyncio.CancelledError()

    async def _get_text(self, session, url):
        to = aiohttp.ClientTimeout(total=self.cfg["timeout"], connect=10)
        proxy = self.cfg.get("proxy") or None
        async with session.get(url, timeout=to, ssl=False, proxy=proxy,
                               headers={"User-Agent": UA}) as resp:
            resp.raise_for_status()
            return await resp.text()

    async def _download_subs(self, session, urls):
        results = []
        for i, url in enumerate(urls, 1):
            self._check()
            title = f"订阅{i}"
            try:
                text = await self._get_text(session, url)
                if "#EXTM3U" in text:
                    groups = parse_m3u(text)
                    fmt = "m3u"
                else:
                    groups = parse_txt(text)
                    fmt = "txt"
                n = sum(len(v) for v in groups.values())
                results.append((title, groups))
                self.emit("log", f"✅ {title} {url} ({fmt}, {n} 条线路)")
            except Exception as e:
                self.emit("log", f"❌ {title} {url} 拉取失败:{type(e).__name__} {str(e)[:60]}")
            self.emit("progress", (int(i / max(len(urls), 1) * 15), f"下载订阅 {i}/{len(urls)}"))
        return results

    async def _fetch_stream(self, session, url):
        """连通验证 + 返回描述;ok/warn/fail"""
        try:
            to = aiohttp.ClientTimeout(total=self.cfg["timeout"], connect=6)
            proxy = self.cfg.get("proxy") or None
            async with session.get(url, timeout=to, ssl=False, proxy=proxy,
                                   headers={"User-Agent": UA}) as resp:
                if resp.status != 200:
                    return ("fail", f"HTTP {resp.status}")
                body = await resp.content.read(65536)
                if not body:
                    return ("fail", "空响应")
                if body[:3] == b"FLV" or body[0:1] == b"\x47":
                    return ("ok", "FLV/TS 流")
                text = body[:16384].decode("utf-8", "ignore")
                if "#EXTM3U" not in text:
                    if "<html" in text.lower():
                        return ("fail", "返回网页")
                    return ("fail", "未知内容")
                lines = [l.strip() for l in text.splitlines() if l.strip()]
                if "#EXT-X-STREAM-INF" in text:
                    nxt = None
                    for i, ln in enumerate(lines):
                        if ln.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines):
                            nxt = urljoin(url, lines[i + 1]); break
                    if not nxt:
                        return ("fail", "master 无子列表")
                    return await self._fetch_stream(session, nxt)
                seg = next((l for l in lines if not l.startswith("#")), None)
                if not seg:
                    return ("warn", "m3u8 无分片")
                to2 = aiohttp.ClientTimeout(total=self.cfg["timeout"], connect=6)
                async with session.get(urljoin(url, seg), timeout=to2, ssl=False, proxy=proxy,
                                       headers={"User-Agent": UA}) as r2:
                    if r2.status != 200:
                        return ("warn", f"分片 HTTP {r2.status}(疑似防盗链)")
                    data = await r2.content.read(2048)
                    if len(data) >= 512 or (data and data[0] == 0x47):
                        return ("ok", "分片验证通过")
                    return ("warn", "分片过短")
        except asyncio.TimeoutError:
            return ("fail", "超时")
        except Exception as e:
            return ("fail", f"{type(e).__name__}: {str(e)[:50]}")

    async def _verify_all(self, session, urls, p_from, p_to):
        sem = asyncio.Semaphore(self.cfg["concurrency"])
        out = {}
        done = [0]
        total = len(urls)

        async def w(u):
            async with sem:
                self._check()
                out[u] = await self._fetch_stream(session, u)
                done[0] += 1
                if done[0] % 20 == 0 or done[0] == total:
                    self.emit("progress", (int(p_from + (p_to - p_from) * done[0] / max(total, 1)),
                                           f"连通验证 {done[0]}/{total}"))

        await asyncio.gather(*(w(u) for u in urls))
        return out

    async def _measure(self, session, sem, url, out):
        async with sem:
            self._check()
            try:
                to = aiohttp.ClientTimeout(total=self.cfg["timeout"], connect=6)
                proxy = self.cfg.get("proxy") or None
                t0 = time.monotonic()
                async with session.get(url, timeout=to, ssl=False, proxy=proxy,
                                       headers={"User-Agent": UA}) as resp:
                    if resp.status != 200:
                        out[url] = 0; return
                    head = await resp.content.read(65536)
                if head[:3] == b"FLV" or head[0:1] == b"\x47":
                    nbytes = len(head)
                    deadline = time.monotonic() + 6
                    while time.monotonic() < deadline:
                        chunk = await resp.content.read(65536)
                        if not chunk:
                            break
                        nbytes += len(chunk)
                    out[url] = round(nbytes / 1024 / max(time.monotonic() - t0, 0.1), 1)
                    return
                text = head.decode("utf-8", "ignore")
                if "#EXTM3U" not in text:
                    out[url] = 0; return
                manifest_url = url
                if "#EXT-X-STREAM-INF" in text:
                    lines = [l.strip() for l in text.splitlines() if l.strip()]
                    nxt = None
                    for i, ln in enumerate(lines):
                        if ln.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines):
                            nxt = urljoin(url, lines[i + 1]); break
                    if not nxt:
                        out[url] = 0; return
                    try:
                        async with session.get(nxt, timeout=to, ssl=False, proxy=proxy,
                                               headers={"User-Agent": UA}) as r:
                            if r.status != 200:
                                out[url] = 0; return
                            text = (await r.content.read(131072)).decode("utf-8", "ignore")
                            manifest_url = nxt
                    except Exception:
                        out[url] = 0; return
                lines = [l.strip() for l in text.splitlines() if l.strip()]
                seg = next((l for l in lines if not l.startswith("#")), None)
                if not seg:
                    out[url] = 0; return
                seg_url = urljoin(manifest_url, seg)
                s0 = time.monotonic()
                async with session.get(seg_url, timeout=to, ssl=False, proxy=proxy,
                                       headers={"User-Agent": UA}) as r2:
                    if r2.status != 200:
                        out[url] = 0; return
                    data = await r2.content.read(16 * 1024 * 1024)
                    dt = max(time.monotonic() - s0, 0.05)
                    out[url] = round(len(data) / 1024 / dt, 1) if len(data) > 10 * 1024 else 0
            except Exception:
                out[url] = 0

    async def _measure_all(self, session, urls, p_from, p_to):
        sem = asyncio.Semaphore(self.cfg["concurrency"])
        out = {}
        done = [0]
        total = len(urls)

        async def w(u):
            await self._measure(session, sem, u, out)
            done[0] += 1
            if done[0] % 10 == 0 or done[0] == total:
                self.emit("progress", (int(p_from + (p_to - p_from) * done[0] / max(total, 1)),
                                       f"测速 {done[0]}/{total}"))

        await asyncio.gather(*(w(u) for u in urls))
        return out

    def _write_outputs(self, chans, speeds, stats):
        outdir = self.cfg["outdir"]
        os.makedirs(outdir, exist_ok=True)
        min_speed = self.cfg["min_speed"]
        top_n = self.cfg["top_n"]

        result = OrderedDict()
        stats_rows = []
        for key, c in chans.items():
            ranked = sorted(c["sources"], key=lambda u: speeds.get(u, 0), reverse=True)
            good = [u for u in ranked if speeds.get(u, 0) >= min_speed]
            keep = good[:top_n]
            low = False
            if not keep and ranked:
                keep = [ranked[0]]
                low = True
            if not keep:
                continue
            result.setdefault(c["group"], []).append((c["display"], keep, low))
            avg = sum(speeds.get(u, 0) for u in keep) / len(keep)
            stats_rows.append((c["group"], c["display"], len(keep), round(avg), low,
                               [speeds.get(u, 0) for u in keep]))

        def emit_txt(only_fast):
            lines, n = [], 0
            for g in GROUP_ORDER:
                if g not in result:
                    continue
                rows = [(d, us, lk) for d, us, lk in result[g] if (not only_fast or not lk)]
                if not rows:
                    continue
                lines.append(f"{g},#genre#")
                for display, us, _ in sorted(rows, key=lambda x: x[0]):
                    lines.append(f"{display},{'#'.join(us)}")
                    n += 1
            return "\n".join(lines) + "\n", n

        fast_txt, n_fast = emit_txt(True)
        full_txt, n_full = emit_txt(False)
        p_fast = os.path.join(outdir, "final_live.txt")
        p_full = os.path.join(outdir, "final_live_full.txt")
        with open(p_fast, "w", encoding="utf-8") as f:
            f.write(fast_txt)
        with open(p_full, "w", encoding="utf-8") as f:
            f.write(full_txt)

        all_sp = [s for s in speeds.values() if s > 0]
        all_sp.sort()
        median = all_sp[len(all_sp) // 2] if all_sp else 0
        rep = ["# IPTV 源筛选报告\n",
               f"- 时间:{time.strftime('%Y-%m-%d %H:%M:%S')}",
               f"- 频道(归一化后):{len(chans)};唯一 URL:{stats['total_urls']}",
               f"- 连通验证:有效 {stats['valid']} / 存疑 {stats['warn']} / 失效 {stats['fail']}",
               f"- 测速:速度>0 {len(all_sp)} 个,中位 {median:.0f} KB/s,≥1MB/s {sum(1 for s in all_sp if s >= 1024)} 个",
               f"- 淘汰线:{min_speed} KB/s;每频道保留 {top_n} 条线路",
               f"- 输出:精选 {n_fast} 频道(final_live.txt);完整 {n_full} 频道(final_live_full.txt)\n"]
        rep.append("| 分组 | 精选频道 | 完整频道 |")
        rep.append("|---|---|---|")
        for g in GROUP_ORDER:
            if g in result:
                nf = sum(1 for _, _, lk in result[g] if not lk)
                rep.append(f"| {g} | {nf} | {len(result[g])} |")
        rep.append("\n| 分组 | 频道 | 线路数 | 最快 KB/s | 线路速度 | 备注 |")
        rep.append("|---|---|---|---|---|---|")
        for g, d, n, avg, low, sp in sorted(stats_rows):
            rep.append(f"| {g} | {d} | {n} | {max(sp) if sp else 0} | {', '.join(str(int(s)) for s in sp)} | {'⚠️低速保底' if low else ''} |")
        p_rep = os.path.join(outdir, "report.md")
        with open(p_rep, "w", encoding="utf-8") as f:
            f.write("\n".join(rep) + "\n")

        # 生成 App 配置 JSON(TVLive/NewsLive 格式):每频道取最快 1 条线路,按分组顺序排序
        sources = []
        for g in GROUP_ORDER:
            if g not in result:
                continue
            for display, us, _ in sorted(result[g], key=lambda x: x[0]):
                if us:
                    sources.append({"name": display, "url": us[0]})
        app_cfg = {"sources": sources, "playerModeEnabled": True}
        p_app = os.path.join(outdir, "tv_live_config.json")
        with open(p_app, "w", encoding="utf-8") as f:
            json.dump(app_cfg, f, ensure_ascii=False, indent=2)

        return {"fast": n_fast, "full": n_full, "median": median,
                "files": [p_fast, p_full, p_rep, p_app], "app_channels": len(sources),
                "result": result, "speeds": speeds}

    async def publish_gitee(self):
        """把 output/tv_live_config.json 发布到 Gitee release 附件(固定 tag,同名覆盖)"""
        cfg = self.cfg
        token = (cfg.get("gitee_token") or "").strip()
        repo = (cfg.get("gitee_repo") or "").strip()
        tag = (cfg.get("gitee_tag") or "live").strip() or "live"
        if not token or not repo:
            self.emit("log", "⏭ 跳过 Gitee 发布(未配置 token 或仓库)")
            return
        path = os.path.join(cfg["outdir"], "tv_live_config.json")
        if not os.path.exists(path):
            self.emit("log", "⏭ 跳过 Gitee 发布(找不到 tv_live_config.json)")
            return
        base = f"https://gitee.com/api/v5/repos/{repo}"
        auth = {"access_token": token}
        proxy = cfg.get("proxy") or None
        fname = os.path.basename(path)
        to = aiohttp.ClientTimeout(total=30, connect=10)
        async with aiohttp.ClientSession(timeout=to) as s:
            # 1. 找 tag 对应的 release;存在则整体删除(Gitee 附件接口无单独删除能力,
            #    且 assets 列表不带附件 id;删 release 不会删 tag,直链 URL 保持不变)
            rel = None
            async with s.get(f"{base}/releases", params=auth, proxy=proxy) as r:
                rels = await r.json()
                if isinstance(rels, list):
                    rel = next((x for x in rels if x.get("tag_name") == tag), None)
            if rel is not None:
                async with s.delete(f"{base}/releases/{rel['id']}", params=auth, proxy=proxy) as r:
                    self.emit("log", f"Gitee: 删除旧 release(tag={tag}, HTTP {r.status})")
            # 2. 重建 release(挂到已有 tag 上,target_commitish 必填)
            self._check()
            body = {"access_token": token, "tag_name": tag, "name": "直播源自动更新",
                    "body": "IPTV 源筛选工具自动发布,固定 tag 附件覆盖更新,App 固定 URL 拉取。",
                    "target_commitish": "master", "prerelease": False}
            async with s.post(f"{base}/releases", json=body, proxy=proxy) as r:
                rel = await r.json()
                if "id" not in rel:
                    raise RuntimeError(f"创建 release 失败: {str(rel)[:150]}")
            rid = rel["id"]
            # 3. 上传新附件
            self._check()
            data = aiohttp.FormData()
            data.add_field("file", open(path, "rb"), filename=fname, content_type="application/json")
            async with s.post(f"{base}/releases/{rid}/attach_files", params=auth, data=data, proxy=proxy) as r:
                if r.status == 201:
                    url = f"https://gitee.com/{repo}/releases/download/{tag}/{fname}"
                    self.emit("log", f"✅ Gitee 已发布:{url}")
                    self.emit("gitee_done", url)
                else:
                    raise RuntimeError(f"上传失败 HTTP {r.status}: {(await r.text())[:150]}")

    async def run(self):
        urls = [u.strip() for u in self.cfg["subs"].splitlines() if u.strip().startswith("http")]
        if not urls:
            self.emit("error", "没有可用订阅地址")
            return
        t0 = time.monotonic()
        conn = aiohttp.TCPConnector(limit=self.cfg["concurrency"], ssl=False, ttl_dns_cache=300)
        async with aiohttp.ClientSession(connector=conn) as session:
            self.emit("phase", "下载订阅")
            sub_results = await self._download_subs(session, urls)
            if not sub_results:
                self.emit("error", "所有订阅均拉取失败")
                return
            chans = merge_channels(sub_results)
            uniq = list({u for c in chans.values() for u in c["sources"]})
            self.emit("log", f"合并后频道 {len(chans)} 个,唯一 URL {len(uniq)} 个")
            self.emit("phase", "连通验证")
            verify = await self._verify_all(session, uniq, 15, 55)
            stats = {"valid": sum(1 for v, _ in verify.values() if v == "ok"),
                     "warn": sum(1 for v, _ in verify.values() if v == "warn"),
                     "fail": sum(1 for v, _ in verify.values() if v == "fail"),
                     "total_urls": len(uniq)}
            self.emit("log", f"连通验证完成:有效 {stats['valid']} / 存疑 {stats['warn']} / 失效 {stats['fail']}")
            test_urls = [u for u in uniq if verify.get(u, ("fail", ""))[0] in ("ok", "warn")]
            self.emit("phase", "分片测速")
            speeds = await self._measure_all(session, test_urls, 55, 95)
            self.emit("log", f"测速完成:{sum(1 for s in speeds.values() if s > 0)} 个源有速度")
            self.emit("phase", "分类与输出")
            summary = self._write_outputs(chans, speeds, stats)
            self.emit("progress", (95, "分类完成"))
            if (cfg := self.cfg).get("gitee_token") and cfg.get("gitee_auto"):
                self.emit("phase", "发布到 Gitee")
                try:
                    await self.publish_gitee()
                except asyncio.CancelledError:
                    raise
                except Exception as e:
                    self.emit("log", "❌ Gitee 发布失败:" + str(e)[:100])
            self.emit("progress", (100, "完成"))
            self.emit("log", f"✅ 全部完成,耗时 {time.monotonic()-t0:.0f}s:精选 {summary['fast']} 频道 / 完整 {summary['full']} 频道,中位速度 {summary['median']:.0f} KB/s")
            self.emit("done", summary)


# ---------------- CLI ----------------

def cli_main(cfg):
    q = queue.Queue()

    def emit(kind, payload=None):
        if kind == "log":
            print(str(payload), flush=True)
        elif kind == "progress":
            print(f"[{payload[1]}]", flush=True)

    cfg = dict(cfg)
    if not cfg.get("outdir"):
        cfg["outdir"] = os.path.join(base_dir(), "output")
    eng = Engine(cfg, emit)
    try:
        asyncio.run(eng.run())
    except KeyboardInterrupt:
        print("已中断")
    return 0

# ---------------- GUI ----------------

def base_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

def load_cfg():
    path = os.path.join(base_dir(), "config.json")
    cfg = dict(DEFAULT_CFG)
    try:
        with open(path, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except Exception:
        pass
    if not cfg.get("outdir"):
        cfg["outdir"] = os.path.join(base_dir(), "output")
    return cfg

def save_cfg(cfg):
    try:
        with open(os.path.join(base_dir(), "config.json"), "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except Exception:
        pass

def gui_main():
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox

    cfg = load_cfg()
    q = queue.Queue()

    root = tk.Tk()
    root.title("IPTV 源筛选工具")
    root.geometry("860x640")
    root.minsize(760, 560)
    try:
        ico = os.path.join(base_dir(), "app.ico")
        if os.path.exists(ico):
            root.iconbitmap(ico)
    except Exception:
        pass

    style = ttk.Style(root)
    if "vista" in style.theme_names():
        style.theme_use("vista")

    # ---- 顶部:订阅 ----
    frm_top = ttk.LabelFrame(root, text="订阅源(每行一个 URL,支持 TVBox txt 与 m3u)")
    frm_top.pack(fill="both", padx=10, pady=(10, 6))
    txt_subs = tk.Text(frm_top, height=4, wrap="none", font=("Microsoft YaHei UI", 10))
    txt_subs.pack(fill="both", expand=True, padx=6, pady=6)
    txt_subs.insert("1.0", cfg["subs"])

    # ---- 中部:参数 ----
    frm_opt = ttk.LabelFrame(root, text="参数")
    frm_opt.pack(fill="x", padx=10, pady=6)
    v = {}
    def spin(label, key, frm, r, c, w=6, to=200):
        ttk.Label(frm, text=label).grid(row=r, column=c, sticky="e", padx=(10, 2), pady=4)
        v[key] = tk.StringVar(value=str(cfg[key]))
        ttk.Spinbox(frm, from_=1, to=to, textvariable=v[key], width=w).grid(row=r, column=c + 1, sticky="w")
    spin("并发数", "concurrency", frm_opt, 0, 0)
    spin("超时(秒)", "timeout", frm_opt, 0, 2)
    spin("淘汰线(KB/s)", "min_speed", frm_opt, 0, 4)
    spin("每频道保留", "top_n", frm_opt, 0, 6)
    ttk.Label(frm_opt, text="代理(可空,如 http://127.0.0.1:7897)").grid(row=1, column=0, sticky="e", padx=(10, 2))
    v["proxy"] = tk.StringVar(value=cfg["proxy"])
    ttk.Entry(frm_opt, textvariable=v["proxy"], width=32).grid(row=1, column=1, columnspan=2, sticky="we", pady=4)
    ttk.Label(frm_opt, text="输出目录").grid(row=1, column=2, sticky="e", padx=(10, 2))
    v["outdir"] = tk.StringVar(value=cfg["outdir"])
    e_out = ttk.Entry(frm_opt, textvariable=v["outdir"])
    e_out.grid(row=1, column=3, columnspan=3, sticky="we", pady=4)
    def pick_dir():
        d = filedialog.askdirectory()
        if d:
            v["outdir"].set(d.replace("/", "\\"))
    ttk.Button(frm_opt, text="…", width=3, command=pick_dir).grid(row=1, column=6, padx=4)
    frm_opt.columnconfigure(3, weight=1)

    # ---- Gitee 发布区 ----
    frm_gh = ttk.LabelFrame(root, text="Gitee 自动发版(筛选完成后自动上传 tv_live_config.json 到 release 附件,固定 tag 覆盖更新)")
    frm_gh.pack(fill="x", padx=10, pady=6)
    ttk.Label(frm_gh, text="Token").grid(row=0, column=0, sticky="e", padx=(10, 2), pady=4)
    v["gitee_token"] = tk.StringVar(value=cfg.get("gitee_token", ""))
    ttk.Entry(frm_gh, textvariable=v["gitee_token"], show="*", width=24).grid(row=0, column=1, sticky="w")
    ttk.Label(frm_gh, text="仓库(owner/repo)").grid(row=0, column=2, sticky="e", padx=(10, 2))
    v["gitee_repo"] = tk.StringVar(value=cfg.get("gitee_repo", DEFAULT_CFG["gitee_repo"]))
    ttk.Entry(frm_gh, textvariable=v["gitee_repo"], width=26).grid(row=0, column=3, sticky="w")
    ttk.Label(frm_gh, text="Tag").grid(row=0, column=4, sticky="e", padx=(10, 2))
    v["gitee_tag"] = tk.StringVar(value=cfg.get("gitee_tag", DEFAULT_CFG["gitee_tag"]))
    ttk.Entry(frm_gh, textvariable=v["gitee_tag"], width=10).grid(row=0, column=5, sticky="w")
    v["gitee_auto"] = tk.BooleanVar(value=bool(cfg.get("gitee_auto", True)))
    ttk.Checkbutton(frm_gh, text="筛选后自动发布", variable=v["gitee_auto"]).grid(row=0, column=6, padx=8)
    b_pub = ttk.Button(frm_gh, text="⬆ 立即发布当前输出", command=lambda: publish_now())
    b_pub.grid(row=0, column=7, padx=4)
    frm_gh.columnconfigure(1, weight=1)

    def publish_now():
        cfg_now = {
            "gitee_token": v["gitee_token"].get().strip(),
            "gitee_repo": v["gitee_repo"].get().strip(),
            "gitee_tag": v["gitee_tag"].get().strip() or "live",
            "gitee_auto": v["gitee_auto"].get(),
            "proxy": v["proxy"].get().strip(),
            "outdir": v["outdir"].get().strip(),
            "subs": "", "concurrency": 30, "timeout": 15, "min_speed": 80, "top_n": 3,
        }
        if not cfg_now["gitee_token"]:
            messagebox.showwarning("提示", "请先填写 Gitee 私人令牌")
            return
        if not os.path.exists(os.path.join(cfg_now["outdir"], "tv_live_config.json")):
            messagebox.showwarning("提示", "输出目录中没有 tv_live_config.json,请先完成一次筛选")
            return
        b_pub.configure(state="disabled")
        def job():
            eng = Engine(cfg_now, lambda k, p=None: q.put((k, p)))
            try:
                asyncio.run(eng.publish_gitee())
            except Exception as e:
                q.put(("log", "❌ 发布失败:" + str(e)[:100]))
            finally:
                q.put(("published", None))
        threading.Thread(target=job, daemon=True).start()

    # ---- 按钮 + 进度 ----
    frm_btn = ttk.Frame(root)
    frm_btn.pack(fill="x", padx=10, pady=4)
    pb = ttk.Progressbar(frm_btn, mode="determinate")
    pb.pack(side="left", fill="x", expand=True, padx=(0, 8))
    lb_status = ttk.Label(frm_btn, text="就绪", width=28, anchor="w")
    lb_status.pack(side="left")
    running = {"flag": False}
    eng_holder = {}

    def log(msg):
        t_log.configure(state="normal")
        t_log.insert("end", msg + "\n")
        t_log.see("end")
        t_log.configure(state="disabled")

    def start():
        if running["flag"]:
            return
        cfg.update({
            "subs": txt_subs.get("1.0", "end").strip(),
            "concurrency": int(v["concurrency"].get()),
            "timeout": int(v["timeout"].get()),
            "min_speed": int(v["min_speed"].get()),
            "top_n": int(v["top_n"].get()),
            "proxy": v["proxy"].get().strip(),
            "outdir": v["outdir"].get().strip(),
            "gitee_token": v["gitee_token"].get().strip(),
            "gitee_repo": v["gitee_repo"].get().strip(),
            "gitee_tag": v["gitee_tag"].get().strip() or "live",
            "gitee_auto": v["gitee_auto"].get(),
        })
        if not cfg["outdir"]:
            messagebox.showwarning("提示", "请设置输出目录")
            return
        save_cfg(cfg)
        running["flag"] = True
        b_start.configure(state="disabled")
        b_stop.configure(state="normal")
        t_log.configure(state="normal")
        t_log.delete("1.0", "end")
        t_log.configure(state="disabled")
        tree.delete(*tree.get_children())
        eng = Engine(cfg, lambda k, p=None: q.put((k, p)))
        eng_holder["engine"] = eng
        threading.Thread(target=lambda: _worker(eng), daemon=True).start()

    def _worker(eng):
        try:
            asyncio.run(eng.run())
        except asyncio.CancelledError:
            q.put(("log", "⏹ 已停止"))
            q.put(("stopped", None))
        except Exception as e:
            q.put(("error", f"{type(e).__name__}: {e}"))

    def stop():
        eng = eng_holder.get("engine")
        if eng:
            eng.stop_flag.set()
            lb_status.configure(text="停止中…")
            b_stop.configure(state="disabled")

    def open_outdir():
        d = v["outdir"].get().strip()
        if os.path.isdir(d):
            os.startfile(d)
        else:
            messagebox.showinfo("提示", f"目录不存在:{d}")

    b_start = ttk.Button(frm_btn, text="▶ 开始筛选", command=start)
    b_start.pack(side="left", padx=2)
    b_stop = ttk.Button(frm_btn, text="■ 停止", command=stop, state="disabled")
    b_stop.pack(side="left", padx=2)
    ttk.Button(frm_btn, text="打开输出目录", command=open_outdir).pack(side="left", padx=2)

    # ---- 下方:日志 + 结果 ----
    nb = ttk.Notebook(root)
    nb.pack(fill="both", expand=True, padx=10, pady=(4, 10))
    f_log = ttk.Frame(nb)
    nb.add(f_log, text="运行日志")
    t_log = tk.Text(f_log, state="disabled", font=("Consolas", 9), bg="#1e1e1e", fg="#d4d4d4")
    sb = ttk.Scrollbar(f_log, command=t_log.yview)
    t_log.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    t_log.pack(fill="both", expand=True)

    f_res = ttk.Frame(nb)
    nb.add(f_res, text="结果浏览")
    cols = ("speed", "lines")
    tree = ttk.Treeview(f_res, columns=cols, show="tree headings")
    tree.heading("#0", text="分组 / 频道")
    tree.heading("speed", text="最快 KB/s")
    tree.heading("lines", text="线路数")
    tree.column("#0", width=420)
    tree.column("speed", width=90, anchor="e")
    tree.column("lines", width=70, anchor="e")
    sbr = ttk.Scrollbar(f_res, command=tree.yview)
    tree.configure(yscrollcommand=sbr.set)
    sbr.pack(side="right", fill="y")
    tree.pack(fill="both", expand=True)

    def fill_tree(result_map, speeds):
        for g in GROUP_ORDER:
            if g not in result_map:
                continue
            gi = tree.insert("", "end", text=f"{g}({len(result_map[g])})", open=False)
            for display, keep, low in sorted(result_map[g], key=lambda x: x[0]):
                fastest = max((speeds.get(u, 0) for u in keep), default=0)
                tag = " ⚠️低速" if low else ""
                tree.insert(gi, "end", text=f"{display}{tag}",
                            values=(int(fastest), len(keep)))

    def poll():
        try:
            while True:
                kind, payload = q.get_nowait()
                if kind == "log":
                    log(str(payload))
                elif kind == "progress":
                    pb["value"] = payload[0]
                    lb_status.configure(text=payload[1])
                elif kind == "phase":
                    lb_status.configure(text=f"阶段:{payload}")
                elif kind == "done":
                    summary = payload
                    lb_status.configure(text=f"完成:精选 {summary['fast']} / 完整 {summary['full']} 频道")
                    fill_tree(summary["result"], summary["speeds"])
                    b_start.configure(state="normal")
                    running["flag"] = False
                    messagebox.showinfo("完成",
                        f"筛选完成!\n精选 {summary['fast']} 个频道(全线路达标)\n完整 {summary['full']} 个频道\n\n输出目录:\n{cfg['outdir']}")
                elif kind == "published":
                    b_pub.configure(state="normal")
                elif kind == "gitee_done":
                    log("✅ 已发布到 Gitee:" + str(payload))
                elif kind == "error":
                    log("❌ " + str(payload))
                    lb_status.configure(text="出错")
                    b_start.configure(state="normal")
                    b_stop.configure(state="disabled")
                    running["flag"] = False
                    messagebox.showerror("错误", str(payload))
                elif kind == "stopped":
                    b_start.configure(state="normal")
                    b_stop.configure(state="disabled")
                    running["flag"] = False
        except queue.Empty:
            pass
        root.after(120, poll)

    root.after(120, poll)
    root.mainloop()

if __name__ == "__main__":
    if "--cli" in sys.argv:
        cli_main(load_cfg())
    else:
        gui_main()
