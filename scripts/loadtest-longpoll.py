"""长轮询线程占用实验

背景
----
Worker 领取任务时用 ``waitSeconds`` 做长轮询。在服务端，每个处于等待中的
Worker 都会**占住一个 Tomcat 请求线程**，直到等到任务或超时。

假设
----
Tomcat 默认 ``max-threads=200``。当长轮询中的 Worker 数量接近或超过这个值时，
平台将没有空闲线程处理**其它任何请求**（包括用户提交任务、管理端页面）。

本脚本验证这个悬崖：
  1. 先测一个探针请求的基线延迟（无长轮询占用）
  2. 再开 N 个长轮询连接占住线程
  3. 在占用期间再次测同一个探针请求，观察是否被拖慢甚至挂起

用法::

    python scripts/loadtest-longpoll.py --holders 250 --wait 15
"""

from __future__ import annotations

import argparse
import statistics
import threading
import time

import requests

DEV_WORKER_TOKEN = "wkr_00000000-0000-0000-0000-000000000001_devsecret"


def probe(base: str, samples: int = 5) -> tuple[float, float, int]:
    """测探针接口（permitAll，最轻量）的延迟。返回 (p50_ms, max_ms, 失败数)。"""
    latencies: list[float] = []
    failures = 0
    for _ in range(samples):
        started = time.perf_counter()
        try:
            response = requests.get(f"{base}/actuator/health", timeout=60)
            if response.status_code == 200:
                latencies.append((time.perf_counter() - started) * 1000)
            else:
                failures += 1
        except Exception:  # noqa: BLE001
            failures += 1
    if not latencies:
        return (float("inf"), float("inf"), failures)
    return (statistics.median(latencies), max(latencies), failures)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--holders", type=int, default=250,
                        help="并发长轮询数量，应超过 Tomcat max-threads(默认 200)")
    parser.add_argument("--wait", type=int, default=15, help="每个长轮询的 waitSeconds")
    args = parser.parse_args()

    print("=" * 78)
    print(" 长轮询线程占用实验")
    print("=" * 78)
    print(f"  目标            : {args.base}")
    print(f"  长轮询数量      : {args.holders}  (Tomcat 默认 max-threads = 200)")
    print(f"  单个 waitSeconds: {args.wait}")

    baseline_p50, baseline_max, baseline_fail = probe(args.base)
    print(f"\n  基线探针延迟    : p50 {baseline_p50:.1f} ms / max {baseline_max:.1f} ms "
          f"(失败 {baseline_fail})")

    stop = threading.Event()
    started_count = [0]
    finished_count = [0]
    lock = threading.Lock()

    def holder() -> None:
        session = requests.Session()
        session.headers["Authorization"] = f"Bearer {DEV_WORKER_TOKEN}"
        with lock:
            started_count[0] += 1
        try:
            session.post(
                f"{args.base}/api/worker/tasks/claim",
                json={"waitSeconds": args.wait},
                timeout=args.wait + 30,
            )
        except Exception:  # noqa: BLE001
            pass
        finally:
            with lock:
                finished_count[0] += 1
            session.close()

    threads = [threading.Thread(target=holder, daemon=True) for _ in range(args.holders)]
    for thread in threads:
        thread.start()

    # 给它们一点时间真正进入等待状态
    time.sleep(3)
    with lock:
        started = started_count[0]
        finished = finished_count[0]
    print(f"  已发出长轮询    : {started}，其中已完成 {finished}（其余正在占住线程）")

    print("  正在测量占用期间的探针延迟 ...")
    busy_p50, busy_max, busy_fail = probe(args.base, samples=3)
    print(f"\n  占用期间探针延迟: p50 {busy_p50:.1f} ms / max {busy_max:.1f} ms "
          f"(失败 {busy_fail})")

    print("\n" + "=" * 78)
    print(" 结论")
    print("=" * 78)
    degradation = busy_p50 / baseline_p50 if baseline_p50 else float("inf")
    print(f"  p50 延迟放大倍数: {degradation:.1f}x")
    print(f"  最坏单次延迟    : {busy_max:.0f} ms（基线最坏 {baseline_max:.0f} ms）")
    # 注意：线程池耗尽的典型表现不是 p50 变差，而是「少数请求被拖到秒级以上」。
    # 因此必须看 max，只看中位数会漏掉真正的故障信号。
    if busy_fail > 0:
        print("\n  探针出现失败：线程池已被长轮询耗尽，平台无法处理其它请求。")
    elif busy_max >= 5000:
        print("\n  出现秒级以上阻塞：长轮询已占满线程池，正常请求被迫排队等待，")
        print("  用户可感知为「页面卡住/请求超时」。这是 V1 最硬的并发上限。")
    elif busy_max >= 1000:
        print("\n  个别请求被拖到秒级：线程资源紧张，接近瓶颈。")
    else:
        print("\n  探针未受明显影响：当前线程池仍有余量。")

    stop.set()
    print(f"\n  等待长轮询自然结束（最多 {args.wait} 秒）...")
    for thread in threads:
        thread.join(timeout=args.wait + 10)
    with lock:
        print(f"  最终完成        : {finished_count[0]}/{args.holders}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
