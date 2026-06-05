# Driftless — Spec 09 one-command bring-up + demo.
#
#   make up        build images and start the whole stack (postgres, app, partner-simulator,
#                  prometheus, grafana) and wait for health
#   make demo      drive the running stack with load + fault injection and prove zero drift
#   make down      stop the stack (keep the database volume)
#   make clean     stop the stack and delete the database volume
#   make ps        show service status
#   make logs      tail app logs

COMPOSE ?= docker compose
APP_URL  ?= http://localhost:8080

.DEFAULT_GOAL := help
.PHONY: help build up wait demo down clean ps logs

help:
	@grep -E '^#   make ' Makefile | sed 's/^#  /  /'

build:
	$(COMPOSE) build

up:
	$(COMPOSE) up -d --build
	@$(MAKE) --no-print-directory wait

wait:
	@echo "==> waiting for app health at $(APP_URL)/actuator/health ..."
	@for i in $$(seq 1 90); do \
	  if curl -fsS $(APP_URL)/actuator/health >/dev/null 2>&1; then echo "    app is UP"; exit 0; fi; \
	  sleep 2; \
	done; \
	echo "    app did not become healthy in time"; $(COMPOSE) ps; exit 1

demo:
	APP_URL=$(APP_URL) ./scripts/demo.sh $(COUNT) $(CONCURRENCY) $(FAULT_MIX)

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f app

down:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v
