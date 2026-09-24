package com.inditex.similarproducts.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.inditex.similarproducts.TestProperties;
import com.inditex.similarproducts.config.ProductsProperties;
import com.inditex.similarproducts.config.WebClientConfig;
import com.inditex.similarproducts.domain.CatalogUnavailableException;
import com.inditex.similarproducts.domain.ProductDetail;
import com.inditex.similarproducts.domain.ProductNotFoundException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import reactor.test.StepVerifier;

/**
 * Circuit breaking is configured declaratively in {@code application.yml}; this
 * test uses an
 * equivalent but much smaller window so the transitions happen within a few
 * calls.
 */
class ProductApiClientCircuitBreakerTest {

    private static final int WINDOW = 4;
    private static final Duration OPEN_STATE = Duration.ofMillis(200);

    private MockWebServer upstream;
    private CircuitBreakerRegistry registry;
    private ProductApiClient client;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(WINDOW)
                .minimumNumberOfCalls(WINDOW)
                .failureRateThreshold(50)
                .waitDurationInOpenState(OPEN_STATE)
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreExceptions(ProductNotFoundException.class)
                .build());
        ProductsProperties properties = TestProperties.with("http://localhost:" + upstream.getPort(),
                Duration.ofSeconds(2), 0);
        WebClient webClient = new WebClientConfig().productsWebClient(WebClient.builder(), properties);
        client = new ProductApiClient(webClient, registry, properties);
    }

    @AfterEach
    void stopUpstream() throws IOException {
        upstream.shutdown();
    }

    @Test
    void opensAfterRepeatedFailuresAndThenFailsFastWithoutCallingTheUpstream() {
        failDetailCalls(WINDOW);

        assertThat(detailBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        StepVerifier.create(client.detail("6"))
                .expectError(CatalogUnavailableException.class)
                .verify();

        assertThat(upstream.getRequestCount()).isEqualTo(WINDOW);
        assertThat(detailBreaker().getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    @Test
    void eachUpstreamOperationHasItsOwnCircuit() {
        failDetailCalls(WINDOW);

        assertThat(detailBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.circuitBreaker(ProductApiClient.SIMILAR_IDS_BREAKER).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);

        upstream.enqueue(json("[2,3,4]"));
        StepVerifier.create(client.similarIds("1")).expectNext("2", "3", "4").verifyComplete();
    }

    @Test
    void missingProductsNeverOpenTheCircuit() {
        for (int i = 0; i < WINDOW * 2; i++) {
            upstream.enqueue(new MockResponse().setResponseCode(404));
            StepVerifier.create(client.detail("5")).verifyComplete();
        }

        assertThat(detailBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(detailBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void closesAgainOnceTheUpstreamRecovers() throws InterruptedException {
        failDetailCalls(WINDOW);
        Thread.sleep(OPEN_STATE.toMillis() + 100);

        upstream.enqueue(json("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}"));

        StepVerifier.create(client.detail("1"))
                .expectNext(new ProductDetail("1", "Shirt", 9.99, true))
                .verifyComplete();
        assertThat(detailBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void failDetailCalls(int times) {
        for (int i = 0; i < times; i++) {
            upstream.enqueue(new MockResponse().setResponseCode(500));
            StepVerifier.create(client.detail("6")).expectError(CatalogUnavailableException.class).verify();
        }
    }

    private CircuitBreaker detailBreaker() {
        return registry.circuitBreaker(ProductApiClient.DETAIL_BREAKER);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
