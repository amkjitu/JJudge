# Deploying CodeArena

Running the whole stack — Postgres, MongoDB, Redis, Kafka, and three JVMs — on one always-free
Oracle Cloud ARM machine, behind HTTPS.

- [Before you start](#before-you-start)
- [1. Provision the machine](#1-provision-the-machine)
- [2. Open the ports](#2-open-the-ports)
- [3. Point a domain at it](#3-point-a-domain-at-it)
- [4. Install Docker](#4-install-docker)
- [5. Configure secrets](#5-configure-secrets)
- [6. First deploy](#6-first-deploy)
- [7. Verify](#7-verify)
- [Updating](#updating)
- [Backups](#backups)
- [Troubleshooting](#troubleshooting)
- [What this deployment is not](#what-this-deployment-is-not)

---

## Before you start

**Read this first, because it changes what you are agreeing to.**

Judging is **simulated**. `SimulatedJudge` derives a verdict from a hash of the submitted text —
it does not compile or execute anything. That is exactly why this is safe to put on the public
internet: there is no untrusted code execution anywhere in the stack.

It also means a visitor who submits a *correct* solution may get `WA`. Say so on the site. A
demo that quietly pretends to be a judge is worse than one that explains itself.

Turning this into a real judge means sandboxing untrusted code — containers, seccomp, cgroups,
pid and memory caps, network isolation — and getting it wrong on a public box hands strangers
remote code execution on your machine. Do not bolt it on casually.

### What it costs

Nothing, on Oracle Cloud's Always Free tier. Two caveats worth knowing before you invest an
evening:

- **A credit card is required for identity verification**, even though the Always Free resources
  do not charge.
- **ARM (Ampere A1) capacity is frequently unavailable** in popular regions. "Out of host
  capacity" is the normal experience, sometimes for days. See
  [Troubleshooting](#troubleshooting) for what to do about it.

If either is a dealbreaker, everything below works unchanged on any €4–6/month VPS. Only
[step 1](#1-provision-the-machine) differs.

### Resource budget

Measured on the running stack, idle:

| Service | Memory |
|---|---|
| kafka | 638 MB |
| arena-api | 481 MB |
| arena-ai | 227 MB |
| mongo | 188 MB |
| arena-judge | 166 MB |
| postgres | 51 MB |
| redis | 20 MB |
| **Total** | **≈ 1.8 GB** |

Budget 3 GB with headroom for load and the OS. The free ARM instance offers 24 GB, so this is
comfortable; it is also why a 512 MB free-tier PaaS was never an option.

---

## 1. Provision the machine

In the Oracle Cloud console: **Compute → Instances → Create instance**.

| Setting | Value |
|---|---|
| Shape | `VM.Standard.A1.Flex` (Ampere ARM) |
| OCPUs / memory | 4 / 24 GB — the whole Always Free ARM allowance |
| Image | Ubuntu 22.04 or 24.04 (ARM build) |
| Boot volume | 50 GB (Always Free allows up to 200 GB total) |
| SSH key | Upload your public key — password login is not offered |

ARM matters and is handled: the Dockerfiles build from `maven:3.9-eclipse-temurin-17` and
`eclipse-temurin:17-jre-alpine`, both of which publish `arm64` images, so everything compiles
natively on the box. No emulation, no cross-building.

---

## 2. Open the ports

Two firewalls, and forgetting the second is the classic waste of an afternoon.

**Oracle's virtual cloud network** — VCN → Security Lists → default → add ingress rules:

| Source | Protocol | Port |
|---|---|---|
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |

**The instance's own firewall.** Oracle's Ubuntu images ship iptables rules that drop
everything except SSH, regardless of what the VCN allows:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
```

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
```

```bash
sudo netfilter-persistent save
```

Do **not** open 5432, 27017, 6379 or 29092. The production overlay binds them to `127.0.0.1`
so they are reachable over SSH and from nowhere else.

---

## 3. Point a domain at it

An `A` record for your domain to the instance's public IP. Caddy requests a certificate on its
first start and the HTTP-01 challenge resolves your domain — **if DNS has not propagated yet,
issuance fails**. Confirm before deploying:

```bash
dig +short arena.example.com
```

A free subdomain (DuckDNS, or a Cloudflare-managed domain) is fine. If you use Cloudflare, set
the record to **DNS only** (grey cloud) for the first issuance; proxying breaks HTTP-01.

---

## 4. Install Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
```

```bash
sudo usermod -aG docker $USER && newgrp docker
```

Then clone:

```bash
git clone https://github.com/amkjitu/jjudge.git && cd jjudge
```

---

## 5. Configure secrets

```bash
cp .env.production.example .env && chmod 600 .env
```

Generate the two secrets rather than inventing them:

```bash
echo "ARENA_JWT_SECRET=$(openssl rand -base64 48)" >> .env
```

```bash
echo "ARENA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
```

Then edit `.env` and set `ARENA_DOMAIN`, `ARENA_TLS_EMAIL` and `POSTGRES_PASSWORD`, removing the
now-duplicated empty lines for the two generated values.

**`ARENA_ADMIN_PASSWORD` is not optional.** The seeded `admin` account has problem CRUD and its
password is printed in this repository's README. With the production overlay the application
refuses to start without a replacement, and rejects the published one — see `SeededAccountGuard`.

---

## 6. First deploy

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

The first build compiles three Maven modules on the box; on 4 ARM cores expect **15–25 minutes**.
Subsequent builds reuse the dependency layer and take a fraction of that.

Watch it come up:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
```

Every service should reach `healthy`. Kafka and Mongo are the slow ones — Mongo's healthcheck
allows 20 seconds per probe because `mongosh` is a Node application whose *startup* alone takes
several seconds on a loaded machine.

---

## 7. Verify

```bash
curl -sI https://arena.example.com | head -1
```

```bash
curl -s https://arena.example.com/api/v1/problems | head -c 200
```

Then check the things that are easy to get wrong and silent when you do:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs arena-api | grep -i "Rotated the seeded"
```

That line must be present. Its absence means the admin account still has the README password.

And confirm from *another machine* that the databases are not exposed:

```bash
nmap -Pn -p 80,443,5432,6379,27017,29092 arena.example.com
```

Only 80 and 443 should be open.

---

## Updating

```bash
git pull && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Compose recreates only what changed. Flyway applies new migrations at startup; the statement
seeder upserts on every boot, so edited problem statements are picked up automatically.

**Rolling back** means checking out the previous commit and rebuilding. There is no automated
rollback here, and Flyway migrations are not reversible — restore from a backup if a migration
is the problem.

---

## Backups

Postgres holds everything that cannot be regenerated: users, submissions, ratings. Mongo holds
statements (re-seeded from the repository on every start) and archived source. Redis is a pure
cache and needs no backup at all.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml exec -T postgres pg_dump -U codearena codearena | gzip > backup-$(date +%F).sql.gz
```

Worth a nightly cron entry. Copy the dumps off the box — a backup on the same disk protects
against your mistakes, not against losing the disk.

---

## Troubleshooting

**"Out of host capacity" when creating the instance.** Oracle's free ARM capacity is genuinely
scarce. Options, in order of how well they work: try a different availability domain in the same
region; try at a different time of day; create the instance in a less popular home region when
you first sign up, since the home region cannot be changed later. Some people script the retry.
If it will not come, any small VPS runs this unchanged.

**Caddy cannot get a certificate.** Check `docker compose ... logs caddy`. The usual causes are
DNS not resolving yet, port 80 blocked by the instance firewall (step 2 — the iptables part is
the one people skip), or Cloudflare proxying. While debugging, uncomment `acme_ca` in the
`Caddyfile` to use Let's Encrypt's staging CA: it issues untrusted certificates but does not
rate-limit you out of five attempts per hour.

**The build is killed partway through.** Almost always memory, though 24 GB makes it unlikely.
Check `dmesg | grep -i oom`.

**Submissions stay `QUEUED`.** arena-judge is not consuming. Check it is healthy and that Kafka
is reachable from it:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs arena-judge --tail 50
```

**Hints always say `HEURISTIC`.** That is the designed behaviour with no Ollama configured. The
static analyser answers instead, and the label is honest about it.

---

## What this deployment is not

Worth being explicit, because the gap between this and Codeforces is not a matter of hosting:

- **One machine, no redundancy.** A reboot is downtime; a disk failure is data loss without
  backups.
- **One JVM per service.** SSE emitters live in a single process's heap, so scaling arena-api
  horizontally would need Redis pub/sub to fan verdicts out — the code says so where it matters.
- **No real code execution**, as covered at the top.
- **No abuse handling beyond rate limiting.** There is no CAPTCHA on registration and no email
  verification, so a determined stranger can create accounts.

For a portfolio demo — something a reviewer can click through — it is the right amount of
machinery. For a service people depend on, each bullet above is a project.
