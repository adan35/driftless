# Kubernetes manifests — OPTIONAL STRETCH (not the MVP)

> **The supported, validated path is `docker compose up` from the repo root** (see the root README
> Quickstart). These manifests are a clearly-labelled stretch goal: a faithful translation of the
> compose topology to a local cluster (kind / minikube / Docker Desktop). They are provided for
> completeness and are **not** part of the zero-drift gate.

## What's here

| File | Contents |
|------|----------|
| `00-namespace.yaml` | the `driftless` namespace |
| `10-postgres.yaml` | single-replica Postgres + PVC + Secret (demo only — prefer a managed DB) |
| `20-partner-simulator.yaml` | the separate partner-simulator Deployment + Service (`partner-simulator:8081`) |
| `30-app.yaml` | the modular monolith Deployment + Service (`app:8080`), wired to Postgres + partner by service name |
| `40-monitoring.yaml` | Prometheus (scrapes app + partner-simulator) and Grafana (anonymous viewer, provisioned datasource + dashboard provider) |

The app reaches the partner-simulator by the in-cluster DNS name `partner-simulator:8081` — the same
real network hop as compose, never an in-process call. Flyway migrates every module's schema on app
startup, exactly as in compose.

## Apply (local cluster)

Build the images so the cluster can find them (e.g. `kind load` or a local registry), then:

```bash
# 1. Build images (multi-stage Dockerfiles)
docker compose build              # produces driftless/app:latest + driftless/partner-simulator:latest
# kind load docker-image driftless/app:latest driftless/partner-simulator:latest   # if using kind

# 2. Load the provisioned Grafana dashboards (the same JSON the compose stack uses) as a ConfigMap
kubectl create namespace driftless --dry-run=client -o yaml | kubectl apply -f -
kubectl -n driftless create configmap grafana-dashboards \
  --from-file=deploy/grafana/dashboards/ --dry-run=client -o yaml | kubectl apply -f -

# 3. Apply the manifests
kubectl apply -f k8s/

# 4. Reach the services
kubectl -n driftless port-forward svc/app 8080:8080            # REST + /actuator/prometheus
kubectl -n driftless port-forward svc/grafana 3000:3000        # Zero Drift dashboard
```

Then run the demo against the forwarded app, exactly as with compose:

```bash
APP_URL=http://localhost:8080 ./scripts/demo.sh 60 8 MIXED
```

## Out of scope (notes only)

Secrets management (the bundled Postgres Secret is demo-grade), TLS/ingress, autoscaling and
production storage classes are intentionally omitted — local reproducibility via compose is the MVP
target.
