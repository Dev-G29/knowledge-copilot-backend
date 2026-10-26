# Deploying Knowledge Copilot (Oracle Cloud Always Free + Vercel)

```
Browser ──► Vercel (React SPA, CDN, never sleeps)
   └─HTTPS─► https://<you>.duckdns.org   Oracle Ampere A1 VM (Ubuntu 24.04, 2 OCPU / 12 GB, $0)
               caddy     :80/:443   automatic Let's Encrypt TLS, reverse proxy
               backend   :8080      Spring Boot image from GHCR (built by GitHub Actions)
               postgres  :5432      pgvector/pgvector:pg16, Docker volume, not exposed
               backup               nightly pg_dump → ./backups (+ optional OCI Object Storage)
```

Nothing sleeps and nothing expires: the VM runs 24/7, and the database lives on the VM's disk with nightly backups.

Steps marked **(you)** need your accounts or credentials.

---

## 0. Migrating from an old database (optional)

**Starting fresh? Skip this step and step 7.** Flyway creates an empty schema on first boot, and the admin account is seeded from `.env`.

If you do have an old Knowledge Copilot database (e.g. on Render), take a dump of it:

1. Render dashboard → your Postgres → **Connections** → copy the **External Database URL**.
2. On your Mac (Homebrew `pg_dump` 18 can dump older servers):

```bash
pg_dump --format=custom --no-owner --no-privileges "postgresql://USER:PASSWORD@HOST/DB" -f ~/render-backup.dump
```

Keep `render-backup.dump` safe. You'll restore it in step 7. Flyway converts the old JSON embeddings to pgvector automatically, so there's no need to re-embed.

## 1. Oracle Cloud account (you)

1. Sign up at <https://signup.cloud.oracle.com>. Choose your **home region** carefully because it can't be changed later. Pick one close to your users that has Ampere (A1) capacity, e.g. `ap-mumbai-1` or `ap-hyderabad-1`.
2. **Upgrade to Pay As You Go**: Billing & Cost Management → Upgrade and Manage Payment. You are still charged **$0** while you stay within Always Free limits, and:
   - Oracle stops reclaiming "idle" Always Free VMs (free-tier-only accounts can lose an instance after 7 quiet days).
   - "Out of host capacity" errors when creating A1 instances become much rarer.
3. **Add a budget alert as a safety net**: Billing → Budgets → Create Budget → $1/month, alert at 1%.

## 2. Create the VM (you)

Compute → Instances → **Create instance**:

| Setting | Value |
|---|---|
| Image | Canonical Ubuntu 24.04 (aarch64) |
| Shape | Ampere `VM.Standard.A1.Flex`, **2 OCPU, 12 GB** (inside the Always Free allowance) |
| Networking | Create new VCN + public subnet, **Assign a public IPv4 address** |
| SSH keys | Upload your `~/.ssh/id_ed25519.pub` (or let Oracle generate one and download it) |
| Boot volume | 50 GB (Always Free covers up to 200 GB total) |

If you get "Out of capacity", try another Availability Domain, or retry later.

Note the **public IP**. It stays the same across reboots and stop/start. It's only released if you *terminate* the instance.

## 3. Open ports 80/443 (you)

These rules have to be added in **two** places:

**a) In the VCN.** Networking → Virtual Cloud Networks → your VCN → Security Lists → Default → **Add Ingress Rules**:
- Source `0.0.0.0/0`, TCP, destination port `80`
- Source `0.0.0.0/0`, TCP, destination port `443`

**b) On the VM.** Oracle's Ubuntu image ships with restrictive iptables rules:

```bash
ssh ubuntu@<PUBLIC_IP>
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 4. Install Docker on the VM

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit   # log out and back in so the group change applies
```

## 5. Free domain with DuckDNS (you)

1. Sign in at <https://www.duckdns.org> and create a subdomain, e.g. `kc-api` → `kc-api.duckdns.org`.
2. Set its IP to the VM's public IP and click **update ip**.

The IP is static, so no updater script is needed. Check it with `dig +short kc-api.duckdns.org`.

## 6. Start the stack

On the VM:

```bash
mkdir -p ~/knowledge-copilot && cd ~/knowledge-copilot
base=https://raw.githubusercontent.com/Dev-G29/knowledge-copilot-backend/main/deploy
for f in docker-compose.yml Caddyfile backup.sh restore.sh .env.example; do curl -fsSLO "$base/$f"; done
chmod +x backup.sh restore.sh
cp .env.example .env
openssl rand -base64 48   # paste as JWT_SECRET
openssl rand -base64 24   # paste as POSTGRES_PASSWORD
nano .env                 # fill in every value
```

The backend image is published to GHCR by GitHub Actions (step 8). After the first successful run, do **one** of:
- **Make the package public** (simplest): GitHub → your profile → Packages → `knowledge-copilot-backend` → Package settings → Change visibility → Public.
- **Keep it private** and log the VM in once: `echo <PAT with read:packages> | docker login ghcr.io -u Dev-G29 --password-stdin`

Then:

```bash
docker compose up -d
docker compose logs -f backend          # wait for "Started KnowledgeCopilotApplication"
curl https://kc-api.duckdns.org/actuator/health   # {"status":"UP"}
```

Caddy fetches the TLS certificate on the first request to the domain. This needs ports 80 and 443 to be reachable.

## 7. Restore old data (optional, migrations only)

```bash
# on your Mac
scp ~/render-backup.dump ubuntu@<PUBLIC_IP>:~/knowledge-copilot/
# on the VM
cd ~/knowledge-copilot && ./restore.sh render-backup.dump
docker compose logs -f backend   # Flyway: "Migrating schema ... to version 2/3"
```

The old database still has the default `admin` / `admin123` account. To rotate it to your `ADMIN_PASSWORD`:
1. Set `ADMIN_SYNC_PASSWORD=true` in `.env` and run `docker compose up -d backend`.
2. Set it back to `false` and run the same command again.

## 8. Continuous deployment (you)

`.github/workflows/ci-deploy.yml` runs on every push to `main`:
1. Runs the tests, including the Testcontainers + pgvector integration tests.
2. Builds an arm64 image on GitHub's native ARM runner and pushes it to `ghcr.io/dev-g29/knowledge-copilot-backend`.
3. Copies the stack files to the VM over SSH, runs `docker compose pull && up -d`, and waits for the health check.

**Create a dedicated deploy key:**

```bash
ssh-keygen -t ed25519 -f ~/.ssh/kc_deploy -N "" -C "github-actions-deploy"
ssh-copy-id -i ~/.ssh/kc_deploy.pub ubuntu@<PUBLIC_IP>
```

**Add the repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `VM_HOST` | the VM's public IP |
| `VM_USER` | `ubuntu` |
| `VM_SSH_KEY` | contents of `~/.ssh/kc_deploy` (the private key) |

Until `VM_HOST` is set, the deploy job skips itself. Tests and image publishing still run.

## 9. Off-site backups (recommended)

Nightly dumps land in `~/knowledge-copilot/backups` (kept 14 days). To also copy them to Oracle Object Storage (20 GB free):

1. Storage → Buckets → Create bucket `knowledge-copilot-backups` (Standard tier).
2. Profile → **Customer secret keys** → Generate. Copy the access key and secret.
3. Find your namespace: Tenancy details → Object Storage namespace.
4. Fill in `.env`:
   ```
   OCI_S3_ENDPOINT=https://<namespace>.compat.objectstorage.<region>.oraclecloud.com
   OCI_REGION=<region, e.g. ap-mumbai-1>
   OCI_S3_ACCESS_KEY=...
   OCI_S3_SECRET_KEY=...
   ```
5. Run `docker compose --profile offsite up -d`.

## 10. Frontend on Vercel (you)

See the frontend repo's README. In short:
1. Import `knowledge-copilot-frontend` into Vercel.
2. Set `VITE_API_BASE_URL=https://kc-api.duckdns.org` and deploy.
3. Add the resulting `https://<project>.vercel.app` to `CORS_ALLOWED_ORIGINS` in the VM's `.env`.
4. Run `docker compose up -d backend`.

---

## Day-to-day operations

| Task | Command (in `~/knowledge-copilot`) |
|---|---|
| Logs | `docker compose logs -f backend` (or `caddy`, `postgres`, `backup`) |
| Status | `docker compose ps` |
| Deploy latest image manually | `docker compose pull backend && docker compose up -d` |
| Roll back | set `BACKEND_IMAGE=ghcr.io/dev-g29/knowledge-copilot-backend:<older-sha>` in `.env`, then `docker compose up -d` |
| Take a backup now | `docker compose exec backup bash -c 'pg_dump -Fc -f /backups/manual-$(date +%s).dump'` |
| Restore a backup | `./restore.sh backups/<file>.dump` |
| psql shell | `docker compose exec postgres psql -U copilot knowledge_copilot` |
| Change config | edit `.env`, then `docker compose up -d` |
| OS updates | `sudo apt update && sudo apt upgrade -y && sudo reboot`. Containers restart on their own. |

## Testing the stack locally

With Docker (or Colima) running:

```bash
cd deploy
cp .env.example .env    # set API_DOMAIN=localhost, CORS_ALLOWED_ORIGINS=http://localhost:5173, real GEMINI_API_KEY
docker compose up -d --build
curl -k https://localhost/actuator/health
```

For `localhost`, Caddy serves a certificate from its own local CA, so use `curl -k`.
