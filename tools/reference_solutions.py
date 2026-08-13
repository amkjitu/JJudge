#!/usr/bin/env python3
"""
Reference solutions and input generators for the problems added after the first ten.

Split out of generate_test_cases.py purely for size - the two files are one thing, and
generate_test_cases.py imports PROBLEMS from here and appends it to its own list.

The rules are the same as in that file, and they matter more than any of the code below:

  - A reference solution only has to be *right*. It is written the obvious way, not the clever
    way, because every expected output in the judge comes from running it. A wrong reference
    solution fails correct submissions and leaves nothing in the judge's own code to find.

  - Case sizes stay small enough that an O(n^2) reference finishes, even where the problem's
    stated constraints would demand better from a submission. The constraints describe what a
    solution must handle; these cases only have to distinguish right from wrong.

  - Every statement's worked example appears here as a sample case. generate_test_cases.py
    verifies that, so a statement and its test data cannot drift apart.
"""

import bisect
import heapq
import math
import random
from collections import deque


# --------------------------------------------------------------------------------------------
# Reference solutions. Each takes the raw stdin text and returns the expected stdout text.
# --------------------------------------------------------------------------------------------

def running_sum(text):
    a = list(map(int, text.split("\n")[1].split()))
    out, total = [], 0
    for x in a:
        total += x
        out.append(total)
    return " ".join(map(str, out))


def even_digits(text):
    a = text.split("\n")[1].split()
    return " ".join(str(sum(1 for ch in x.lstrip("-") if int(ch) % 2 == 0)) for x in a)


def anagram_groups(text):
    lines = text.split("\n")
    n = int(lines[0])
    return str(len({"".join(sorted(w.strip())) for w in lines[1:n + 1]}))


def island_counter(text):
    lines = text.split("\n")
    r, c = map(int, lines[0].split())
    grid = [list(lines[1 + i]) for i in range(r)]
    seen = [[False] * c for _ in range(r)]
    islands = 0
    for i in range(r):
        for j in range(c):
            if grid[i][j] != "1" or seen[i][j]:
                continue
            islands += 1
            # Iterative: a fully filled grid would blow a recursive stack, and the reference
            # has to survive the same inputs a submission does.
            stack = [(i, j)]
            seen[i][j] = True
            while stack:
                y, x = stack.pop()
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < r and 0 <= nx < c and not seen[ny][nx] and grid[ny][nx] == "1":
                        seen[ny][nx] = True
                        stack.append((ny, nx))
    return str(islands)


def _dsu(n):
    parent = list(range(n + 1))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra == rb:
            return False
        parent[ra] = rb
        return True

    return find, union


def connected_components(text):
    lines = text.split("\n")
    n, m = map(int, lines[0].split())
    _, union = _dsu(n)
    components = n
    for i in range(1, m + 1):
        u, v = map(int, lines[i].split())
        if union(u, v):
            components -= 1
    return str(components)


def rotate_matrix(text):
    lines = text.split("\n")
    n = int(lines[0])
    a = [list(map(int, lines[1 + i].split())) for i in range(n)]
    return "\n".join(" ".join(str(a[n - 1 - j][i]) for j in range(n)) for i in range(n))


def mod_pow(text):
    lines = text.split("\n")
    q = int(lines[0])
    out = []
    for i in range(1, q + 1):
        a, b, m = map(int, lines[i].split())
        out.append(str(pow(a, b, m)))
    return "\n".join(out)


def distinct_prime_factors(text):
    a = list(map(int, text.split("\n")[1].split()))
    out = []
    for x in a:
        count, d = 0, 2
        while d * d <= x:
            if x % d == 0:
                count += 1
                while x % d == 0:
                    x //= d
            d += 1
        if x > 1:
            count += 1
        out.append(count)
    return " ".join(map(str, out))


def sliding_window_max(text):
    lines = text.split("\n")
    n, k = map(int, lines[0].split())
    a = list(map(int, lines[1].split()))
    # Deliberately the naive window scan: obviously correct, and the cases are small.
    return " ".join(str(max(a[i:i + k])) for i in range(n - k + 1))


def kth_smallest_stream(text):
    lines = text.split("\n")
    n, k = map(int, lines[0].split())
    a = list(map(int, lines[1].split()))
    seen, out = [], []
    for x in a:
        bisect.insort(seen, x)
        out.append(str(seen[k - 1]) if len(seen) >= k else "-1")
    return " ".join(out)


def coin_change(text):
    lines = text.split("\n")
    n, amount = map(int, lines[0].split())
    coins = list(map(int, lines[1].split()))
    unreachable = amount + 1
    dp = [0] + [unreachable] * amount
    for x in range(1, amount + 1):
        for c in coins:
            if c <= x and dp[x - c] + 1 < dp[x]:
                dp[x] = dp[x - c] + 1
    return str(dp[amount] if dp[amount] != unreachable else -1)


def subset_sums(text):
    a = list(map(int, text.split("\n")[1].split()))
    reachable = {0}
    for x in a:
        reachable |= {s + x for s in reachable}
    return str(len(reachable))


def palindromic_substrings(text):
    s = text.strip("\n")
    total = 0
    for centre in range(len(s)):
        for lo, hi in ((centre, centre), (centre, centre + 1)):
            while lo >= 0 and hi < len(s) and s[lo] == s[hi]:
                total += 1
                lo -= 1
                hi += 1
    return str(total)


def grid_shortest_path(text):
    lines = text.split("\n")
    r, c = map(int, lines[0].split())
    grid = [lines[1 + i] for i in range(r)]
    if grid[0][0] == "#" or grid[r - 1][c - 1] == "#":
        return "-1"
    dist = [[-1] * c for _ in range(r)]
    dist[0][0] = 0
    queue = deque([(0, 0)])
    while queue:
        y, x = queue.popleft()
        if (y, x) == (r - 1, c - 1):
            return str(dist[y][x])
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < r and 0 <= nx < c and dist[ny][nx] < 0 and grid[ny][nx] == ".":
                dist[ny][nx] = dist[y][x] + 1
                queue.append((ny, nx))
    return "-1"


def weighted_grid(text):
    lines = text.split("\n")
    r, c = map(int, lines[0].split())
    grid = [lines[1 + i] for i in range(r)]
    best = [[None] * c for _ in range(r)]
    best[0][0] = 0
    heap = [(0, 0, 0)]
    while heap:
        cost, y, x = heapq.heappop(heap)
        if cost > best[y][x]:
            continue
        if (y, x) == (r - 1, c - 1):
            return str(cost)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < r and 0 <= nx < c:
                nxt = cost + int(grid[ny][nx])
                if best[ny][nx] is None or nxt < best[ny][nx]:
                    best[ny][nx] = nxt
                    heapq.heappush(heap, (nxt, ny, nx))
    return str(best[r - 1][c - 1])


def word_ladder(text):
    lines = text.split("\n")
    begin, end = lines[0].split()
    n = int(lines[1])
    words = {lines[2 + i].strip() for i in range(n)}
    if end not in words:
        return "0"
    queue = deque([(begin, 1)])
    words.discard(begin)
    while queue:
        word, length = queue.popleft()
        if word == end:
            return str(length)
        for i in range(len(word)):
            for ch in "abcdefghijklmnopqrstuvwxyz":
                candidate = word[:i] + ch + word[i + 1:]
                if candidate in words:
                    words.discard(candidate)
                    queue.append((candidate, length + 1))
    return "0"


def course_order(text):
    lines = text.split("\n")
    n, m = map(int, lines[0].split())
    adjacency = [[] for _ in range(n + 1)]
    indegree = [0] * (n + 1)
    for i in range(1, m + 1):
        u, v = map(int, lines[i].split())
        adjacency[u].append(v)
        indegree[v] += 1

    ready = [v for v in range(1, n + 1) if indegree[v] == 0]
    heapq.heapify(ready)
    order = []
    while ready:
        v = heapq.heappop(ready)
        order.append(v)
        for w in adjacency[v]:
            indegree[w] -= 1
            if indegree[w] == 0:
                heapq.heappush(ready, w)
    return " ".join(map(str, order)) if len(order) == n else "IMPOSSIBLE"


def mst_weight(text):
    lines = text.split("\n")
    n, m = map(int, lines[0].split())
    edges = []
    for i in range(1, m + 1):
        u, v, w = map(int, lines[i].split())
        edges.append((w, u, v))
    edges.sort()
    _, union = _dsu(n)
    total, accepted = 0, 0
    for w, u, v in edges:
        if union(u, v):
            total += w
            accepted += 1
    return str(total) if accepted == n - 1 else "-1"


def widest_path(text):
    lines = text.split("\n")
    n, m = map(int, lines[0].split())
    adjacency = [[] for _ in range(n + 1)]
    for i in range(1, m + 1):
        u, v, c = map(int, lines[i].split())
        adjacency[u].append((v, c))
        adjacency[v].append((u, c))

    best = [-1] * (n + 1)
    best[1] = float("inf")
    # Negated because heapq is a min-heap and this wants the widest first.
    heap = [(-best[1], 1)]
    while heap:
        width, v = heapq.heappop(heap)
        width = -width
        if width < best[v]:
            continue
        if v == n:
            return str(width)
        for w, c in adjacency[v]:
            candidate = min(width, c)
            if candidate > best[w]:
                best[w] = candidate
                heapq.heappush(heap, (-candidate, w))
    return "-1"


def matrix_chain(text):
    lines = text.split("\n")
    n = int(lines[0])
    d = list(map(int, lines[1].split()))
    dp = [[0] * (n + 1) for _ in range(n + 1)]
    for length in range(2, n + 1):
        for i in range(1, n - length + 2):
            j = i + length - 1
            dp[i][j] = min(dp[i][k] + dp[k + 1][j] + d[i - 1] * d[k] * d[j]
                           for k in range(i, j))
    return str(dp[1][n])


def bitmask_knapsack(text):
    lines = text.split("\n")
    n, capacity = map(int, lines[0].split())
    items = [tuple(map(int, lines[1 + i].split())) for i in range(n)]
    best = 0
    for mask in range(1 << n):
        weight = value = 0
        for i in range(n):
            if mask >> i & 1:
                weight += items[i][0]
                value += items[i][1]
        if weight <= capacity and value > best:
            best = value
    return str(best)


def range_sum_mutable(text):
    lines = text.split("\n")
    n, q = map(int, lines[0].split())
    a = list(map(int, lines[1].split()))
    out = []
    for i in range(2, 2 + q):
        parts = list(map(int, lines[i].split()))
        if parts[0] == 1:
            a[parts[1] - 1] = parts[2]
        else:
            out.append(str(sum(a[parts[1] - 1:parts[2]])))
    return "\n".join(out)


def lazy_segment_tree(text):
    lines = text.split("\n")
    n, q = map(int, lines[0].split())
    a = list(map(int, lines[1].split()))
    out = []
    for i in range(2, 2 + q):
        parts = list(map(int, lines[i].split()))
        if parts[0] == 1:
            _, l, r, v = parts
            for j in range(l - 1, r):
                a[j] += v
        else:
            out.append(str(sum(a[parts[1] - 1:parts[2]])))
    return "\n".join(out)


def _parents(line, n):
    parent = [0, 0]
    parent.extend(map(int, line.split())) if n > 1 else None
    return parent


def lca_queries(text):
    lines = text.split("\n")
    n, q = map(int, lines[0].split())
    parent = _parents(lines[1] if n > 1 else "", n)
    depth = [0] * (n + 1)
    for v in range(2, n + 1):
        depth[v] = depth[parent[v]] + 1

    out = []
    for i in range(2, 2 + q):
        u, v = map(int, lines[i].split())
        while depth[u] > depth[v]:
            u = parent[u]
        while depth[v] > depth[u]:
            v = parent[v]
        while u != v:
            u, v = parent[u], parent[v]
        out.append(str(u))
    return "\n".join(out)


def kth_ancestor(text):
    lines = text.split("\n")
    n, q = map(int, lines[0].split())
    parent = _parents(lines[1] if n > 1 else "", n)
    out = []
    for i in range(2, 2 + q):
        v, k = map(int, lines[i].split())
        # Walking one step at a time is fine because k is clamped in the generated cases;
        # a submission facing k up to 1e9 needs the lifting table.
        while k > 0 and v != 0:
            v = parent[v]
            k -= 1
        out.append(str(v if v != 0 and k == 0 else -1))
    return "\n".join(out)


def trie_prefix_counts(text):
    lines = text.split("\n")
    n, q = map(int, lines[0].split())
    words = [lines[1 + i].strip() for i in range(n)]
    out = []
    for i in range(1 + n, 1 + n + q):
        prefix = lines[i].strip()
        out.append(str(sum(1 for w in words if w.startswith(prefix))))
    return "\n".join(out)


def distinct_substrings(text):
    s = text.strip("\n")
    return str(len({s[i:j] for i in range(len(s)) for j in range(i + 1, len(s) + 1)}))


def digit_sum_range(text):
    lo, hi = map(int, text.split())
    return str(sum(sum(int(ch) for ch in str(x)) for x in range(lo, hi + 1)))


def lattice_triangles(text):
    lines = text.split("\n")
    n = int(lines[0])
    points = [tuple(map(int, lines[1 + i].split())) for i in range(n)]
    total = 0
    for i in range(n):
        for j in range(i + 1, n):
            for k in range(j + 1, n):
                (ax, ay), (bx, by), (cx, cy) = points[i], points[j], points[k]
                if (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) != 0:
                    total += 1
    return str(total)


def hull_perimeter(text):
    lines = text.split("\n")
    n = int(lines[0])
    points = sorted({tuple(map(int, lines[1 + i].split())) for i in range(n)})
    if len(points) == 1:
        return "0.000000"

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    def half(sequence):
        chain = []
        for p in sequence:
            while len(chain) >= 2 and cross(chain[-2], chain[-1], p) <= 0:
                chain.pop()
            chain.append(p)
        return chain

    hull = half(points)[:-1] + half(reversed(points))[:-1]
    perimeter = sum(math.dist(hull[i], hull[(i + 1) % len(hull)]) for i in range(len(hull)))
    return f"{perimeter:.6f}"


# --------------------------------------------------------------------------------------------
# Inputs. Statement samples first, then edge cases, then randomised ones at a fixed seed.
# --------------------------------------------------------------------------------------------

def rand_ints(n, lo, hi):
    return [random.randint(lo, hi) for _ in range(n)]


def _ints_line(n, lo, hi):
    return f"{n}\n{' '.join(map(str, rand_ints(n, lo, hi)))}"


def running_sum_inputs():
    yield "4\n1 2 3 4", True
    yield "3\n1 -1 5", True
    yield "1\n0", False                                  # single element
    yield "5\n-1 -1 -1 -1 -1", False                     # monotonically decreasing
    yield f"200\n{' '.join(['10000'] * 200)}", False      # largest values, longest run
    for _ in range(4):
        yield _ints_line(random.randint(1, 200), -10000, 10000), False


def even_digits_inputs():
    yield "3\n248 13 0", True
    yield "2\n-24 1000000000", True
    yield "1\n1", False                                  # no even digits at all
    yield "1\n-0", False                                 # a signed zero in the input text
    yield "4\n2 4 6 8", False
    for _ in range(4):
        yield _ints_line(random.randint(1, 60), -10**9, 10**9), False


def anagram_groups_inputs():
    yield "6\neat\ntea\ntan\nate\nnat\nbat", True
    yield "3\na\na\na", True
    yield "1\nz", False                                  # a single word
    yield "3\nab\nba\nabc", False                        # length alone does not group
    for _ in range(4):
        n = random.randint(1, 60)
        words = []
        for _ in range(n):
            letters = [random.choice("abcde") for _ in range(random.randint(1, 6))]
            random.shuffle(letters)
            words.append("".join(letters))
        yield f"{n}\n" + "\n".join(words), False


def _random_grid(r, c, alphabet, weights):
    return "\n".join("".join(random.choices(alphabet, weights=weights)[0] for _ in range(c))
                     for _ in range(r))


def island_counter_inputs():
    yield "4 5\n11000\n11000\n00100\n00011", True
    yield "2 2\n10\n01", True
    yield "1 1\n0", False                                # no land at all
    yield "1 1\n1", False
    yield "3 3\n111\n111\n111", False                    # one solid island
    yield "3 3\n101\n010\n101", False                    # diagonals are not connected
    for _ in range(4):
        r, c = random.randint(1, 12), random.randint(1, 12)
        yield f"{r} {c}\n" + _random_grid(r, c, "01", [1, 1]), False


def connected_components_inputs():
    yield "5 3\n1 2\n2 3\n4 5", True
    yield "3 0", True
    yield "1 0", False                                   # a single isolated vertex
    yield "2 3\n1 2\n1 2\n1 1", False                    # repeated edges and a self-loop
    for _ in range(4):
        n = random.randint(1, 30)
        m = random.randint(0, 40)
        rows = [f"{random.randint(1, n)} {random.randint(1, n)}" for _ in range(m)]
        yield f"{n} {m}\n" + "\n".join(rows), False


def rotate_matrix_inputs():
    yield "3\n1 2 3\n4 5 6\n7 8 9", True
    yield "1\n42", True
    yield "2\n1 2\n3 4", False
    yield "2\n-1 -2\n-3 -4", False                       # negatives
    for _ in range(4):
        n = random.randint(1, 8)
        rows = [" ".join(map(str, rand_ints(n, -1000, 1000))) for _ in range(n)]
        yield f"{n}\n" + "\n".join(rows), False


def mod_pow_inputs():
    yield "3\n2 10 1000\n3 0 7\n5 3 1", True
    yield "1\n0 0 5", True
    yield "1\n1000000000000000000 1000000000000000000 999999937", False   # the stated limits
    yield "2\n0 5 7\n7 1 7", False                       # zero base, and a base divisible by m
    for _ in range(4):
        q = random.randint(1, 8)
        rows = [f"{random.randint(0, 10**18)} {random.randint(0, 10**18)} "
                f"{random.randint(1, 10**9)}" for _ in range(q)]
        yield f"{q}\n" + "\n".join(rows), False


def prime_factors_inputs():
    yield "4\n12 1 97 360", True
    yield "2\n1000000 999983", True
    yield "1\n1", False
    yield "3\n2 4 8", False                              # powers of one prime count once
    yield "1\n510510", False                             # the product of the first seven primes
    for _ in range(4):
        yield _ints_line(random.randint(1, 60), 1, 10**6), False


def sliding_window_inputs():
    yield "8 3\n1 3 -1 -3 5 3 6 7", True
    yield "4 4\n4 3 2 1", True
    yield "1 1\n5", False
    yield "5 1\n3 1 4 1 5", False                        # every element is its own window
    yield "5 2\n-5 -4 -3 -2 -1", False                   # all negative
    for _ in range(4):
        n = random.randint(1, 60)
        yield f"{n} {random.randint(1, n)}\n{' '.join(map(str, rand_ints(n, -1000, 1000)))}", False


def kth_smallest_inputs():
    yield "5 2\n5 1 3 2 4", True
    yield "3 1\n7 3 9", True
    yield "1 1\n0", False
    yield "3 5\n1 2 3", False                            # k never reached: all -1
    yield "4 2\n5 5 5 5", False                          # duplicates count separately
    for _ in range(4):
        n = random.randint(1, 60)
        yield f"{n} {random.randint(1, n)}\n{' '.join(map(str, rand_ints(n, -1000, 1000)))}", False


def coin_change_inputs():
    yield "3 11\n1 2 5", True
    yield "1 3\n2", True
    yield "3 6\n1 3 4", False                            # the case greedy gets wrong
    yield "1 1\n1", False
    yield "2 9973\n7 11", False                          # a large amount from two coins
    for _ in range(4):
        n = random.randint(1, 6)
        yield f"{n} {random.randint(1, 400)}\n{' '.join(map(str, rand_ints(n, 1, 50)))}", False


def subset_sums_inputs():
    yield "3\n1 2 3", True
    yield "2\n1 1", True
    yield "1\n1000", False
    yield "5\n1 1 1 1 1", False                          # heavy duplication
    yield f"12\n{' '.join(str(2 ** i) for i in range(12))}", False   # powers of two: all distinct
    for _ in range(4):
        yield _ints_line(random.randint(1, 12), 1, 60), False


def palindromic_inputs():
    yield "abc", True
    yield "aaa", True
    yield "a", False
    yield "ab" * 60, False                               # long, with even-length palindromes
    yield "racecar", False
    for _ in range(4):
        yield "".join(random.choice("abc") for _ in range(random.randint(1, 120))), False


def shortest_path_inputs():
    yield "3 3\n...\n.#.\n...", True
    yield "2 2\n.#\n#.", True
    yield "1 1\n.", False                                # start is the destination
    yield "1 1\n#", False                                # the start itself is a wall
    yield "1 5\n.....", False                            # a corridor
    for _ in range(4):
        r, c = random.randint(1, 10), random.randint(1, 10)
        yield f"{r} {c}\n" + _random_grid(r, c, ".#", [3, 1]), False


def weighted_grid_inputs():
    yield "3 3\n123\n456\n789", True
    yield "1 1\n7", True
    yield "2 2\n00\n00", False                           # a free grid costs nothing
    yield "1 4\n9999", False                             # a corridor charges every cell entered
    for _ in range(4):
        r, c = random.randint(1, 8), random.randint(1, 8)
        yield f"{r} {c}\n" + _random_grid(r, c, "0123456789", [1] * 10), False


def word_ladder_inputs():
    yield "hit cog\n6\nhot\ndot\ndog\nlot\nlog\ncog", True
    yield "hit cog\n5\nhot\ndot\ndog\nlot\nlog", True
    yield "a c\n2\nb\nc", False                          # single letters
    yield "ab ab\n1\nab", False                          # begin equals end
    for _ in range(4):
        length = random.randint(2, 3)
        pool = {"".join(random.choice("abc") for _ in range(length)) for _ in range(12)}
        pool = sorted(pool)
        begin = "".join(random.choice("abc") for _ in range(length))
        end = random.choice(pool)
        yield f"{begin} {end}\n{len(pool)}\n" + "\n".join(pool), False


def course_order_inputs():
    yield "4 3\n1 2\n1 3\n3 4", True
    yield "2 2\n1 2\n2 1", True
    yield "3 0", False                                   # no constraints: plain ascending order
    yield "1 0", False
    yield "3 3\n1 2\n2 3\n3 1", False                    # a longer cycle
    for _ in range(4):
        n = random.randint(1, 12)
        # Edges only from lower to higher keeps these acyclic, so the ordering itself is tested
        # rather than only the cycle detection.
        pairs = {(u, v) for u in range(1, n + 1) for v in range(u + 1, n + 1)
                 if random.random() < 0.25}
        rows = [f"{u} {v}" for u, v in sorted(pairs)]
        yield f"{n} {len(rows)}\n" + "\n".join(rows), False


def mst_inputs():
    yield "4 5\n1 2 1\n2 3 2\n3 4 3\n1 4 10\n1 3 4", True
    yield "3 1\n1 2 5", True
    yield "1 0", False                                   # one vertex spans itself
    yield "2 2\n1 2 5\n1 2 3", False                     # parallel edges of different weight
    yield "3 3\n1 2 1000000000\n2 3 1000000000\n1 3 1000000000", False   # 64-bit total
    for _ in range(4):
        n = random.randint(1, 12)
        pairs = [(u, v) for u in range(1, n + 1) for v in range(u + 1, n + 1)]
        chosen = [p for p in pairs if random.random() < 0.5]
        rows = [f"{u} {v} {random.randint(1, 100)}" for u, v in chosen]
        yield f"{n} {len(rows)}\n" + "\n".join(rows), False


def widest_path_inputs():
    yield "4 4\n1 2 5\n2 4 3\n1 3 2\n3 4 8", True
    yield "2 0", True
    yield "2 1\n1 2 1000000000", False                   # a single pipe at the capacity limit
    yield "3 2\n1 2 5\n2 3 5", False                     # a chain of equal capacity
    for _ in range(4):
        n = random.randint(2, 12)
        pairs = [(u, v) for u in range(1, n + 1) for v in range(u + 1, n + 1)]
        chosen = [p for p in pairs if random.random() < 0.45]
        rows = [f"{u} {v} {random.randint(1, 1000)}" for u, v in chosen]
        yield f"{n} {len(rows)}\n" + "\n".join(rows), False


def matrix_chain_inputs():
    yield "3\n10 30 5 60", True
    yield "1\n5 7", True
    yield "2\n2 3 4", False
    yield "4\n1 1 1 1 1", False                          # every split costs the same
    for _ in range(4):
        n = random.randint(1, 12)
        yield f"{n}\n{' '.join(map(str, rand_ints(n + 1, 1, 200)))}", False


def knapsack_inputs():
    yield "3 5\n2 3\n3 4\n4 5", True
    yield "2 1\n5 100\n6 200", True
    yield "1 1000000000\n1000000000 1000000000", False   # everything at the stated limit
    yield "3 1000000000\n1 1\n1 1\n1 1", False           # capacity far exceeds the items
    for _ in range(4):
        n = random.randint(1, 12)
        rows = [f"{random.randint(1, 50)} {random.randint(1, 100)}" for _ in range(n)]
        yield f"{n} {random.randint(1, 120)}\n" + "\n".join(rows), False


def range_sum_inputs():
    yield "5 3\n1 2 3 4 5\n2 1 5\n1 3 10\n2 2 4", True
    yield "1 1\n7\n2 1 1", True
    # Two updates to one position: the case that catches a Fenwick tree adding v rather than
    # the delta v - a[i].
    yield "3 4\n1 1 1\n1 2 5\n2 1 3\n1 2 9\n2 1 3", False
    yield "3 1\n1000000000 1000000000 1000000000\n2 1 3", False   # a 64-bit sum
    for _ in range(4):
        n, q = random.randint(1, 12), random.randint(1, 10)
        rows = []
        for _ in range(q):
            if random.random() < 0.5:
                rows.append(f"1 {random.randint(1, n)} {random.randint(-100, 100)}")
            else:
                l = random.randint(1, n)
                rows.append(f"2 {l} {random.randint(l, n)}")
        if not any(r.startswith("2") for r in rows):
            rows.append("2 1 " + str(n))
        yield (f"{n} {len(rows)}\n{' '.join(map(str, rand_ints(n, -100, 100)))}\n"
               + "\n".join(rows)), False


def lazy_inputs():
    yield "5 3\n1 2 3 4 5\n1 2 4 10\n2 1 5\n2 3 3", True
    yield "3 2\n0 0 0\n1 1 3 5\n2 1 3", True
    yield "4 3\n1 1 1 1\n1 1 4 -1\n1 1 4 -1\n2 1 4", False   # overlapping negative updates
    yield "1 2\n5\n1 1 1 7\n2 1 1", False
    for _ in range(4):
        n, q = random.randint(1, 12), random.randint(1, 10)
        rows = []
        for _ in range(q):
            l = random.randint(1, n)
            r = random.randint(l, n)
            if random.random() < 0.5:
                rows.append(f"1 {l} {r} {random.randint(-50, 50)}")
            else:
                rows.append(f"2 {l} {r}")
        if not any(row.startswith("2") for row in rows):
            rows.append(f"2 1 {n}")
        yield (f"{n} {len(rows)}\n{' '.join(map(str, rand_ints(n, -100, 100)))}\n"
               + "\n".join(rows)), False


def _random_tree(n):
    """Parents of nodes 2..n, each strictly less than its child as the statement promises."""
    return [random.randint(1, v - 1) for v in range(2, n + 1)]


def lca_inputs():
    yield "5 3\n1 1 2 2\n4 5\n4 3\n2 4", True
    yield "2 1\n1\n1 2", True
    yield "1 1\n\n1 1", False                            # a single node, queried against itself
    yield "4 1\n1 2 3\n4 4", False                       # a path, node against itself
    for _ in range(4):
        n = random.randint(1, 14)
        parents = _random_tree(n)
        q = random.randint(1, 6)
        rows = [f"{random.randint(1, n)} {random.randint(1, n)}" for _ in range(q)]
        yield f"{n} {q}\n{' '.join(map(str, parents))}\n" + "\n".join(rows), False


def ancestor_inputs():
    yield "5 3\n1 1 2 2\n4 1\n4 2\n4 3", True
    yield "2 1\n1\n2 1000000000", True
    yield "1 1\n\n1 1", False                            # the root has no parent
    yield "4 2\n1 2 3\n4 3\n4 4", False                  # exactly the depth, then one past it
    for _ in range(4):
        n = random.randint(1, 14)
        parents = _random_tree(n)
        q = random.randint(1, 6)
        rows = [f"{random.randint(1, n)} {random.randint(1, n + 2)}" for _ in range(q)]
        yield f"{n} {q}\n{' '.join(map(str, parents))}\n" + "\n".join(rows), False


def trie_inputs():
    yield "4 3\napple\napp\napply\nbanana\napp\nappl\nb", True
    yield "1 1\nabc\nabcd", True
    yield "2 1\nab\nab\nab", False                       # duplicate words count separately
    yield "1 1\na\na", False
    for _ in range(4):
        n, q = random.randint(1, 20), random.randint(1, 6)
        words = ["".join(random.choice("ab") for _ in range(random.randint(1, 5)))
                 for _ in range(n)]
        prefixes = ["".join(random.choice("ab") for _ in range(random.randint(1, 4)))
                    for _ in range(q)]
        yield f"{n} {q}\n" + "\n".join(words) + "\n" + "\n".join(prefixes), False


def substrings_inputs():
    yield "abc", True
    yield "aaa", True
    yield "a", False
    yield "ab" * 40, False                               # heavy repetition
    yield "abcdefghij", False                            # all distinct: the maximum for its length
    for _ in range(4):
        yield "".join(random.choice("abc") for _ in range(random.randint(1, 90))), False


def digit_sum_inputs():
    yield "1 10", True
    yield "5 5", True
    yield "1 1", False
    yield "999 1001", False                              # a carry across the boundary
    yield "1 100000", False                              # too many to enumerate by hand
    for _ in range(4):
        lo = random.randint(1, 50000)
        yield f"{lo} {lo + random.randint(0, 20000)}", False


def triangle_inputs():
    yield "4\n0 0\n1 0\n2 0\n0 1", True
    yield "3\n0 0\n1 1\n2 2", True
    yield "3\n0 0\n1 0\n0 1", False                      # the smallest non-degenerate case
    yield "4\n0 0\n1000000000 0\n0 1000000000\n1000000000 1000000000", False   # 64-bit cross
    for _ in range(4):
        n = random.randint(3, 14)
        points = set()
        while len(points) < n:
            points.add((random.randint(-8, 8), random.randint(-8, 8)))
        rows = [f"{x} {y}" for x, y in points]
        yield f"{n}\n" + "\n".join(rows), False


def hull_inputs():
    yield "4\n0 0\n1 0\n1 1\n0 1", True
    yield "3\n0 0\n2 0\n1 0", True
    yield "1\n5 5", False                                # a single point has no perimeter
    yield "2\n0 0\n3 4", False                           # two points: twice the distance
    yield "4\n0 0\n0 0\n1 1\n1 1", False                 # duplicates
    for _ in range(4):
        n = random.randint(1, 20)
        rows = [f"{random.randint(-50, 50)} {random.randint(-50, 50)}" for _ in range(n)]
        yield f"{n}\n" + "\n".join(rows), False


PROBLEMS = [
    ("running-sum-of-array", running_sum, running_sum_inputs),
    ("count-even-digits", even_digits, even_digits_inputs),
    ("anagram-groups", anagram_groups, anagram_groups_inputs),
    ("island-counter", island_counter, island_counter_inputs),
    ("number-of-connected-components", connected_components, connected_components_inputs),
    ("rotate-matrix-in-place", rotate_matrix, rotate_matrix_inputs),
    ("modular-exponentiation", mod_pow, mod_pow_inputs),
    ("count-distinct-prime-factors", distinct_prime_factors, prime_factors_inputs),
    ("sliding-window-maximum", sliding_window_max, sliding_window_inputs),
    ("kth-smallest-in-a-stream", kth_smallest_stream, kth_smallest_inputs),
    ("coin-change-minimum", coin_change, coin_change_inputs),
    ("subsets-and-sums", subset_sums, subset_sums_inputs),
    ("palindromic-substrings-count", palindromic_substrings, palindromic_inputs),
    ("shortest-path-in-a-grid", grid_shortest_path, shortest_path_inputs),
    ("dijkstra-on-a-weighted-grid", weighted_grid, weighted_grid_inputs),
    ("word-ladder-length", word_ladder, word_ladder_inputs),
    ("course-schedule-order", course_order, course_order_inputs),
    ("minimum-spanning-network", mst_weight, mst_inputs),
    ("max-flow-bottleneck", widest_path, widest_path_inputs),
    ("matrix-chain-multiplication", matrix_chain, matrix_chain_inputs),
    ("knapsack-with-bitmask", bitmask_knapsack, knapsack_inputs),
    ("range-sum-queries-mutable", range_sum_mutable, range_sum_inputs),
    ("segment-tree-with-lazy-propagation", lazy_segment_tree, lazy_inputs),
    ("lowest-common-ancestor-queries", lca_queries, lca_inputs),
    ("binary-lifting-ancestors", kth_ancestor, ancestor_inputs),
    ("trie-autocomplete", trie_prefix_counts, trie_inputs),
    ("suffix-automaton-substrings", distinct_substrings, substrings_inputs),
    ("digit-dp-sum-of-digit-sums", digit_sum_range, digit_sum_inputs),
    ("counting-lattice-triangles", lattice_triangles, triangle_inputs),
    ("convex-hull-perimeter", hull_perimeter, hull_inputs),
]
