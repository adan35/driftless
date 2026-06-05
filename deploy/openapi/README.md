# OpenAPI contract

`openapi.yaml` is the **hand-authored, single source** OpenAPI 3.1 description of the Driftless public
REST surface (accounts, authorizations, tokens, reconciliation).

## Why hand-authored (not springdoc)

`springdoc-openapi 2.x` targets Spring Framework 6 / Spring Boot 3. Driftless runs on **Spring Boot
4.0.x (Spring Framework 7)**, with which springdoc 2.x is not yet compatible — adding it breaks the
build. Per the refinement guidance, keeping the build green takes priority, so the contract is
authored here and committed in-repo so it is browsable without a running service.

When a Spring Boot 4-compatible springdoc release ships, this file can be replaced by a generated
`/v3/api-docs` and the static copy retired.

## How it is served

The `app` module copies this file onto its classpath at build time
(`maven-resources-plugin` → `static/openapi.yaml`), so the booted monolith serves it at:

- `GET /openapi.yaml` — the raw contract
- `GET /swagger-ui/index.html` — Swagger UI (assets from the `org.webjars:swagger-ui` webjar) rendering it
