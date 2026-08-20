package com.inditex.similarproducts.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.inditex.similarproducts.config.ProductsProperties.CacheProperties;
import com.inditex.similarproducts.domain.ProductCatalog;
import com.inditex.similarproducts.domain.ProductDetail;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CachingProductCatalogTest {

	private static final ProductDetail SHIRT = new ProductDetail("1", "Shirt", 9.99, true);
	private static final CacheProperties CACHE = new CacheProperties(true, Duration.ofSeconds(60),
			Duration.ofSeconds(60), 1000);

	@Test
	void servesTheSecondCallFromMemory() {
		CountingCatalog upstream = new CountingCatalog(Mono.just(SHIRT));
		CachingProductCatalog catalog = new CachingProductCatalog(upstream, CACHE);

		assertThat(catalog.detail("1").block()).isEqualTo(SHIRT);
		assertThat(catalog.detail("1").block()).isEqualTo(SHIRT);
		assertThat(upstream.calls).hasValue(1);
	}

	@Test
	void collapsesConcurrentCallsForTheSameProductIntoOneUpstreamRequest() {
		CountingCatalog upstream = new CountingCatalog(Mono.just(SHIRT).delayElement(Duration.ofMillis(200)));
		CachingProductCatalog catalog = new CachingProductCatalog(upstream, CACHE);

		List<ProductDetail> results = Flux.range(0, 50)
				.flatMap(i -> catalog.detail("1"))
				.collectList()
				.block(Duration.ofSeconds(5));

		assertThat(results).hasSize(50);
		assertThat(upstream.calls).hasValue(1);
	}

	@Test
	void cachesTheAbsenceOfAProduct() {
		CountingCatalog upstream = new CountingCatalog(Mono.empty());
		CachingProductCatalog catalog = new CachingProductCatalog(upstream, CACHE);

		StepVerifier.create(catalog.detail("5")).verifyComplete();
		StepVerifier.create(catalog.detail("5")).verifyComplete();
		assertThat(upstream.calls).hasValue(1);
	}

	@Test
	void doesNotCacheFailures() {
		CountingCatalog upstream = new CountingCatalog(Mono.error(new IllegalStateException("boom")));
		CachingProductCatalog catalog = new CachingProductCatalog(upstream, CACHE);

		StepVerifier.create(catalog.detail("6")).verifyError();
		StepVerifier.create(catalog.detail("6")).verifyError();
		assertThat(upstream.calls).hasValue(2);
	}

	@Test
	void aCancelledSubscriberDoesNotAbortTheSharedUpstreamCall() {
		CountingCatalog upstream = new CountingCatalog(Mono.just(SHIRT).delayElement(Duration.ofMillis(300)));
		CachingProductCatalog catalog = new CachingProductCatalog(upstream, CACHE);

		StepVerifier.create(catalog.detail("1").timeout(Duration.ofMillis(50))).verifyError();

		assertThat(catalog.detail("1").block(Duration.ofSeconds(2))).isEqualTo(SHIRT);
		assertThat(upstream.calls).hasValue(1);
	}

	private static final class CountingCatalog implements ProductCatalog {

		private final AtomicInteger calls = new AtomicInteger();
		private final Mono<ProductDetail> response;

		private CountingCatalog(Mono<ProductDetail> response) {
			this.response = response;
		}

		@Override
		public Flux<String> similarIds(String productId) {
			return Flux.defer(() -> {
				calls.incrementAndGet();
				return Flux.just("1", "2");
			});
		}

		@Override
		public Mono<ProductDetail> detail(String productId) {
			return Mono.defer(() -> {
				calls.incrementAndGet();
				return response;
			});
		}
	}
}
