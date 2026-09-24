# Similar products API

Spring Boot service that implements the agreed contract ([`api/similarProducts.yaml`](api/similarProducts.yaml))
on port **5000**, on top of the two existing APIs ([`api/existingApis.yaml`](api/existingApis.yaml)).

```
GET /product/{productId}/similar  ->  200 [ProductDetail, ...]  |  404
```

## Run it

```bash
# 1. Mocks + metrics infrastructure
docker-compose up -d simulado influxdb grafana

# 2. The app (port 5000)
./mvnw spring-boot:run          # or: ./mvnw package && java -jar target/similar-products-1.0.0.jar
                                # or: docker-compose up -d --build app

# 3. The load test
docker-compose run --rm k6 run scripts/test.js
```

Results at <http://localhost:3000/d/Le2Ku9NMk/k6-performance-test>.
Requires JDK 17+. On macOS, free port 5000 first by turning off *System Settings → General →
AirDrop & Handoff → AirPlay Receiver*.

```bash
./mvnw test    # 28 unit + integration tests, no infrastructure needed
```

## Design

```
SimilarProductsController ──► SimilarProductsService ──► ProductCatalog
        (api)                       (domain)                (domain port)
                                                                 ▲
                                                    CachingProductCatalog
                                                                 ▲
                                                         ProductApiClient ──► existing APIs
```

`SimilarProductsService` only knows the `ProductCatalog` port, so caching, retries, timeouts and
circuit breaking are composed as decorators around the HTTP adapter instead of leaking into the
business logic.

The stack is fully reactive (WebFlux + WebClient). Each request fans out to N detail calls that a
blocking stack would need N threads to serve; here they share a handful of event-loop threads, which
is what keeps 200 concurrent users cheap.

### Performance

| Decision                                              | Why                                                                                                                                                                                                   |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Parallel fan-out** (`flatMap`, bounded concurrency) | the detail calls are independent, so the response costs the slowest call, not their sum. Similarity order is restored afterwards by index.                                                            |
| **Asynchronous cache** (Caffeine `AsyncCache`)        | it does not only avoid round trips: it *collapses* concurrent requests, so 200 users asking for the same product produce **one** upstream call. This is the single biggest lever under the test load. |
| **Pooled, bounded HTTP connections**                  | connection reuse removes the TCP handshake from the hot path, and the bound caps how much load we can ever push upstream.                                                                             |
| **Deduplicated ids**                                  | the contract declares the list as unique; deduplicating avoids paying twice for a repeated id.                                                                                                        |

### Resilience

| Decision                                                  | Why                                                                                                                                                                                                         |
| --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Degrade, don't fail**                                   | a similar product whose detail is missing (404), broken (500) or too slow is omitted from the response instead of turning the whole request into an error. One bad product never takes the feature down.    |
| **Fan-out time budget** (`products.fan-out-budget`, 2s)   | bounds the response time regardless of how slow a single detail is. The abandoned call is *not* cancelled: it keeps running behind the shared cache entry, so the next requests do get the complete answer. |
| **Timeouts everywhere**                                   | connect (500ms), similar ids (2s) and detail (10s). No call can pin a connection forever.                                                                                                                   |
| **Bounded retry with jittered backoff**                   | one retry for connection errors and 5xx. Timeouts are *not* retried on purpose: the attempt already spent its whole budget and retrying would only add load to a struggling upstream.                       |
| **Circuit breaker per upstream operation** (Resilience4j) | when the detail endpoint keeps failing, calls fail fast instead of queueing behind a dead dependency. `ProductNotFoundException` is ignored so a legitimate 404 never opens the circuit.                    |
| **Only successful answers are cached**                    | failures are evicted immediately (so we recover as soon as the upstream does), while a "product does not exist" answer is cached, since it is a valid and stable result.                                    |

`404` is returned only when the *requested* product has no similar ids; every other upstream problem
maps to `502` with a `application/problem+json` body.

### Observability

`/actuator/health` (including circuit breaker state), `/actuator/metrics`, `/actuator/caches` and
`/actuator/circuitbreakers`. Cache hit ratios and connection pool usage are exported to Micrometer.

## Load test results

Full k6 suite (5 scenarios × 200 VUs) against the provided mocks:

```
http_req_failed ... 0.00%  ✓ 0  ✗ 16600
http_req_duration . med=8.58ms  p(90)=49.79ms  p(95)=62.71ms  max=2.02s
http_reqs ......... 16600  276.3/s
```

The `max` is the fan-out budget kicking in for `verySlow` (a similar product whose detail takes 50s);
after a few of those the circuit breaker opens and those requests also return in milliseconds.

## Configuration

Everything is tunable through `application.yml` / environment variables, no rebuild needed:

| Property                         | Default                 |                                   |
| -------------------------------- | ----------------------- | --------------------------------- |
| `products.base-url`              | `http://localhost:3001` | env `PRODUCTS_API_BASE_URL`       |
| `products.fan-out-budget`        | `2s`                    | max time spent resolving details  |
| `products.fan-out-concurrency`   | `16`                    | parallel detail calls per request |
| `products.detail-timeout`        | `10s`                   | per detail call                   |
| `products.similar-ids-timeout`   | `2s`                    | per similar-ids call              |
| `products.max-retries`           | `1`                     | retries for transient failures    |
| `products.cache.detail-ttl`      | `300s`                  |                                   |
| `products.cache.similar-ids-ttl` | `60s`                   |                                   |
| `products.cache.enabled`         | `true`                  |                                   |

## Trade-offs and next steps

- **Omitting slow products is a deliberate trade-off.** With real catalog data (thousands of
  products instead of five) the cache would matter less and the budget more; both are configurable
  per environment.
- The cache is **local to the instance**. It is the right default for this workload (tiny, hot, read
  only data), but a shared cache would be a better fit once the catalog no longer fits in memory or
  invalidation needs to be coordinated.
- Cache entries expire by TTL. If the upstream published change events, a
  **stale-while-revalidate** policy would remove the cold-start penalty entirely.
