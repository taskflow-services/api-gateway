# API Gateway

The API gateway is the single entry point for the TaskFlow system. Clients send every request to `http://localhost:8082`, and the gateway forwards it to the right service based on the path. It finds the services through the Eureka discovery server, so it doesn't need to know their ports.

Besides routing, the gateway:

- adds an `X-Correlation-Id` header to every request and response, and prints it in its log lines,
- wraps the notification route in a circuit breaker, so a failing notification service gets a quick `503` instead of hanging requests,
- loads shared settings from the config server, and reloads them without a restart when a Spring Cloud Bus refresh is sent over RabbitMQ,
- sends traces to Zipkin.

## Tech stack

- Java 25
- Spring Boot 4.1 (Actuator, Zipkin tracing)
- Spring Cloud 2025.1 (Gateway Server WebMVC, Netflix Eureka client, LoadBalancer, Config client, Bus AMQP, Circuit Breaker with Resilience4j)
- Maven (the wrapper is included, so you don't need to install Maven)
- Docker (optional, to run everything with Docker Compose)

## How the services fit together

| Service                | Port(s)         | What it does                                                        |
|------------------------|-----------------|---------------------------------------------------------------------|
| `rabbitmq`             | `5672`, `15672` | Message broker for Spring Cloud Bus config refreshes. 15672 is the management UI. |
| `discovery-server`     | `8761`          | Eureka registry. The other services register here.                  |
| `config-server`        | `8888`          | Serves shared settings to the other services.                       |
| `taskflow`             | `8080`, `61616` | Task REST API. Also runs the embedded Artemis message broker on 61616. |
| `notification-service` | `8081`          | Stores a notification each time a task is created.                  |
| `api-gateway`          | `8082`          | This project. Routes requests to TaskFlow and the notification service. |

```
               ┌──────────────────┐        ┌───────────────┐
               │ discovery-server │ :8761  │ config-server │ :8888
               └──────────────────┘        └───────────────┘
                 ▲      ▲      ▲             settings for every service
       looks up  │      │      │  register
                 │      │      │
client ──► api-gateway  │      │
            :8082 │     │      │
                  ├─ /tasks/** ──────────────► taskflow  :8080
                  │                              │  TaskCreatedEvent (JMS, :61616)
                  │                              ▼
                  └─ /notifications/** ──────► notification-service  :8081
                     (circuit breaker)
```

When you create a task through the gateway:

1. The gateway gives the request a correlation ID, unless the client already sent one.
2. It matches `/tasks/**`, asks Eureka for a `TASKFLOW` instance and forwards the request unchanged, including the `Authorization` and `X-Correlation-Id` headers.
3. TaskFlow checks the login, saves the task and returns it.
4. TaskFlow publishes a `TaskCreatedEvent` to `task.created.queue` on its embedded Artemis broker.
5. The notification service receives the event and stores `"Task created: <title>"`.

## Getting started

### Prerequisites

- JDK 25 or newer
- The other projects (`discovery-server`, `config-server`, `taskflow`, `notification-service`) checked out next to this one
- RabbitMQ on `localhost:5672`, for config refreshes. The quickest way is `docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management`.
- Optional: Zipkin on `localhost:9411`, to collect traces. Run it with `docker run -d -p 9411:9411 openzipkin/zipkin`.

> **The gateway needs the config server to start.** `ConfigProbeController` reads `management.tracing.sampling.probability`, and that value is only set in the config server's shared file. If the config server isn't running, the gateway fails at startup with `Could not resolve placeholder`.

### Option 1: Docker Compose

The `taskflow-compose` project, next to this one, builds and starts all six services in the right order and waits for each to be healthy:

```bash
cd ../taskflow-compose
docker compose up -d --build
```

See its README for the details. This project's `Dockerfile` builds the gateway image it uses.

### Option 2: Run each service by hand

Start the services in this order, each in its own terminal:

1. RabbitMQ
2. `discovery-server`
3. `config-server`
4. `taskflow`. It must start before the notification service, because the notification service connects to the broker that runs inside TaskFlow.
5. `notification-service`
6. `api-gateway`

In each project folder, run:

```bash
# macOS / Linux
./mvnw spring-boot:run

# Windows
mvnw.cmd spring-boot:run
```

Check the Eureka dashboard at `http://localhost:8761`. `TASKFLOW` and `NOTIFICATION-SERVICE` should both be listed before you send requests through the gateway. Registration can take up to 30 seconds after a service starts.

### Run the tests

```bash
./mvnw test        # or mvnw.cmd test on Windows
```

`ApiGatewayApplicationTests` checks that the application starts. Because of the config server requirement above, it only passes while the config server is running.

## Routes

| Path                | Forwarded to                  | Service ID in Eureka   | Filters         |
|---------------------|-------------------------------|------------------------|-----------------|
| `/tasks/**`         | `lb://TASKFLOW`               | `TASKFLOW`             | —               |
| `/notifications/**` | `lb://NOTIFICATION-SERVICE`   | `NOTIFICATION-SERVICE` | Circuit breaker |

Paths are forwarded as they are, so `GET :8082/tasks/1` becomes `GET /tasks/1` on TaskFlow.

The gateway also answers these paths itself:

| Path                              | What it returns |
|-----------------------------------|-----------------|
| `POST /actuator/busrefresh`       | Tells every service, through RabbitMQ, to reload its settings from the config server. |
| `GET /internal/sampling-probability` | The current trace sampling rate. Use it to check that a refresh worked. |
| `/fallback/notifications`         | `503` with a JSON `message`. The circuit breaker sends requests here when the notification service is failing. |

Any other path returns `404 Not Found`.

Routes are set in `application.properties` under `spring.cloud.gateway.server.webmvc.routes`. The older `spring.cloud.gateway.routes` prefix is ignored by this version, and the gateway then returns `404` for every path.

## Circuit breaker

The `/notifications/**` route uses a Resilience4j circuit breaker named `notification-service`. Its settings are in `application.properties`:

| Setting | Value | Meaning |
|---|---|---|
| `sliding-window-size` | `4` | Judge the service on its last 4 calls. |
| `failure-rate-threshold` | `50` | Open the circuit when half of those calls fail. |
| `slow-call-duration-threshold` | `2s` | A call that takes longer than 2 seconds counts as slow. |
| `slow-call-rate-threshold` | `50` | Open the circuit when half of the calls are slow. |
| `wait-duration-in-open-state` | `10s` | Stay open for 10 seconds before trying again. |
| `permitted-number-of-calls-in-half-open-state` | `2` | Then let 2 trial calls through to decide whether to close. |

While the circuit is open, requests don't reach the notification service at all. They go straight to `/fallback/notifications` and get a `503`.

The filter's argument for the circuit breaker's name is `id`, not `name`. With `name`, every request fails with `A CircuitBreaker must have an id`.

## Configuration

Settings come from two places:

| File | What's in it |
|---|---|
| `src/main/resources/application.properties` | The gateway's own settings: port, routes, circuit breaker, and the config server address. |
| `config-server/src/main/resources/config/application.properties` | Settings shared by every service: the Eureka address, the log pattern that prints the correlation ID, and the trace sampling rate. |

The config server is imported with `optional:`, so a missing config server doesn't stop the import itself. The gateway still fails to start for the reason given under [Prerequisites](#prerequisites).

### Change a shared setting without restarting

1. Edit the shared file, for example `management.tracing.sampling.probability`.
2. If you run the config server in Docker Compose, restart it with `docker compose restart config-server`. When run by hand, it reads the file on each request.
3. Send a refresh through the gateway:

   ```bash
   curl -X POST http://localhost:8082/actuator/busrefresh
   ```

4. Check the new value:

   ```bash
   curl http://localhost:8082/internal/sampling-probability
   ```

`spring.cloud.gateway.server.webmvc.function.enabled=false` in `application.properties` is needed because of the Bus dependency. Bus brings in Spring Cloud Function, and without this setting the gateway adds a catch-all `/{path}` route that hides the gateway's own endpoints.

## Correlation IDs

`CorrelationIdFilter` runs on every request:

- If the request has an `X-Correlation-Id` header, the gateway keeps it. Otherwise it creates a random UUID.
- It forwards the ID to the downstream service and returns it in the response's `X-Correlation-Id` header.
- It prints the ID in each log line written while handling that request, for example `INFO [3f1c...]`.

To follow one request across services, send your own ID:

```bash
curl -i -H "X-Correlation-Id: my-test-1" http://localhost:8082/tasks
```

## Authentication

The gateway doesn't check logins. It passes the `Authorization` header through, and TaskFlow checks it. TaskFlow uses HTTP Basic with these accounts:

| Username | Password    | Role    |
|----------|-------------|---------|
| `admin`  | `adminpass` | `ADMIN` |
| `alice`  | `alicepass` | `USER`  |

Reading tasks needs no login, creating and updating need any login, and deleting needs `admin`. The notification service has no login at all. See the TaskFlow README for the full rules.

The gateway's own endpoints, including `/actuator/busrefresh`, also have no login.

## Example with curl

```bash
# Create a task (-i shows the status line, so a failed login isn't silent)
curl -i -u alice:alicepass -X POST http://localhost:8082/tasks \
  -H "Content-Type: application/json" \
  -d '{"title": "Routed through the gateway"}'

# List tasks
curl http://localhost:8082/tasks

# List notifications
curl http://localhost:8082/notifications
```

On Windows PowerShell, use `curl.exe`, put each command on one line, and escape the inner quotes, for example `-d '{\"title\": \"Routed through the gateway\"}'`.

## Troubleshooting

| What you see | Likely cause |
|---|---|
| Startup fails with `Could not resolve placeholder 'management.tracing.sampling.probability'` | The config server isn't running, or isn't reachable on `localhost:8888`. |
| `404` with `"path":"/tasks"` for every request | The routes weren't loaded. Check that they use the `spring.cloud.gateway.server.webmvc.routes` prefix. |
| `404` for `/actuator/busrefresh` or `/internal/...` | `spring.cloud.gateway.server.webmvc.function.enabled=false` is missing. |
| `503 Service Unavailable` on `/tasks` | TaskFlow isn't registered in Eureka yet. Check `http://localhost:8761` and wait for it to appear. |
| `503` with a JSON `message` on `/notifications` | The circuit breaker is open. Check that the notification service is running, then wait 10 seconds. |
| `401 Unauthorized` with an empty body on `POST /tasks` | Wrong or missing login. Use `alice:alicepass` or `admin:adminpass`. |
| Tasks are created but `/notifications` stays empty | The notification service started before TaskFlow, or can't reach `localhost:61616`. Restart it after TaskFlow is up. |
| `Dropped N spans due to ConnectException` in the log | No Zipkin server on `localhost:9411`. Requests aren't affected. Start Zipkin or ignore the warning. |
| Connection errors to `localhost:5672` | RabbitMQ isn't running. Routing still works, but `/actuator/busrefresh` doesn't. |
| Data is gone | TaskFlow and the notification service keep data in memory. Restarting either one clears it. |

## Project structure

```
src/main/java/com/example/api_gateway/
├── ApiGatewayApplication.java              # Application entry point
├── config/ConfigProbeController.java       # GET /internal/sampling-probability, refreshed by Bus
├── controller/FallbackController.java      # 503 response when the notification circuit is open
└── filter/CorrelationIdFilter.java         # Adds and logs the X-Correlation-Id header
src/main/resources/
└── application.properties                  # Port, routes, circuit breaker, config server address
Dockerfile                                  # Two-stage image build, used by taskflow-compose
```
