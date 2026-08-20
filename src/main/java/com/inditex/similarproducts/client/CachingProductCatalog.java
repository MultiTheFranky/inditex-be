package com.inditex.similarproducts.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.config.ProductsProperties.CacheProperties;
import com.inditex.similarproducts.domain.ProductCatalog;
import com.inditex.similarproducts.domain.ProductDetail;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Decorates a {@link ProductCatalog} with an in-memory, asynchronous cache.
 *
 * <p>Beyond saving round trips, the asynchronous cache <em>collapses</em> concurrent requests: N
 * simultaneous callers asking for the same product share a single in-flight upstream call. That is
 * what keeps the upstream load flat under a burst of traffic on the same catalog entries.
 *
 * <p>Failed calls are never cached (Caffeine drops futures that complete exceptionally), while a
 * "product does not exist" answer is, since it is a valid and stable result.
 */
public class CachingProductCatalog implements ProductCatalog {

	private final ProductCatalog delegate;
	private final AsyncCache<String, List<String>> similarIdsCache;
	private final AsyncCache<String, Optional<ProductDetail>> detailCache;

	public CachingProductCatalog(ProductCatalog delegate, CacheProperties properties) {
		this.delegate = delegate;
		this.similarIdsCache = newCache(properties.maxSize(), properties.similarIdsTtl());
		this.detailCache = newCache(properties.maxSize(), properties.detailTtl());
	}

	@Override
	public Flux<String> similarIds(String productId) {
		return shared(() -> similarIdsCache.get(productId,
				(id, executor) -> delegate.similarIds(id).collectList().toFuture()))
				.flatMapMany(Flux::fromIterable);
	}

	@Override
	public Mono<ProductDetail> detail(String productId) {
		return shared(() -> detailCache.get(productId, (id, executor) -> delegate.detail(id)
				.map(Optional::of)
				.defaultIfEmpty(Optional.empty())
				.toFuture()))
				.flatMap(Mono::justOrEmpty);
	}

	public AsyncCache<String, List<String>> similarIdsCache() {
		return similarIdsCache;
	}

	public AsyncCache<String, Optional<ProductDetail>> detailCache() {
		return detailCache;
	}

	/**
	 * {@code suppressCancel} is essential here: a caller that gives up (fan-out budget exceeded)
	 * must not cancel the upstream call it shares with the other subscribers. Letting it finish
	 * warms the cache for the requests that follow.
	 */
	private static <T> Mono<T> shared(Supplier<CompletableFuture<T>> future) {
		return Mono.fromFuture(future, true);
	}

	private static <T> AsyncCache<String, T> newCache(long maxSize, Duration ttl) {
		return Caffeine.newBuilder()
				.maximumSize(maxSize)
				.expireAfterWrite(ttl)
				.recordStats()
				.buildAsync();
	}
}
