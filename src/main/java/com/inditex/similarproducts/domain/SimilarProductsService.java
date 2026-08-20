package com.inditex.similarproducts.domain;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.inditex.similarproducts.config.ProductsProperties;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

/**
 * Builds the similar-products view: resolves the similar ids and fans out to the detail endpoint.
 *
 * <p>Two deliberate resilience decisions:
 * <ul>
 *   <li>a product whose detail cannot be resolved is <em>omitted</em> instead of failing the whole
 *       response, so one broken product never takes the feature down;</li>
 *   <li>the fan-out has a global time budget: details that are still in flight when it expires are
 *       omitted from this response, while the underlying call keeps running to warm the cache.</li>
 * </ul>
 */
@Service
public class SimilarProductsService {

	private static final Logger log = LoggerFactory.getLogger(SimilarProductsService.class);

	private final ProductCatalog catalog;
	private final Duration budget;
	private final int concurrency;

	public SimilarProductsService(ProductCatalog catalog, ProductsProperties properties) {
		this.catalog = catalog;
		this.budget = properties.fanOutBudget();
		this.concurrency = properties.fanOutConcurrency();
	}

	public Mono<List<ProductDetail>> similarProducts(String productId) {
		return catalog.similarIds(productId)
				.distinct()
				.index()
				.flatMap(this::resolveDetail, concurrency)
				.takeUntilOther(Mono.delay(budget))
				.collectSortedList(Comparator.comparingLong(Tuple2::getT1))
				.map(SimilarProductsService::detailsInSimilarityOrder);
	}

	private Mono<Tuple2<Long, ProductDetail>> resolveDetail(Tuple2<Long, String> indexedId) {
		return catalog.detail(indexedId.getT2())
				.map(detail -> Tuples.of(indexedId.getT1(), detail))
				.onErrorResume(error -> {
					log.debug("Skipping product {}: {}", indexedId.getT2(), error.toString());
					return Mono.empty();
				});
	}

	private static List<ProductDetail> detailsInSimilarityOrder(List<Tuple2<Long, ProductDetail>> indexed) {
		return indexed.stream().map(Tuple2::getT2).toList();
	}
}
