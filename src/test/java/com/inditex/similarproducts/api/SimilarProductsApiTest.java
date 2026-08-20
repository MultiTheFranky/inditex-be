package com.inditex.similarproducts.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.inditex.similarproducts.domain.ProductDetail;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * End-to-end test of the agreed contract against a stubbed version of the existing APIs, mirroring
 * the scenarios exercised by the k6 test (normal, not found, error and slow products).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "products.cache.enabled=false", "products.fan-out-budget=500ms" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimilarProductsApiTest {

	private static final MockWebServer UPSTREAM = new MockWebServer();

	private static final Map<String, String> SIMILAR_IDS = Map.of(
			"/product/1/similarids", "[2,3,4]",
			"/product/4/similarids", "[1,2,5]",
			"/product/5/similarids", "[1,2,6]",
			"/product/2/similarids", "[3,1000]");

	private static final Map<String, String> DETAILS = Map.of(
			"/product/1", "{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}",
			"/product/2", "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}",
			"/product/3", "{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}",
			"/product/4", "{\"id\":\"4\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}");

	@Autowired
	private WebTestClient client;

	@BeforeAll
	static void startUpstream() throws IOException {
		UPSTREAM.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String path = request.getPath();
				if (SIMILAR_IDS.containsKey(path)) {
					return json(SIMILAR_IDS.get(path));
				}
				if (DETAILS.containsKey(path)) {
					return json(DETAILS.get(path));
				}
				if ("/product/6".equals(path)) {
					return new MockResponse().setResponseCode(500);
				}
				if ("/product/1000".equals(path)) {
					return json(DETAILS.get("/product/1")).setBodyDelay(5, TimeUnit.SECONDS);
				}
				return new MockResponse().setResponseCode(404);
			}
		});
		UPSTREAM.start();
	}

	@AfterAll
	static void stopUpstream() throws IOException {
		UPSTREAM.shutdown();
	}

	@DynamicPropertySource
	static void upstreamUrl(DynamicPropertyRegistry registry) {
		registry.add("products.base-url", () -> "http://localhost:" + UPSTREAM.getPort());
	}

	@Test
	void returnsTheDetailOfEverySimilarProductInOrder() {
		similar("1").expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
				.expectBodyList(ProductDetail.class)
				.isEqualTo(List.of(new ProductDetail("2", "Dress", 19.99, true),
						new ProductDetail("3", "Blazer", 29.99, false),
						new ProductDetail("4", "Boots", 39.99, true)));
	}

	@Test
	void returns404WhenTheRequestedProductDoesNotExist() {
		similar("does-not-exist").expectStatus().isNotFound();
	}

	@Test
	void omitsSimilarProductsThatDoNotExist() {
		similar("4").expectStatus().isOk()
				.expectBodyList(ProductDetail.class)
				.hasSize(2);
	}

	@Test
	void omitsSimilarProductsWhoseDetailFails() {
		similar("5").expectStatus().isOk()
				.expectBodyList(ProductDetail.class)
				.hasSize(2);
	}

	/**
	 * Runs last on purpose: the abandoned upstream response stays in flight for a few seconds and
	 * would slow down the connections reused by the other scenarios.
	 */
	@Test
	@Order(Integer.MAX_VALUE)
	void omitsSimilarProductsSlowerThanTheFanOutBudget() {
		similar("2").expectStatus().isOk()
				.expectBodyList(ProductDetail.class)
				.hasSize(1);
	}

	private WebTestClient.ResponseSpec similar(String productId) {
		return client.get().uri("/product/{productId}/similar", productId).exchange();
	}

	private static MockResponse json(String body) {
		return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
	}
}
