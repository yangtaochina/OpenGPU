"""智由 AI 视频任务平台 — 并发压测脚本

针对两个热点路径做真实并发测量：

  1. 任务创建  POST /api/tasks
  2. 原子领取  POST /api/worker/tasks/claim   （SKIP LOCKED 的正确性与吞吐）

关注指标：
  * 成功率、p50 / p95 / p99 延迟、吞吐（req/s）
  * **同一个任务是否被领取了两次**（并发正确性的核心不变量）

用法::

    python scripts/loadtest.py
    python scripts/loadtest.py --tasks 300 --concurrency 50
    python scripts/loadtest.py --base http://localhost:8080 --skip-create

依赖: requests（worker 的 requirements.txt 已包含）
"""

from __future__ import annotations

import argparse
import statistics
import sys
import threading
import time
from dataclasses import dataclass, field

import requests

DEFAULT_BASE = "http://localhost:8080"
DEV_WORKER_TOKEN = "wkr_00000000-0000-0000-0000-000000000001_devsecret"


# ---------------------------------------------------------------------------
@dataclass
class Sample:
    """线程安全的延迟/错误收集器。"""

    latencies: list[float] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def ok(self, seconds: float) -> None:
        with self._lock:
            self.latencies.append(seconds)

    def fail(self, message: str) -> None:
        with self._lock:
            self.errors.append(message)

    @property
    def count(self) -> int:
        return len(self.latencies)

    def percentile(self, pct: float) -> float:
        if not self.latencies:
            return 0.0
        ordered = sorted(self.latencies)
        index = min(len(ordered) - 1, int(round((pct / 100.0) * (len(ordered) - 1))))
        return ordered[index] * 1000.0  # ms

    def report(self, title: str, wall_seconds: float) -> None:
        print(f"\n  {title}")
        print(f"    成功请求   : {self.count}")
        print(f"    失败请求   : {len(self.errors)}")
        if self.errors:
            for message in self.errors[:5]:
                print(f"      - {message}")
        if self.count:
            print(f"    耗时       : {wall_seconds:.2f}s")
            print(f"    吞吐       : {self.count / wall_seconds:.1f} req/s")
            print(f"    p50 / p95  : {self.percentile(50):.1f} ms / {self.percentile(95):.1f} ms")
            print(f"    p99 / max  : {self.percentile(99):.1f} ms / {max(self.latencies) * 1000:.1f} ms")
            print(f"    平均       : {statistics.mean(self.latencies) * 1000:.1f} ms")


# ---------------------------------------------------------------------------
def login(base: str, username: str, password: str) -> str:
    response = requests.post(
        f"{base}/api/auth/login",
        json={"username": username, "password": password},
        timeout=15,
    )
    response.raise_for_status()
    return response.json()["data"]["token"]


def run_concurrently(worker_count: int, job) -> float:
    """启动 worker_count 个线程，用一个屏障让它们尽量同时起跑。"""
    barrier = threading.Barrier(worker_count)
    threads = []

    for index in range(worker_count):
        def target(i=index):
            barrier.wait()
            job(i)

        thread = threading.Thread(target=target, daemon=True)
        thread.start()
        threads.append(thread)

    started = time.perf_counter()
    for thread in threads:
        thread.join()
    return time.perf_counter() - started


# ---------------------------------------------------------------------------
def phase_create(base: str, token: str, total: int, concurrency: int) -> Sample:
    sample = Sample()
    base_url = base

    def job(index: int) -> None:
        session = requests.Session()
        session.headers["Authorization"] = f"Bearer {token}"
        per_thread = total // concurrency + (1 if index < total % concurrency else 0)
        for i in range(per_thread):
            payload = {"prompt": f"压测任务 #{index}-{i}：星空下的机械鹿"}
            started = time.perf_counter()
            try:
                response = session.post(f"{base_url}/api/tasks", json=payload, timeout=30)
                elapsed = time.perf_counter() - started
                if response.status_code == 201 and response.json().get("code") == 0:
                    sample.ok(elapsed)
                else:
                    sample.fail(f"HTTP {response.status_code}: {response.text[:160]}")
            except Exception as exc:  # noqa: BLE001
                sample.fail(f"{type(exc).__name__}: {exc}")
        session.close()

    wall = run_concurrently(concurrency, job)
    sample.report(f"任务创建（{concurrency} 并发 × 共 {total} 个）", wall)
    return sample


def phase_claim(base: str, worker_token: str, concurrency: int,
                max_rounds: int) -> tuple[Sample, list[str], int]:
    """并发领取，直到队列为空。返回 (样本, 领到的 taskId 列表, 204 次数)。"""
    sample = Sample()
    claimed: list[str] = []
    empty_responses = [0]
    lock = threading.Lock()
    base_url = base

    def job(index: int) -> None:
        session = requests.Session()
        session.headers["Authorization"] = f"Bearer {worker_token}"
        for _ in range(max_rounds):
            started = time.perf_counter()
            try:
                response = session.post(
                    f"{base_url}/api/worker/tasks/claim", json={"waitSeconds": 0}, timeout=30
                )
                elapsed = time.perf_counter() - started
                if response.status_code == 204:
                    with lock:
                        empty_responses[0] += 1
                    return
                if response.status_code == 200:
                    body = response.json()
                    if body.get("code") == 0 and body.get("data"):
                        with lock:
                            claimed.append(body["data"]["taskId"])
                        sample.ok(elapsed)
                        continue
                    sample.fail(f"业务错误: {str(body)[:160]}")
                    return
                sample.fail(f"HTTP {response.status_code}: {response.text[:160]}")
                return
            except Exception as exc:  # noqa: BLE001
                sample.fail(f"{type(exc).__name__}: {exc}")
                return
        sample.fail(f"达到最大轮次 {max_rounds}，队列可能未清空")
        session.close()

    wall = run_concurrently(concurrency, job)
    sample.report(f"并发领取（{concurrency} 并发同时抢）", wall)
    return sample, claimed, empty_responses[0]


# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(description="OpenGPU 并发压测")
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--tasks", type=int, default=150, help="创建的任务数量")
    parser.add_argument("--concurrency", type=int, default=30, help="并发线程数")
    parser.add_argument("--worker-token", default=DEV_WORKER_TOKEN)
    parser.add_argument("--skip-create", action="store_true", help="只测领取（复用队列已有任务）")
    args = parser.parse_args()

    print("=" * 78)
    print(" OpenGPU 并发压测")
    print("=" * 78)
    print(f"  目标        : {args.base}")
    print(f"  任务数      : {args.tasks}")
    print(f"  并发数      : {args.concurrency}")

    token = login(args.base, "admin", "admin123")
    print("  登录        : OK")

    if not args.skip_create:
        phase_create(args.base, token, args.tasks, args.concurrency)
    else:
        print("\n  已跳过任务创建阶段")

    # 领取阶段：轮次上限要足够大，保证并发线程能把队列清空
    # 单个线程可能连续抢到多个任务，所以按「人均任务数 × 3 + 余量」给足空间
    max_rounds = max(30, (args.tasks // max(1, args.concurrency)) * 3 + 20)
    claim_sample, claimed, empties = phase_claim(
        args.base, args.worker_token, args.concurrency, max_rounds
    )

    unique = set(claimed)
    duplicates = len(claimed) - len(unique)

    print("\n" + "=" * 78)
    print(" 并发正确性")
    print("=" * 78)
    print(f"  领取到的任务总数     : {len(claimed)}")
    print(f"  去重后的任务数       : {len(unique)}")
    print(f"  重复领取（必须为 0） : {duplicates}   {'<-- OK' if duplicates == 0 else '<-- 严重问题!'}")
    print(f"  队列空响应(204) 次数 : {empties}")

    if duplicates:
        print("\n存在任务被重复分配，SKIP LOCKED / 租约逻辑可能被破坏！")
        return 1
    print("\n未发现任务被重复分配。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
