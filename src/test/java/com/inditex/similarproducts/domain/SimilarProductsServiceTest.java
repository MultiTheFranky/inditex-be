package com.inditex.similarproducts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.inditex.similarproducts.config.ProductsProperties;
import com.inditex.similarproducts.config.ProductsProperties.CacheProperties;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SimilarProductsServiceTest {

	private static final ProductDetail SHIRT = new ProductDetail("1", "Shirt", 9.99, true);
	private static final ProductDetail DRESS = new ProductDetail("2", "Dress", 19.99, true);
	private static final ProductDetail BLAZER = new ProductDetail("3", "Blazer", 29.99, false);

	@Test
	void returnsTheDetailsInSimilarityOrder() {
		SimilarProductsService service = serviceFor(new StubCatalog(
				Map.of("1", List.of("3", "2")),
				Map.of("2", Mono.just(DRESS), "3", Mono.just(BLAZER))));

		StepVerifier.create(service.similarProducts("1"))
				.expectNext(List.of(BLAZER, DRESS))
				.verifyComplete();
	}

	@Test
	void skipsProductsThatDoNotExist() {
		SimilarProductsService service = serviceFor(new StubCatalog(
				Map.of("4", List.of("1", "2", "5")),
				Map.of("1", Mono.just(SHIRT), "2", Mono.just(DRESS), "5", Mono.empty())));

		StepVerifier.create(service.similarProducts("4"))
				.expectNext(List.of(SHIRT, DRESS))
				.verifyComplete();
	}

	@Test
	void skipsProductsWhoseDetailFails() {
		SimilarProductsService service = serviceFor(new StubCatalog(
				Map.of("5", List.of("1", "2", "6")),
				Map.of("1", Mono.just(SHIRT), "2", Mono.just(DRESS),
						"6", Mono.error(new CatalogUnavailableException("boom", null)))));

		StepVerifier.create(service.similarProducts("5"))
				.expectNext(List.of(SHIRT, DRESS))
				.verifyComplete();
	}

	@Test
	void omitsDetailsThatExceedTheFanOutBudget() {
		SimilarProductsService service = serviceFor(new StubCatalog(
				Map.of("2", List.of("3", "1000")),
				Map.of("3", Mono.just(BLAZER),
						"1000", Mono.just(SHIRT).delayElement(Duration.ofSeconds(5)))),
				Duration.ofMillis(200));

		StepVerifier.create(service.similarProducts("2"))
				.expectNext(List.of(BLAZER))
				.verifyComplete();
	}

	@Test
	void propagatesNotFoundWhenTheProductItselfDoesNotExist() {
		SimilarProductsService service = serviceFor(new StubCatalog(Map.of(), Map.of()));

		StepVerifier.create(service.similarProducts("does-not-exist"))
				.expectError(ProductNotFoundException.class)
				.verify();
	}

	@Test
	void requestsEveryDistinctIdOnlyOnce() {
		StubCatalog catalog = new StubCatalog(
				Map.of("1", List.of("2", "2", "3")),
				Map.of("2", Mono.just(DRESS), "3", Mono.just(BLAZER)));

		SimilarProductsService service = serviceFor(catalog);

		assertThat(service.similarProducts("1").block()).containsExactly(DRESS, BLAZER);
		assertThat(catalog.detailCalls).isEqualTo(2);
	}

	private static SimilarProductsService serviceFor(ProductCatalog catalog) {
		return serviceFor(catalog, Duration.ofSeconds(2));
	}

	private static SimilarProductsService serviceFor(ProductCatalog catalog, Duration budget) {
		ProductsProperties properties = new ProductsProperties(null, Duration.ofMillis(500), Duration.ofSeconds(2),
				Duration.ofSeconds(10), budget, 16, 100, Duration.ofSeconds(2), 1, Duration.ofMillis(20),
				new CacheProperties(false, Duration.ofSeconds(60), Duration.ofSeconds(300), 1000));
		return new SimilarProductsService(catalog, properties);
	}

	private static final class StubCatalog implements ProductCatalog {

		private final Map<String, List<String>> similar;
		private final Map<String, Mono<ProductDetail>> details;
		private int detailCalls;

		private StubCatalog(Map<String, List<String>> similar, Map<String, Mono<ProductDetail>> details) {
			this.similar = similar;
			this.details = details;
		}

		@Override
		public Flux<String> similarIds(String productId) {
			List<String> ids = similar.get(productId);
			return ids == null ? Flux.error(new ProductNotFoundException(productId)) : Flux.fromIterable(ids);
		}

		@Override
		public Mono<ProductDetail> detail(String productId) {
			detailCalls++;
			return details.getOrDefault(productId, Mono.empty());
		}
	}
}
