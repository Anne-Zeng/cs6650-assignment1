import csv
import math
import sys
from datetime import datetime
from pathlib import Path

import matplotlib.pyplot as plt


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


csv_path = Path(sys.argv[1])

first_send = None
last_completion = None
successful_completions = []

with csv_path.open(newline="", encoding="utf-8") as file:
    for row in csv.DictReader(file):
        sent = parse_time(row["timestamp"])
        completed = parse_time(row["responseTimestamp"])

        if first_send is None or sent < first_send:
            first_send = sent

        if last_completion is None or completed > last_completion:
            last_completion = completed

        if int(row["statusCode"]) == 201:
            successful_completions.append(completed)

if first_send is None:
    raise SystemExit("CSV contains no samples.")

duration = (last_completion - first_send).total_seconds()

if duration <= 0:
    raise SystemExit("Measurement duration must be positive.")

bucket_seconds = 10
bucket_count = max(1, math.ceil(duration / bucket_seconds))
counts = [0] * bucket_count

for completed in successful_completions:
    elapsed = (completed - first_send).total_seconds()
    index = min(int(elapsed // bucket_seconds), bucket_count - 1)
    counts[index] += 1

midpoints = []
throughputs = []

for index, count in enumerate(counts):
    start = index * bucket_seconds
    end = min(start + bucket_seconds, duration)
    actual_seconds = end - start
    throughput = count / actual_seconds

    midpoints.append((start + end) / 2)
    throughputs.append(throughput)

    print(
        f"{start:.2f}–{end:.2f}s: "
        f"{count} successful swipes, "
        f"{throughput:.2f} swipes/sec"
    )

fig, ax = plt.subplots(figsize=(9, 5))

ax.plot(midpoints, throughputs, marker="o")
ax.set_xlabel("Seconds since first HTTP attempt")
ax.set_ylabel("Successful swipes / second")
ax.set_title("EC2 ingest throughput — 128 main threads")
# ax.set_title("Local ingest throughput — 10-second buckets")
ax.set_xlim(0, duration)
ax.set_ylim(bottom=0)
ax.grid(True, alpha=0.3)

fig.text(
    0.5, 0.02,
    "Includes warmup. Final partial bucket uses its actual duration.",
    ha="center", fontsize=9
)

fig.tight_layout(rect=(0, 0.05, 1, 1))

output_path = csv_path.with_name(csv_path.stem + "-throughput.png")
fig.savefig(output_path, dpi=200)
plt.close(fig)

print(f"Total successful swipes: {sum(counts)}")
print(f"Chart saved to: {output_path.resolve()}")