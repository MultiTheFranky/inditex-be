package com.inditex.similarproducts.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import reactor.test.StepVerifier;

class ProductApiClientTest {

	private MockWebServer upstream;
	private ProductApiClient client;

	@BeforeEach
	void startUpstream() throws IOException {
		upstream = new MockWebServer();
		upstream.start();
		client = clientWith(Duration.ofSeconds(2), 1);
	}

	@AfterEach
	void stopUpstream() throws IOException {
		upstream.shutdown();
	}

	@Test
	void readsSimilarIdsEvenWhenTheUpstreamSerialisesThemAsNumbers() {
		upstream.enqueue(json("[2,3,4]"));

		StepVerifier.create(client.similarIds("1"))
				.expectNext("2", "3", "4")
				.verifyComplete();
	}

	@Test
	void failsWithNotFoundWhenTheProductHasNoSimilarIds() {
		upstream.enqueue(new MockResponse().setResponseCode(404));

		StepVerifier.create(client.similarIds("999"))
				.expectError(ProductNotFoundException.class)
				.verify();
	}

	@Test
	void readsTheProductDetail() {
		upstream.enqueue(json("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}"));

		StepVerifier.create(client.detail("1"))
				.expectNext(new ProductDetail("1", "Shirt", 9.99, true))
				.verifyComplete();
	}

	@Test
	void returnsEmptyWhenTheDetailDoesNotExist() {
		upstream.enqueue(new MockResponse().setResponseCode(404));

		StepVerifier.create(client.detail("5"))
				.verifyComplete();
	}

	@Test
	void retriesServerErrorsOnceAndThenGivesUp() {
		upstream.enqueue(new MockResponse().setResponseCode(500));
		upstream.enqueue(new MockResponse().setResponseCode(500));

		StepVerifier.create(client.detail("6"))
				.expectError(CatalogUnavailableException.class)
				.verify();

		assertThat(upstream.getRequestCount()).isEqualTo(2);
	}

	@Test
	void recoversWhenTheRetriedAttemptSucceeds() {
		upstream.enqueue(new MockResponse().setResponseCode(503));
		upstream.enqueue(json("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}"));

		StepVerifier.create(client.detail("1"))
				.expectNext(new ProductDetail("1", "Shirt", 9.99, true))
				.verifyComplete();
	}

	@Test
	void doesNotRetryTimeouts() {
		ProductApiClient impatient = clientWith(Duration.ofMillis(200), 1);
		upstream.enqueue(json("{\"id\":\"1\"}").setBodyDelay(2, TimeUnit.SECONDS));

		StepVerifier.create(impatient.detail("1"))
				.expectError(CatalogUnavailableException.class)
				.verify(Duration.ofSeconds(5));

		assertThat(upstream.getRequestCount()).isEqualTo(1);
	}

	private ProductApiClient clientWith(Duration timeout, int maxRetries) {
		ProductsProperties properties = TestProperties.with("http://localhost:" + upstream.getPort(), timeout,
				maxRetries);
		WebClient webClient = new WebClientConfig().productsWebClient(WebClient.builder(), properties);
		return new ProductApiClient(webClient, CircuitBreakerRegistry.ofDefaults(), properties);
	}

	private static MockResponse json(String body) {
		return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
	}
}
