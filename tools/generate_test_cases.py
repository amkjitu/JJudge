#!/usr/bin/env python3
"""
Generates the judge's test cases from reference solutions.

Run from the repository root:

    python tools/generate_test_cases.py

Writes arena-api/src/main/resources/mongo/problem-test-cases.json, which the seeder loads
into MongoDB on startup.

Why generated rather than hand-written
--------------------------------------
Expected output has to come from *something*. Typing it by hand means the answers are only as
good as the person typing them, and a wrong expected output is the worst kind of bug in a judge:
it fails correct submissions and there is nothing in the code to find. Here each problem has a
reference solution, and every expected output is that solution's actual output for the input.

The reference solutions are deliberately the straightforward version, not the clever one -
O(n^2) where the intended answer is O(n log n). They only have to be *right*, and a simple
implementation is easier to be sure about. Case sizes stay small enough that the slow reference
still finishes.

Each problem gets:
  - the worked examples from its statement, so what a reader is shown is genuinely judged
  - hand-picked edge cases, which is where the interesting failures live
  - randomised cases at a fixed seed, so the file is reproducible

Nothing is written unless every statement's worked examples match their sample cases, and every
problem has both. See check_against_statements.
"""

import json
import random
import sys
from pathlib import Path

# Resolves because Python puts a script's own directory on sys.path, so this works from the
# repository root as documented above.
import reference_solutions

OUT = Path("arena-api/src/main/resources/mongo/problem-test-cases.json")
STATEMENTS = Path("arena-api/src/main/resources/mongo/problem-statements.json")

# Fixed so regenerating produces an identical file - a diff should mean somebody changed the
# generator, not that the dice rolled differently.
random.seed(20260811)


# --------------------------------------------------------------------------------------------
# Reference solutions. Each takes the raw stdin text and returns the expected stdout text.
# --------------------------------------------------------------------------------------------

def two_sum(text):
    lines = text.split("\n")
    n, t = map(int, lines[0].split())
    a = list(map(int, lines[1].split()))
    for i in range(n):
        for j in range(i + 1, n):
            if a[i] + a[j] == t:
                return f"{i} {j}"
    raise AssertionError("every case must have a solution")


def reverse_words(text):
    return " ".join(reversed(text.strip().split()))


def merge_sorted(text):
    lines = text.split("\n")
    first = list(map(int, lines[0].split()))[1:]
    second = list(map(int, lines[1].split()))[1:]
    return " ".join(map(str, sorted(first + second)))


def parentheses_stream(text):
    stream = text.strip()
    pairs = {")": "(", "]": "[", "}": "{"}
    stack, dead, out = [], False, []
    for ch in stream:
        if not dead:
            if ch in "([{":
                stack.append(ch)
            elif ch in pairs:
                if stack and stack[-1] == pairs[ch]:
                    stack.pop()
                else:
                    dead = True
        out.append("N" if dead else "Y")
    return "".join(out)


def search_boundaries(text):
    lines = text.split("\n")
    n, k = map(int, lines[0].split())
    a = list(map(int, lines[1].split()))
    queries = list(map(int, lines[2].split()))
    out = []
    for q in queries:
        lower = sum(1 for x in a if x < q)
        upper = sum(1 for x in a if x <= q)
        out.append(f"{lower} {upper}")
    return "\n".join(out)


def longest_unique(text):
    s = text.strip("\n")
    best, start, seen = 0, 0, {}
    for i, ch in enumerate(s):
        if ch in seen and seen[ch] >= start:
            start = seen[ch] + 1
        seen[ch] = i
        best = max(best, i - start + 1)
    return str(best)


def max_subarray(text):
    lines = text.split("\n")
    a = list(map(int, lines[1].split()))
    best = running = a[0]
    for x in a[1:]:
        running = x + max(running, 0)
        best = max(best, running)
    return str(best)


def meeting_rooms(text):
    lines = text.split("\n")
    n = int(lines[0])
    events = []
    for i in range(1, n + 1):
        s, e = map(int, lines[i].split())
        events.append((s, 1))
        events.append((e, -1))
    # Half-open intervals: an end at t frees the room before a start at t claims it, so ends
    # sort first at equal timestamps.
    events.sort(key=lambda p: (p[0], p[1]))
    best = running = 0
    for _, delta in events:
        running += delta
        best = max(best, running)
    return str(best)


def lis(text):
    lines = text.split("\n")
    a = list(map(int, lines[1].split()))
    import bisect
    tails = []
    for x in a:
        i = bisect.bisect_left(tails, x)
        if i == len(tails):
            tails.append(x)
        else:
            tails[i] = x
    return str(len(tails))


def edit_distance(text):
    lines = text.split("\n")
    s, t = lines[0].strip(), lines[1].strip()
    previous = list(range(len(t) + 1))
    for i in range(1, len(s) + 1):
        current = [i] + [0] * len(t)
        for j in range(1, len(t) + 1):
            if s[i - 1] == t[j - 1]:
                current[j] = previous[j - 1]
            else:
                current[j] = 1 + min(previous[j - 1], previous[j], current[j - 1])
        previous = current
    return str(previous[len(t)])


# --------------------------------------------------------------------------------------------
# Inputs per problem: the statement's samples first, then edge cases, then random ones.
# --------------------------------------------------------------------------------------------

def rand_ints(n, lo, hi):
    return [random.randint(lo, hi) for _ in range(n)]


def two_sum_inputs():
    yield "4 9\n2 7 11 15", True
    yield "3 6\n3 2 4", True
    yield "2 0\n0 0", False                      # the smallest possible array
    yield "2 -3\n-1 -2", False                   # negatives
    yield "5 1000000000\n999999999 1 5 7 9", False  # values at the constraint limit
    for _ in range(4):
        n = random.randint(2, 60)
        a = rand_ints(n, -1000, 1000)
        i, j = random.sample(range(n), 2)
        yield f"{n} {a[i] + a[j]}\n{' '.join(map(str, a))}", False


def reverse_words_inputs():
    yield "the sky  is   blue", True
    yield "hello", False                          # one word, no spaces to collapse
    yield "   leading and trailing   ", False     # the trim the statement calls for
    yield "a  b   c    d", False                  # several runs of spaces
    yield "punctuation, stays! attached?", False
    yield " ".join(f"w{i}" for i in range(2000)), False   # a long line
    for _ in range(3):
        words = [f"word{random.randint(1, 999)}" for _ in range(random.randint(2, 40))]
        gaps = ["".join(" " for _ in range(random.randint(1, 3))) for _ in words[:-1]]
        text = "".join(w + g for w, g in zip(words, gaps)) + words[-1]
        yield text, False


def merge_sorted_inputs():
    yield "3 1 2 4\n3 1 3 4", True
    yield "0\n0", False                           # both lists empty
    yield "0\n3 1 2 3", False                     # one side empty
    yield "3 1 1 1\n3 1 1 1", False               # all duplicates
    for _ in range(4):
        n, m = random.randint(1, 40), random.randint(1, 40)
        a = sorted(rand_ints(n, -100, 100))
        b = sorted(rand_ints(m, -100, 100))
        yield f"{n} {' '.join(map(str, a))}\n{m} {' '.join(map(str, b))}", False


def parentheses_inputs():
    yield "([)]", True
    yield "((((", False                           # never balanced, always completable
    yield ")", False                              # dead on the first character
    yield "{[()]}", False
    yield "()" * 500, False
    for _ in range(4):
        yield "".join(random.choice("()[]{}") for _ in range(random.randint(1, 60))), False


def search_boundaries_inputs():
    yield "5 2\n1 2 2 2 5\n2 3", True
    yield "1 1\n7\n7", False                      # single element, exact hit
    yield "4 2\n5 5 5 5\n5 4", False              # all identical
    yield "3 2\n-10 0 10\n-1000000000 1000000000", False   # queries outside the range
    for _ in range(4):
        n, k = random.randint(1, 50), random.randint(1, 10)
        a = sorted(rand_ints(n, -50, 50))
        q = rand_ints(k, -60, 60)
        yield f"{n} {k}\n{' '.join(map(str, a))}\n{' '.join(map(str, q))}", False


def longest_unique_inputs():
    yield "abcabcbb", True
    yield "bbbbb", True
    yield "a", False
    yield "abcdefghij", False                     # already all distinct
    yield "ab" * 3000, False
    for _ in range(4):
        alphabet = "abcdefg"
        yield "".join(random.choice(alphabet) for _ in range(random.randint(1, 200))), False


def max_subarray_inputs():
    yield "9\n-2 1 -3 4 -1 2 1 -5 4", True
    yield "3\n-5 -2 -9", True                     # all negative: must not return 0
    yield "1\n-1", False
    yield "1\n1000000000", False
    yield "4\n1000000000 1000000000 1000000000 1000000000", False   # overflows 32-bit
    for _ in range(4):
        n = random.randint(1, 200)
        yield f"{n}\n{' '.join(map(str, rand_ints(n, -10000, 10000)))}", False


def meeting_rooms_inputs():
    yield "3\n0 30\n5 10\n15 20", True
    yield "2\n5 10\n10 20", True                  # half-open: these share one room
    yield "1\n0 1", False
    yield "3\n0 100\n0 100\n0 100", False         # fully overlapping
    for _ in range(4):
        n = random.randint(1, 60)
        rows = []
        for _ in range(n):
            s = random.randint(0, 500)
            rows.append(f"{s} {s + random.randint(1, 100)}")
        yield f"{n}\n" + "\n".join(rows), False


def lis_inputs():
    yield "8\n10 9 2 5 3 7 101 18", True
    yield "4\n7 7 7 7", True                      # strictly increasing, so the answer is 1
    yield "1\n5", False
    yield "5\n5 4 3 2 1", False                   # strictly decreasing
    for _ in range(4):
        n = random.randint(1, 300)
        yield f"{n}\n{' '.join(map(str, rand_ints(n, -1000, 1000)))}", False


def edit_distance_inputs():
    yield "horse\nros", True
    yield "abc\nabc", True                        # already equal
    yield "a\nb", False
    yield "abcdef\n" + "x", False                 # one side much shorter
    for _ in range(4):
        alphabet = "abcde"
        s = "".join(random.choice(alphabet) for _ in range(random.randint(1, 60)))
        t = "".join(random.choice(alphabet) for _ in range(random.randint(1, 60)))
        yield f"{s}\n{t}", False


PROBLEMS = [
    ("two-sum-revisited", two_sum, two_sum_inputs),
    ("reverse-the-words", reverse_words, reverse_words_inputs),
    ("merge-two-sorted-lists", merge_sorted, merge_sorted_inputs),
    ("valid-parentheses-stream", parentheses_stream, parentheses_inputs),
    ("binary-search-boundaries", search_boundaries, search_boundaries_inputs),
    ("longest-unique-substring", longest_unique, longest_unique_inputs),
    ("maximum-subarray-sum", max_subarray, max_subarray_inputs),
    ("meeting-rooms-scheduler", meeting_rooms, meeting_rooms_inputs),
    ("longest-increasing-subsequence", lis, lis_inputs),
    ("edit-distance", edit_distance, edit_distance_inputs),
]

# The rest of the catalogue lives in its own module purely for size.
PROBLEMS += reference_solutions.PROBLEMS


def check_against_statements(documents):
    """
    Every worked example in a statement must appear as a sample case with the same output.

    This is the one guard that matters. The statement is what a person reads and the test cases
    are what their code is judged against, and the two are edited at different times by different
    hands - so they drift, and when they do the platform shows someone one thing and marks them
    against another. Here the expected output comes from the reference solution, so a mismatch
    means the *statement* is wrong, and it is reported with the value that would make it right.

    Returns a list of complaints; empty means they agree.
    """
    if not STATEMENTS.exists():
        return [f"{STATEMENTS} is missing, so nothing could be cross-checked"]

    statements = {d["slug"]: d for d in json.loads(STATEMENTS.read_text(encoding="utf-8"))}
    by_slug = {d["slug"]: d for d in documents}
    complaints = []

    for slug, statement in sorted(statements.items()):
        if slug not in by_slug:
            complaints.append(f"{slug}: has a statement but no test cases")
            continue

        samples = {c["input"]: c["expectedOutput"]
                   for c in by_slug[slug]["cases"] if c["sample"]}
        for example in statement.get("examples", []):
            shown_in, shown_out = example["input"], example["output"]
            if shown_in not in samples:
                complaints.append(
                    f"{slug}: the statement shows an example that is not a sample case:\n"
                    f"      input {shown_in!r}")
            elif samples[shown_in] != shown_out:
                complaints.append(
                    f"{slug}: the statement's example output disagrees with the reference "
                    f"solution\n"
                    f"      input     {shown_in!r}\n"
                    f"      statement {shown_out!r}\n"
                    f"      reference {samples[shown_in]!r}")

    for slug in sorted(by_slug.keys() - statements.keys()):
        complaints.append(f"{slug}: has test cases but no statement, so nobody can know the "
                          f"input format it is judged against")
    return complaints


def main():
    documents = []
    for slug, solve, inputs in PROBLEMS:
        cases = []
        for index, (raw, is_sample) in enumerate(inputs(), start=1):
            cases.append({
                "index": index,
                "input": raw,
                "expectedOutput": solve(raw),
                "sample": is_sample,
            })
        documents.append({"slug": slug, "cases": cases})
        samples = sum(1 for c in cases if c["sample"])
        print(f"  {slug:<36} {len(cases):>2} cases ({samples} sample)")

    duplicates = {d["slug"] for d in documents}
    if len(duplicates) != len(documents):
        raise SystemExit("  the same slug appears twice in PROBLEMS")

    complaints = check_against_statements(documents)
    if complaints:
        print("\n  Statements and test cases disagree - nothing was written:\n")
        for complaint in complaints:
            print(f"    - {complaint}")
        return 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(documents, indent=2, ensure_ascii=False) + "\n",
                   encoding="utf-8", newline="\n")
    total = sum(len(d["cases"]) for d in documents)
    print(f"\n  {len(documents)} problems, {total} cases -> {OUT} "
          f"({OUT.stat().st_size // 1024} KB)")
    print("  every statement example matches its sample case")
    return 0


if __name__ == "__main__":
    sys.exit(main())
